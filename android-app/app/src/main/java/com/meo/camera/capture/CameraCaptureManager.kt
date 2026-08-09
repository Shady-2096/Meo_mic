package com.meo.camera.capture

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Service-owned CameraX capture source.
 *
 * ImageAnalysis deliberately keeps a real 720p capture use case alive when the
 * Activity preview surface goes away. Its frames are only measured for now;
 * the WebRTC integration will either consume that analyzer or replace it with
 * a Camera2 capturer after the measured adapter decision in ADR 0008.
 */
class CameraCaptureManager(
    context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameRateTracker = FrameRateTracker()

    private val _state = MutableStateFlow(CameraCaptureState())
    val state: StateFlow<CameraCaptureState> = _state.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var startGeneration = 0

    fun start(lens: CameraLens = _state.value.lens) {
        val generation = ++startGeneration
        _state.update {
            it.copy(
                status = CameraSessionStatus.Starting,
                lens = lens,
                width = 0,
                height = 0,
                framesPerSecond = 0.0,
                errorMessage = null
            )
        }

        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener(
            {
                if (generation != startGeneration) return@addListener
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    bindCamera(provider, lens, generation)
                } catch (error: Exception) {
                    fail("Camera could not start: ${error.readableMessage()}")
                }
            },
            mainExecutor
        )
    }

    fun stop() {
        startGeneration += 1
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopOnMainThread()
        } else {
            mainExecutor.execute(::stopOnMainThread)
        }
    }

    fun attachPreview(provider: Preview.SurfaceProvider) {
        surfaceProvider = provider
        mainExecutor.execute { preview?.setSurfaceProvider(provider) }
    }

    fun detachPreview(provider: Preview.SurfaceProvider) {
        if (surfaceProvider !== provider) return
        surfaceProvider = null
        mainExecutor.execute { preview?.setSurfaceProvider(null) }
    }

    fun switchLens() {
        val nextLens = when (_state.value.lens) {
            CameraLens.Back -> CameraLens.Front
            CameraLens.Front -> CameraLens.Back
        }
        start(nextLens)
    }

    fun setZoomRatio(requestedRatio: Float) {
        val activeCamera = camera ?: return
        val appliedRatio = requestedRatio.coerceIn(
            _state.value.minZoomRatio,
            _state.value.maxZoomRatio
        )
        val result = activeCamera.cameraControl.setZoomRatio(appliedRatio)
        result.addListener(
            {
                try {
                    result.get()
                    if (camera === activeCamera) {
                        _state.update { it.copy(zoomRatio = appliedRatio) }
                    }
                } catch (_: Exception) {
                    // The camera may have been switched or stopped. Keep the
                    // last value acknowledged by CameraX.
                }
            },
            mainExecutor
        )
    }

    fun setTorch(enabled: Boolean) {
        val activeCamera = camera ?: return
        if (!_state.value.torchAvailable) return
        val result = activeCamera.cameraControl.enableTorch(enabled)
        result.addListener(
            {
                try {
                    result.get()
                    if (camera === activeCamera) {
                        _state.update { it.copy(torchEnabled = enabled) }
                    }
                } catch (_: Exception) {
                    // Keep the last applied value if the request was rejected.
                }
            },
            mainExecutor
        )
    }

    fun close() {
        stop()
        analysisExecutor.shutdown()
    }

    private fun bindCamera(
        provider: ProcessCameraProvider,
        lens: CameraLens,
        generation: Int
    ) {
        val hasBackCamera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        val hasFrontCamera = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        val selector = when (lens) {
            CameraLens.Back -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraLens.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
        }

        if (!provider.hasCamera(selector)) {
            fail("This device does not have a ${lens.name.lowercase()} camera.")
            return
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(
                    AspectRatio.RATIO_16_9,
                    AspectRatioStrategy.FALLBACK_RULE_AUTO
                )
            )
            .setResolutionStrategy(
                ResolutionStrategy(
                    TARGET_SIZE,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val previewUseCase = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .also { useCase ->
                preview = useCase
                surfaceProvider?.let(useCase::setSurfaceProvider)
            }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(analysisExecutor) { image ->
                    try {
                        val fps = frameRateTracker.sample(SystemClock.elapsedRealtimeNanos())
                        if (generation == startGeneration) {
                            _state.update { current ->
                                current.copy(
                                    status = CameraSessionStatus.Capturing,
                                    width = image.width,
                                    height = image.height,
                                    framesPerSecond = fps ?: current.framesPerSecond,
                                    errorMessage = null
                                )
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
            }

        try {
            provider.unbindAll()
            frameRateTracker.reset()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, previewUseCase, analysis)

            val zoomState = camera?.cameraInfo?.zoomState?.value
            _state.update {
                it.copy(
                    status = CameraSessionStatus.Starting,
                    lens = lens,
                    minZoomRatio = zoomState?.minZoomRatio ?: 1f,
                    maxZoomRatio = zoomState?.maxZoomRatio ?: 1f,
                    zoomRatio = zoomState?.zoomRatio ?: 1f,
                    hasFrontCamera = hasFrontCamera,
                    hasBackCamera = hasBackCamera,
                    torchAvailable = camera?.cameraInfo?.hasFlashUnit() == true,
                    torchEnabled = false,
                    errorMessage = null
                )
            }
        } catch (error: Exception) {
            fail("Camera configuration failed: ${error.readableMessage()}")
        }
    }

    private fun fail(message: String) {
        cameraProvider?.unbindAll()
        camera = null
        preview = null
        _state.update {
            it.copy(
                status = CameraSessionStatus.Error,
                framesPerSecond = 0.0,
                errorMessage = message
            )
        }
    }

    private fun stopOnMainThread() {
        cameraProvider?.unbindAll()
        camera = null
        preview = null
        frameRateTracker.reset()
        _state.value = CameraCaptureState(
            status = CameraSessionStatus.Idle,
            lens = _state.value.lens,
            hasFrontCamera = _state.value.hasFrontCamera,
            hasBackCamera = _state.value.hasBackCamera
        )
    }

    private fun Throwable.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private companion object {
        val TARGET_SIZE = Size(1280, 720)
    }
}

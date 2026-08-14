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
import com.meo.camera.encode.FrameSink
import com.meo.camera.encode.NullFrameSink
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
 * Activity preview surface goes away, and its frames are what reach the
 * network: [frameSink] receives every analysed frame, and is a WebRTC sink
 * while a desktop is streaming and [NullFrameSink] otherwise.
 *
 * ADR 0008 leaves open whether this is the right adapter or whether WebRTC's
 * own `Camera2Capturer` should own the camera instead. That decision needs
 * measured 720p30 CPU and thermal figures from real hardware; until then this
 * path is provisional and the seam is [FrameSink].
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

    /**
     * Where analysed frames go. Swapped for a WebRTC sink when a desktop starts
     * streaming and back to [NullFrameSink] when it stops, so capture keeps
     * running — and the preview keeps working — with nothing on the wire.
     *
     * Volatile rather than locked: it is written from the service thread and
     * read once per frame on the analysis thread, and a frame delivered to the
     * previous sink during a swap is harmless.
     */
    @Volatile
    var frameSink: FrameSink = NullFrameSink

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

    fun switchLens(): CameraLens {
        val nextLens = when (_state.value.lens) {
            CameraLens.Back -> CameraLens.Front
            CameraLens.Front -> CameraLens.Back
        }
        return setLens(nextLens)
    }

    /**
     * Returns the lens actually selected, which is the current one if this
     * device has no such camera. Plan §7.3: acknowledge the applied value.
     */
    fun setLens(lens: CameraLens): CameraLens {
        val state = _state.value
        val available = when (lens) {
            CameraLens.Back -> state.hasBackCamera
            CameraLens.Front -> state.hasFrontCamera
        }
        // Unknown availability means capture has not bound yet; try anyway and
        // let bindCamera report the failure.
        if (!available && (state.hasBackCamera || state.hasFrontCamera)) return state.lens
        start(lens)
        return lens
    }

    /** Returns the zoom that will be applied, clamped to the reported range. */
    fun setZoomRatio(requestedRatio: Float): Float {
        val appliedRatio = requestedRatio.coerceIn(
            _state.value.minZoomRatio,
            _state.value.maxZoomRatio
        )
        val activeCamera = camera ?: return _state.value.zoomRatio
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
        return appliedRatio
    }

    /** Returns false when this device has no torch, whatever was asked for. */
    fun setTorch(enabled: Boolean): Boolean {
        val activeCamera = camera ?: return _state.value.torchEnabled
        if (!_state.value.torchAvailable) return false
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
        return enabled
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
            // Stated rather than assumed. It is already the default, but the
            // frame sink converts from this exact format and a device that
            // handed it RGBA would silently drop every frame.
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(analysisExecutor) { image ->
                    try {
                        // Deliver before measuring, so the reported rate is the
                        // rate frames actually reached the encoder at.
                        if (generation == startGeneration) {
                            frameSink.onFrame(image)
                        }
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

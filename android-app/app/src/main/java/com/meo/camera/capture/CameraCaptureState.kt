package com.meo.camera.capture

enum class CameraLens(
    /**
     * The spelling used on the control channel. Kept next to the enum so both
     * directions of the mapping are impossible to change independently.
     */
    val wireName: String
) {
    Back("back"),
    Front("front");

    companion object {
        fun fromWireName(name: String): CameraLens? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

enum class CameraSessionStatus {
    Idle,
    Starting,
    Capturing,
    Error
}

data class CameraCaptureState(
    val status: CameraSessionStatus = CameraSessionStatus.Idle,
    val lens: CameraLens = CameraLens.Back,
    val width: Int = 0,
    val height: Int = 0,
    val framesPerSecond: Double = 0.0,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val zoomRatio: Float = 1f,
    val hasFrontCamera: Boolean = false,
    val hasBackCamera: Boolean = false,
    val torchAvailable: Boolean = false,
    val torchEnabled: Boolean = false,
    val errorMessage: String? = null
)

package dev.drosh.domain.session

/**
 * One sample of the device and engine resources, for the resource monitor.
 *
 * CPU is system-wide, read from `/proc/stat` as the delta between two samples.
 * RAM is device-wide, from `ActivityManager`. FPS and frame time describe the
 * terminal renderer; both are null until the engine has drawn a frame. Stock
 * Android exposes no GPU utilisation, so there is no GPU field here on purpose.
 */
data class ResourceSnapshot(
    val cpuPercent: Float = 0f,
    val ramUsedMb: Long = 0L,
    val ramTotalMb: Long = 0L,
    val fps: Float? = null,
    val frameMs: Float? = null,
)

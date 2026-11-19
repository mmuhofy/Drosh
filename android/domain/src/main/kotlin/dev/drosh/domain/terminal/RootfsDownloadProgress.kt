package dev.drosh.domain.terminal

/**
 * One sample of the rootfs download.
 *
 * The setup screen shows a determinate bar and a plain-language MB counter
 * while the large Ubuntu Server archive is fetched. [totalBytes] is -1 when the
 * server did not report a length; in that case consumers show only the bytes
 * received. [bytesPerSecond] is a moving estimate over the last second, not an
 * average over the whole download.
 */
data class RootfsDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
)

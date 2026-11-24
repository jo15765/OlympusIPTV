package com.olympus.iptv.data

data class GuideSlot(
    val title: String? = null,
    val startMillis: Long? = null,
    val endMillis: Long? = null
)

data class SmbSettings(
    val host: String? = null,
    val share: String? = null,
    val folder: String? = null,
    val username: String? = null,
    val password: String? = null
) {
    fun ready(): Boolean = !host.isNullOrBlank() && !share.isNullOrBlank()

    fun folderName(): String = folder?.trim().orEmpty().ifBlank { "Olympus-IPTV" }
}

data class CloudDriveSettings(
    val provider: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val refreshToken: String? = null,
    val folder: String? = null,
    val accountLabel: String? = null,
    val deleteLocalAfterUpload: Boolean? = false
) {
    fun normalizedProvider(): String = provider?.trim()?.lowercase().orEmpty()

    fun ready(): Boolean =
        (normalizedProvider() == Recording.GDRIVE || normalizedProvider() == Recording.ONEDRIVE) &&
            !refreshToken.isNullOrBlank()

    fun folderName(): String = folder?.trim().orEmpty().ifBlank { "Olympus-IPTV" }
}

data class Recording(
    val id: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val title: String? = null,
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val destination: String? = null,
    val status: String? = null,
    val fileName: String? = null,
    val bytes: Long? = null,
    val error: String? = null,
    val copied: Boolean? = null,
    val cloudProvider: String? = null,
    val cloudUrl: String? = null,
    val startPadMillis: Long? = null,
    val endPadMillis: Long? = null
) {
    fun start(): Long = startMillis ?: 0L
    fun end(): Long = endMillis ?: 0L
    fun windowStart(): Long = start() - (startPadMillis ?: PAD)
    fun windowEnd(): Long = end() + (endPadMillis ?: PAD)

    companion object {
        const val LOCAL = "local"
        const val SMB = "smb"
        const val GDRIVE = "gdrive"
        const val ONEDRIVE = "onedrive"
        const val SCHEDULED = "scheduled"
        const val RECORDING = "recording"
        const val DONE = "done"
        const val FAILED = "failed"
        const val MISSED = "missed"
        const val STOPPED = "stopped"
        const val PAD = 60_000L
    }
}

data class SeriesWatch(
    val channelId: String? = null,
    val channelName: String? = null,
    val title: String? = null,
    val destination: String? = null
)

data class RecordingBook(
    val items: List<Recording>? = emptyList(),
    val smb: SmbSettings? = SmbSettings(),
    val cloudDrive: CloudDriveSettings? = CloudDriveSettings(),
    val guide: Map<String, List<GuideSlot>>? = emptyMap(),
    val guideRequests: List<String>? = emptyList(),
    val series: List<SeriesWatch>? = null,
    val revision: Long? = 0
) {
    fun itemsOrEmpty(): List<Recording> = items.orEmpty().filter { !it.id.isNullOrBlank() }
    fun guideOrEmpty(): Map<String, List<GuideSlot>> = guide.orEmpty()
    fun requestsOrEmpty(): List<String> = guideRequests.orEmpty().filter { it.isNotBlank() }
    fun seriesOrEmpty(): List<SeriesWatch> = series.orEmpty().filter {
        !it.channelId.isNullOrBlank() && !it.title.isNullOrBlank()
    }
    fun revisionOrZero(): Long = revision ?: 0L
    fun smbOrEmpty(): SmbSettings = smb ?: SmbSettings()
    fun cloudDriveOrEmpty(): CloudDriveSettings = cloudDrive ?: CloudDriveSettings()
}

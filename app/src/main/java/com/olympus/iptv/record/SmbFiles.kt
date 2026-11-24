package com.olympus.iptv.record

import com.olympus.iptv.data.SmbSettings
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.util.EnumSet

object SmbFiles {
    fun sink(settings: SmbSettings, fileName: String): RecordSink {
        val session = connect(settings)
        val share = session.share
        ensureFolder(share, settings.folderName())
        val remote = share.openFile(
            path(settings, fileName),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null
        )
        return SmbSink(session, remote)
    }

    private class SmbSink(
        private val session: OpenShare,
        private val file: File
    ) : RecordSink {
        private var offset = 0L
        override val bytesWritten: Long get() = offset

        override fun write(bytes: ByteArray, length: Int) {
            file.write(bytes, offset, 0, length)
            offset += length
        }

        override fun close() {
            runCatching { file.close() }
            session.close()
        }
    }

    fun delete(settings: SmbSettings, fileName: String) {
        val session = connect(settings)
        try {
            val remote = path(settings, fileName)
            if (session.share.fileExists(remote)) session.share.rm(remote)
        } finally {
            session.close()
        }
    }

    fun <T> withFile(settings: SmbSettings, fileName: String, read: Boolean, block: (File, Long) -> T): T {
        val session = connect(settings)
        try {
            val remote = path(settings, fileName)
            val size = session.share.getFileInformation(remote).standardInformation.endOfFile
            val access = if (read) AccessMask.GENERIC_READ else AccessMask.GENERIC_WRITE
            val file = session.share.openFile(
                remote,
                EnumSet.of(access),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            try {
                return block(file, size)
            } finally {
                runCatching { file.close() }
            }
        } finally {
            session.close()
        }
    }

    private fun path(settings: SmbSettings, fileName: String): String {
        val folder = settings.folderName().replace('/', '\\').trim('\\')
        return if (folder.isBlank()) fileName else "$folder\\$fileName"
    }

    private fun ensureFolder(share: DiskShare, folder: String) {
        var built = ""
        for (part in folder.split('\\', '/')) {
            if (part.isBlank()) continue
            built = if (built.isEmpty()) part else "$built\\$part"
            if (!share.folderExists(built)) share.mkdir(built)
        }
    }

    private fun connect(settings: SmbSettings): OpenShare {
        val host = settings.host?.trim().orEmpty()
        val shareName = settings.share?.trim().orEmpty()
        val username = settings.username?.trim().orEmpty().ifBlank { "guest" }
        val domain = if (username.contains('\\')) username.substringBefore('\\') else ""
        val user = username.substringAfter('\\')
        val client = SMBClient()
        val connection = client.connect(host)
        val session = connection.authenticate(
            AuthenticationContext(user, settings.password.orEmpty().toCharArray(), domain)
        )
        val share = session.connectShare(shareName) as DiskShare
        return OpenShare(client, connection, session, share)
    }

    private class OpenShare(
        private val client: SMBClient,
        private val connection: com.hierynomus.smbj.connection.Connection,
        private val session: com.hierynomus.smbj.session.Session,
        val share: DiskShare
    ) {
        fun close() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }
}

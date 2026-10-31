package net.machang.backup

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Media3 DataSource backed by the receiver's fs READ ranged requests
 * (design §6.3 playback): the player streams the PC copy over the direct
 * tunnel; nothing is downloaded first. Each instance owns one FsClient
 * connection and buffers one 512 KiB chunk at a time.
 */
class FsDataSource(
    private val fsFactory: () -> FsClient,
    private val path: String,
    private val totalSize: Long
) : BaseDataSource(true) {

    private var client: FsClient? = null
    private var uri: Uri? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var chunk: ByteArray = ByteArray(0)
    private var chunkStart: Long = -1

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        position = dataSpec.position
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalSize > 0 -> totalSize - position
            else -> C.LENGTH_UNSET.toLong()
        }
        client = fsFactory().also { it.connect() }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val fs = client ?: throw IOException("FsDataSource not open")
        val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length.toLong()
        } else {
            minOf(length.toLong(), bytesRemaining)
        }
        if (chunkStart < 0 || position < chunkStart || position >= chunkStart + chunk.size) {
            val toFetch = maxOf(want, 256 * 1024L)
            val fetched = fs.read(path, position, toFetch.toInt())
            if (fetched.isEmpty()) return C.RESULT_END_OF_INPUT
            chunk = fetched
            chunkStart = position
        }
        val from = (position - chunkStart).toInt()
        val n = minOf(want, (chunk.size - from).toLong()).toInt()
        System.arraycopy(chunk, from, buffer, offset, n)
        position += n
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            client?.close()
        } catch (t: Throwable) {
            // best effort
        }
        client = null
        chunk = ByteArray(0)
        chunkStart = -1
    }

    class Factory(
        private val fsFactory: () -> FsClient,
        private val path: String,
        private val totalSize: Long
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = FsDataSource(fsFactory, path, totalSize)
    }
}

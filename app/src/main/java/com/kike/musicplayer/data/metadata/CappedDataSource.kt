package com.kike.musicplayer.data.metadata

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Limits every request to [capBytes] from its start position, but reports the length as unknown
 * so extractors treat the cut as a plain end of stream rather than as a truncated file. A seek
 * (e.g. to a 'moov' box at the end of an MP4) opens a new, again capped, request.
 */
@OptIn(UnstableApi::class)
class CappedDataSource(private val upstream: DataSource, private val capBytes: Long) : DataSource {

    class Factory(private val upstream: DataSource.Factory, private val capBytes: Long) : DataSource.Factory {
        override fun createDataSource(): DataSource = CappedDataSource(upstream.createDataSource(), capBytes)
    }

    override fun open(dataSpec: DataSpec): Long {
        val capped = if (dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length > capBytes) {
            dataSpec.subrange(0, capBytes)
        } else {
            dataSpec
        }
        upstream.open(capped)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun close() = upstream.close()
}

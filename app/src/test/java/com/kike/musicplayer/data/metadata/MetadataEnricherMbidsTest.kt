package com.kike.musicplayer.data.metadata

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(UnstableApi::class)
class MetadataEnricherMbidsTest {

    private val recUuid = "1145a0eb-065b-47c3-87cc-58ed204e1bc2"
    private val relUuid = "95bc4c8a-878a-4a39-bf18-b967c12b6ab2"

    @Test
    fun `extracts MP4 iTunes freeform MusicBrainz tags from InternalFrame`() {
        val md = Metadata(
            InternalFrame("com.apple.iTunes", "MusicBrainz Track Id", recUuid),
            InternalFrame("com.apple.iTunes", "MusicBrainz Album Id", relUuid),
            InternalFrame("com.apple.iTunes", "MusicBrainz Release Track Id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        )
        val mbids = MetadataEnricher.extractEmbeddedMbids(md)
        assertEquals(recUuid, mbids.recordingId)
        assertEquals(relUuid, mbids.releaseId)
    }

    @Test
    fun `extracts FLAC VorbisComment MusicBrainz tags case-insensitively`() {
        val md = Metadata(
            VorbisComment("musicbrainz_trackid", " $recUuid "),
            VorbisComment("MUSICBRAINZ_ALBUMID", relUuid),
        )
        val mbids = MetadataEnricher.extractEmbeddedMbids(md)
        assertEquals(recUuid, mbids.recordingId)
        assertEquals(relUuid, mbids.releaseId)
    }

    @Test
    fun `extracts ID3 UFID BinaryFrame and TXXX album id`() {
        val ufidPayload = "http://musicbrainz.org\u0000$recUuid".toByteArray(Charsets.ISO_8859_1)
        val md = Metadata(
            BinaryFrame("UFID", ufidPayload),
            TextInformationFrame("TXXX", "MusicBrainz Album Id", listOf(relUuid)),
        )
        val mbids = MetadataEnricher.extractEmbeddedMbids(md)
        assertEquals(recUuid, mbids.recordingId)
        assertEquals(relUuid, mbids.releaseId)
    }

    @Test
    fun `extracts ID3 PrivFrame fallback and ignores invalid non-UUID values`() {
        val md = Metadata(
            InternalFrame("com.apple.iTunes", "MusicBrainz Track Id", "not-a-valid-uuid"),
            InternalFrame("com.apple.iTunes", "MusicBrainz Album Id", "302127"),
            PrivFrame("http://musicbrainz.org", recUuid.toByteArray(Charsets.ISO_8859_1)),
        )
        val mbids = MetadataEnricher.extractEmbeddedMbids(md)
        assertEquals(recUuid, mbids.recordingId)
        assertNull(mbids.releaseId)
    }
}

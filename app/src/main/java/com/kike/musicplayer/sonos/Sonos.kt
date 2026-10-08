package com.kike.musicplayer.sonos

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

/** A Sonos group (or single room). Commands go to the group coordinator, identified by [uuid] (RINCON_...). */
data class SonosGroup(val coordinatorIp: String, val name: String, val uuid: String)

/** What the coordinator is playing, from GetPositionInfo + GetTransportInfo. */
data class SonosPosition(
    val transportState: String,
    /** 1-based position in the Sonos queue; 0 when nothing is loaded. */
    val trackNumber: Int,
    val trackUri: String?,
    val relTimeMs: Long,
    val durationMs: Long,
)

/** One queue entry for AddMultipleURIsToQueue. */
data class SonosQueueItem(val uri: String, val didl: String)

/**
 * Sonos control over plain UPnP: SSDP discovery plus SOAP calls to port 1400. All calls block,
 * so run them off the main thread.
 */
object Sonos {
    private const val ZONE_PLAYER = "urn:schemas-upnp-org:device:ZonePlayer:1"
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val AV_TRANSPORT_PATH = "/MediaRenderer/AVTransport/Control"
    private const val ZONE_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
    private const val ZONE_TOPOLOGY_PATH = "/ZoneGroupTopology/Control"
    private const val GROUP_RENDERING = "urn:schemas-upnp-org:service:GroupRenderingControl:1"
    private const val GROUP_RENDERING_PATH = "/MediaRenderer/GroupRenderingControl/Control"

    /** Sonos accepts at most 16 URIs per AddMultipleURIsToQueue call. */
    const val MAX_URIS_PER_ADD = 16

    /** Returns the IPs of Sonos players that answer an SSDP search. */
    fun discoverSpeakerIps(context: Context, timeoutMs: Long = 3000): Set<String> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("musicplayer-ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        val found = linkedSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: $ZONE_PLAYER\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(msg, msg.size, group, 1900)) }

                val buf = ByteArray(2048)
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    if (text.contains(ZONE_PLAYER) || text.contains("Sonos", ignoreCase = true)) {
                        packet.address.hostAddress?.let { found += it }
                    }
                }
            }
        } finally {
            lock.release()
        }
        return found
    }

    /** Current groups as seen by any one speaker. */
    fun groups(anySpeakerIp: String): List<SonosGroup> {
        val response = soap(anySpeakerIp, ZONE_TOPOLOGY_PATH, ZONE_TOPOLOGY, "GetZoneGroupState", "")
        val state = firstText(response, "ZoneGroupState") ?: throw IOException("No ZoneGroupState in reply")
        return parseGroups(state)
    }

    // ---- Queue ----

    fun clearQueue(ip: String) = av(ip, "RemoveAllTracksFromQueue", "<InstanceID>0</InstanceID>")

    /**
     * Adds up to [MAX_URIS_PER_ADD] tracks. [position] is the 1-based queue position of the first
     * one, or 0 to append.
     */
    fun addToQueue(ip: String, items: List<SonosQueueItem>, position: Int = 0) {
        require(items.size in 1..MAX_URIS_PER_ADD)
        // Sonos splits both lists on spaces; the URIs contain none, and it copes with the DIDL.
        av(
            ip, "AddMultipleURIsToQueue",
            "<InstanceID>0</InstanceID><UpdateID>0</UpdateID><NumberOfURIs>${items.size}</NumberOfURIs>" +
                "<EnqueuedURIs>${escape(items.joinToString(" ") { it.uri })}</EnqueuedURIs>" +
                "<EnqueuedURIsMetaData>${escape(items.joinToString(" ") { it.didl })}</EnqueuedURIsMetaData>" +
                "<ContainerURI></ContainerURI><ContainerMetaData></ContainerMetaData>" +
                "<DesiredFirstTrackNumberEnqueued>$position</DesiredFirstTrackNumberEnqueued>" +
                "<EnqueueAsNext>0</EnqueueAsNext>",
        )
    }

    /** Removes [count] tracks starting at 1-based [start]. */
    fun removeFromQueue(ip: String, start: Int, count: Int) = av(
        ip, "RemoveTrackRangeFromQueue",
        "<InstanceID>0</InstanceID><UpdateID>0</UpdateID><StartingIndex>$start</StartingIndex><NumberOfTracks>$count</NumberOfTracks>",
    )

    /** Moves [count] tracks starting at 1-based [start] in front of the track now at 1-based [insertBefore]. */
    fun reorderQueue(ip: String, start: Int, count: Int, insertBefore: Int) = av(
        ip, "ReorderTracksInQueue",
        "<InstanceID>0</InstanceID><StartingIndex>$start</StartingIndex><NumberOfTracks>$count</NumberOfTracks>" +
            "<InsertBefore>$insertBefore</InsertBefore><UpdateID>0</UpdateID>",
    )

    /** Makes the group play from its own queue (rather than e.g. a radio stream). */
    fun useQueue(ip: String, coordinatorUuid: String) = av(
        ip, "SetAVTransportURI",
        "<InstanceID>0</InstanceID><CurrentURI>x-rincon-queue:$coordinatorUuid#0</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>",
    )

    // ---- Transport ----

    fun play(ip: String) = av(ip, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    fun pause(ip: String) = av(ip, "Pause", "<InstanceID>0</InstanceID>")
    fun stop(ip: String) = av(ip, "Stop", "<InstanceID>0</InstanceID>")

    /** Jumps to the 1-based queue position [track]. */
    fun seekTrack(ip: String, track: Int) =
        av(ip, "Seek", "<InstanceID>0</InstanceID><Unit>TRACK_NR</Unit><Target>$track</Target>")

    fun seekTime(ip: String, positionMs: Long) =
        av(ip, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${hms(positionMs)}</Target>")

    /** NORMAL, REPEAT_ALL, REPEAT_ONE, SHUFFLE_NOREPEAT, SHUFFLE, SHUFFLE_REPEAT_ONE. */
    fun setPlayMode(ip: String, mode: String) =
        av(ip, "SetPlayMode", "<InstanceID>0</InstanceID><NewPlayMode>$mode</NewPlayMode>")

    fun playMode(ip: String): String =
        firstText(av(ip, "GetTransportSettings", "<InstanceID>0</InstanceID>"), "PlayMode") ?: "NORMAL"

    fun position(ip: String): SonosPosition {
        val transport = av(ip, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        val info = av(ip, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        return SonosPosition(
            transportState = firstText(transport, "CurrentTransportState") ?: "STOPPED",
            trackNumber = firstText(info, "Track")?.toIntOrNull() ?: 0,
            trackUri = firstText(info, "TrackURI")?.takeIf { it.isNotEmpty() },
            relTimeMs = parseHms(firstText(info, "RelTime")),
            durationMs = parseHms(firstText(info, "TrackDuration")),
        )
    }

    // ---- Volume (whole group) ----

    fun groupVolume(ip: String): Int {
        val reply = soap(ip, GROUP_RENDERING_PATH, GROUP_RENDERING, "GetGroupVolume", "<InstanceID>0</InstanceID>")
        return firstText(reply, "CurrentVolume")?.toIntOrNull() ?: throw IOException("No volume in reply")
    }

    /** Sets the group volume, keeping the members' relative levels. */
    fun setGroupVolume(ip: String, volume: Int) {
        // Sonos scales members relative to the snapshot taken here.
        soap(ip, GROUP_RENDERING_PATH, GROUP_RENDERING, "SnapshotGroupVolume", "<InstanceID>0</InstanceID>")
        soap(
            ip, GROUP_RENDERING_PATH, GROUP_RENDERING, "SetGroupVolume",
            "<InstanceID>0</InstanceID><DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>",
        )
    }

    // ---- Metadata ----

    /** DIDL-Lite for one track, so the Sonos app shows title, artist, album, cover and length. */
    fun didl(title: String, artist: String?, album: String?, artUrl: String?, durationMs: Long, mimeType: String, url: String): String =
        """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
            """xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" """ +
            """xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""" +
            """<item id="-1" parentID="-1" restricted="true">""" +
            "<dc:title>${escape(title)}</dc:title>" +
            (artist?.let { "<dc:creator>${escape(it)}</dc:creator>" } ?: "") +
            (album?.let { "<upnp:album>${escape(it)}</upnp:album>" } ?: "") +
            (artUrl?.let { "<upnp:albumArtURI>${escape(it)}</upnp:albumArtURI>" } ?: "") +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            """<res protocolInfo="http-get:*:$mimeType:*"""" +
            (if (durationMs > 0) """ duration="${hms(durationMs)}.000"""" else "") +
            ">${escape(url)}</res>" +
            "</item></DIDL-Lite>"

    // ---- SOAP plumbing ----

    private fun av(ip: String, action: String, args: String) = soap(ip, AV_TRANSPORT_PATH, AV_TRANSPORT, action, args)

    private fun soap(ip: String, path: String, service: String, action: String, args: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""
        // A plain socket and a minimal HTTP/1.1 exchange: no connection reuse, because Sonos
        // silently drops idle keep-alive connections.
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(ip, 1400), 2000)
            // Some calls only answer after Sonos has fetched the first track; the rest is quick.
            socket.soTimeout = 20_000
            val payload = body.toByteArray()
            val request = "POST $path HTTP/1.1\r\n" +
                "Host: $ip:1400\r\n" +
                "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                "SOAPACTION: \"$service#$action\"\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1) + payload)
                flush()
            }
            val raw = socket.getInputStream().readBytes()
            if (raw.isEmpty()) throw IOException("$action on $ip: empty reply")
            val text = String(raw, Charsets.UTF_8)
            val code = text.substringAfter(' ').take(3).toIntOrNull() ?: 0
            val head = text.substringBefore("\r\n\r\n")
            var bodyText = text.substringAfter("\r\n\r\n", "")
            if (head.contains("transfer-encoding: chunked", ignoreCase = true)) bodyText = dechunk(bodyText)
            if (code !in 200..299) {
                val upnpError = Regex("<errorCode>(\\d+)</errorCode>").find(bodyText)?.groupValues?.get(1)
                throw IOException("$action on $ip failed: HTTP $code" + (upnpError?.let { ", UPnP error $it" } ?: ""))
            }
            return bodyText
        }
    }

    /** Decodes an HTTP chunked body (Sonos usually sends Content-Length, but be safe). */
    private fun dechunk(body: String): String {
        val out = StringBuilder()
        var rest = body
        while (true) {
            val size = rest.substringBefore("\r\n").trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) break
            rest = rest.substringAfter("\r\n")
            out.append(rest.take(size))
            rest = rest.drop(size).removePrefix("\r\n")
        }
        return out.toString()
    }

    private fun firstText(xml: String, tag: String): String? {
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == tag) return parser.nextText()
        }
        return null
    }

    private data class Member(val uuid: String, val ip: String, val name: String)

    private fun parseGroups(xml: String): List<SonosGroup> {
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        val groups = mutableListOf<SonosGroup>()
        var coordinator: String? = null
        val members = mutableListOf<Member>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "ZoneGroup" -> {
                        coordinator = parser.getAttributeValue(null, "Coordinator")
                        members.clear()
                    }
                    "ZoneGroupMember" -> {
                        // Invisible members are e.g. the second speaker of a stereo pair.
                        val invisible = parser.getAttributeValue(null, "Invisible") == "1"
                        val uuid = parser.getAttributeValue(null, "UUID")
                        val ip = parser.getAttributeValue(null, "Location")?.let { URL(it).host }
                        val name = parser.getAttributeValue(null, "ZoneName")
                        if (!invisible && uuid != null && ip != null && name != null) members += Member(uuid, ip, name)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "ZoneGroup") {
                    val lead = members.firstOrNull { it.uuid == coordinator }
                    if (lead != null) {
                        val names = listOf(lead.name) + members.filter { it !== lead }.map { it.name }.sorted()
                        groups += SonosGroup(lead.ip, names.distinct().joinToString(" + "), lead.uuid)
                    }
                }
            }
        }
        return groups.sortedBy { it.name }
    }

    /** "H:MM:SS" */
    fun hms(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun parseHms(text: String?): Long {
        val parts = text?.substringBefore('.')?.split(':')?.mapNotNull { it.toLongOrNull() } ?: return 0
        if (parts.size != 3) return 0
        return ((parts[0] * 3600) + (parts[1] * 60) + parts[2]) * 1000
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

package com.kike.musicplayer.data.catalog

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.kike.musicplayer.data.db.mosaicCovers
import org.junit.Assert.fail
import org.junit.Test

class CatalogClientPlaylistTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private fun mockCatalogClient(handler: (okhttp3.Request) -> Response): CatalogClient {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()
        return CatalogClient(okHttpClient, moshi)
    }

    private fun textResponse(req: okhttp3.Request, body: String, code: Int = 200, contentType: String = "text/html; charset=utf-8"): Response {
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()
    }

    private fun jsonResponse(req: okhttp3.Request, body: String, code: Int = 200): Response {
        return textResponse(req, body, code, "application/json; charset=utf-8")
    }

    @Test
    fun resolveDeezerPlaylistSucceeds() = runBlocking {
        val deezerJson = """
            {
              "id": 4590807924,
              "title": "Metal Classics",
              "nb_tracks": 2,
              "picture_xl": "https://e-cdns-images.dzcdn.net/images/playlist/123/1000x1000-000000-80-0-0.jpg",
              "creator": {
                "id": 1,
                "name": "Deezer Metal Editor"
              },
              "tracks": {
                "data": [
                  {
                    "id": 101,
                    "title": "Master of Puppets",
                    "duration": 515,
                    "track_position": 1,
                    "artist": { "id": 10, "name": "Metallica" },
                    "album": {
                      "id": 20,
                      "title": "Master of Puppets (Remastered)",
                      "cover_xl": "https://e-cdns-images.dzcdn.net/images/cover/metallica/1000x1000.jpg"
                    }
                  },
                  {
                    "id": 102,
                    "title": "Holy Wars... The Punishment Due",
                    "duration": 392,
                    "track_position": 2,
                    "artist": { "id": 11, "name": "Megadeth" },
                    "album": {
                      "id": 21,
                      "title": "Rust in Peace",
                      "cover_xl": "https://e-cdns-images.dzcdn.net/images/cover/megadeth/1000x1000.jpg"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val client = mockCatalogClient { req ->
            val url = req.url.toString()
            if (url.startsWith("https://api.deezer.com/playlist/4590807924")) {
                jsonResponse(req, deezerJson)
            } else {
                textResponse(req, "", code = 200)
            }
        }

        val resolved = client.resolvePlaylistUrl("https://www.deezer.com/en/playlist/4590807924")
        val playlist = resolved.playlist
        assertEquals("dz:4590807924", playlist.id)
        assertEquals("Metal Classics", playlist.title)
        assertEquals("Deezer Metal Editor", playlist.creator)
        assertEquals(2, playlist.trackCount)
        assertEquals(CatalogPlaylist.SOURCE_DEEZER, playlist.source)
        assertEquals(2, resolved.tracks.size)

        val t1 = resolved.tracks[0]
        assertEquals("dz:101", t1.id)
        assertEquals("Master of Puppets", t1.title)
        assertEquals("Metallica", t1.artist)
        assertEquals("Master of Puppets (Remastered)", t1.album)
        assertEquals(515_000L, t1.durationMs)
        assertEquals("dz:20", t1.albumId)
    }

    @Test
    fun resolveApplePlaylistSucceeds() = runBlocking {
        val appleHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta property="og:title" content="80s Hits Essentials">
              <meta property="og:image" content="https://is1-ssl.mzstatic.com/image/thumb/Features115/v4/30/80s.jpg/1200x630bf-60.jpg">
              <meta name="apple:creator" content="Apple Music 80s">
            </head>
            <body>
              <script type="application/json">
              {
                "data": [
                  {
                    "title": "Billie Jean",
                    "artistName": "Michael Jackson",
                    "duration": 294000,
                    "contentDescriptor": {
                      "identifiers": {
                        "storeAdamID": "269572838"
                      }
                    },
                    "tertiaryLinks": [
                      {
                        "title": "Thriller",
                        "segue": {
                          "destination": {
                            "contentDescriptor": {
                              "identifiers": {
                                "storeAdamID": "269572830"
                              }
                            }
                          }
                        }
                      }
                    ],
                    "artwork": {
                      "dictionary": {
                        "url": "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/thriller/{w}x{h}bb.{f}"
                      }
                    }
                  },
                  {
                    "title": "Sweet Child O' Mine",
                    "artistName": "Guns N' Roses",
                    "duration": 356000,
                    "contentDescriptor": {
                      "identifiers": {
                        "storeAdamID": "1377813298"
                      }
                    },
                    "tertiaryLinks": [
                      {
                        "title": "Appetite for Destruction",
                        "segue": {
                          "destination": {
                            "contentDescriptor": {
                              "identifiers": {
                                "storeAdamID": "1377813284"
                              }
                            }
                          }
                        }
                      }
                    ],
                    "artwork": {
                      "dictionary": {
                        "url": "https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/appetite/{w}x{h}bb.{f}"
                      }
                    }
                  }
                ]
              }
              </script>
            </body>
            </html>
        """.trimIndent()

        val client = mockCatalogClient { req ->
            val url = req.url.toString()
            if (url.contains("pl.af4d982795c6472ea48579eb147cd726")) {
                textResponse(req, appleHtml)
            } else {
                textResponse(req, "", code = 200)
            }
        }

        val resolved = client.resolvePlaylistUrl("https://music.apple.com/us/playlist/80s-hits-essentials/pl.af4d982795c6472ea48579eb147cd726")
        val playlist = resolved.playlist
        assertEquals("ap:pl.af4d982795c6472ea48579eb147cd726", playlist.id)
        assertEquals("80s Hits Essentials", playlist.title)
        assertEquals("Apple Music 80s", playlist.creator)
        assertEquals(2, playlist.trackCount)
        assertEquals(CatalogPlaylist.SOURCE_APPLE, playlist.source)
        assertEquals(2, resolved.tracks.size)

        val t1 = resolved.tracks[0]
        assertEquals("ap:269572838", t1.id)
        assertEquals("Billie Jean", t1.title)
        assertEquals("Michael Jackson", t1.artist)
        assertEquals("Thriller", t1.album)
        assertEquals(294_000L, t1.durationMs)
        assertEquals("ap:269572830", t1.albumId)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/thriller/1000x1000bb.jpg", t1.coverUrl)
    }

    @Test
    fun resolveUnsupportedUrlThrowsIllegalArgumentException() = runBlocking {
        val client = mockCatalogClient { req -> textResponse(req, "") }
        try {
            client.resolvePlaylistUrl("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
            fail("Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unsupported playlist URL") == true)
        }
    }

    @Test
    fun liveResolutionForAppleAndDeezerPlaylists() = runBlocking {
        val liveClient = CatalogClient(OkHttpClient(), moshi)

        // 1. Deezer
        val deezerResult = liveClient.resolvePlaylistUrl("https://www.deezer.com/en/playlist/4590807924")
        assertEquals("dz:4590807924", deezerResult.playlist.id)
        assertTrue(deezerResult.playlist.title.isNotBlank())
        assertTrue(deezerResult.tracks.isNotEmpty())
        println("Resolved live Deezer: ${deezerResult.playlist.title} with ${deezerResult.tracks.size} tracks")

        // 2. Apple Music
        val appleResult = liveClient.resolvePlaylistUrl("https://music.apple.com/us/playlist/80s-hits-essentials/pl.af4d982795c6472ea48579eb147cd726")
        assertEquals("ap:pl.af4d982795c6472ea48579eb147cd726", appleResult.playlist.id)
        assertTrue(appleResult.playlist.title.contains("80s Hits Essentials"))
        assertTrue(appleResult.tracks.isNotEmpty())
        println("Resolved live Apple Music: ${appleResult.playlist.title} with ${appleResult.tracks.size} tracks")
    }


    @Test
    fun normalizeArtworkUrlHandlesVariousFormats() {
        // 1. Social card 1200x630 -> 600x600 square
        val social = "https://is1-ssl.mzstatic.com/image/thumb/Features116/v4/17/10/9c/17109c78-a9f2-05bf-662d-3cd126e47458/82ff49a3-d0ce-497d-bd29-1be6b257f000.png/1200x630SC.DN02-60.jpg?l=en-US"
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Features116/v4/17/10/9c/17109c78-a9f2-05bf-662d-3cd126e47458/82ff49a3-d0ce-497d-bd29-1be6b257f000.png/600x600SC.DN02-60.jpg?l=en-US",
            normalizeArtworkUrl(social)
        )

        // 2. Social card with wp (wallpaper border) -> 600x600cc.jpg (full bleed cover crop)
        val wallpaper = "https://is1-ssl.mzstatic.com/image/thumb/Podcasts125/v4/bf/12/34/mza_123.jpg/1200x630wp-60.jpg"
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Podcasts125/v4/bf/12/34/mza_123.jpg/600x600cc.jpg",
            normalizeArtworkUrl(wallpaper)
        )

        // 3. Template {w}x{h} -> 600x600 and {f} -> jpg
        val template = "https://is1-ssl.mzstatic.com/image/thumb/Features116/v4/art/{w}x{h}SC.DN02.{f}?l=en-US"
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Features116/v4/art/600x600SC.DN02.jpg?l=en-US",
            normalizeArtworkUrl(template)
        )

        // 4. Already square (1000x1000) preserved
        val square = "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/thriller/1000x1000bb.jpg"
        assertEquals(square, normalizeArtworkUrl(square))

        // 5. Non-mzstatic URL preserved
        val dz = "https://e-cdns-images.dzcdn.net/images/cover/500x500.jpg"
        assertEquals(dz, normalizeArtworkUrl(dz))

        // 6. Unescapes &amp; to & in query parameters
        val dynamicGen = "https://is1-ssl.mzstatic.com/image/thumb/gen/1200x630AM.PDCXS16-60.jpg?c1=F2A39D&amp;c2=FFDC40&amp;signature=db8"
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/gen/600x600AM.PDCXS16-60.jpg?c1=F2A39D&c2=FFDC40&signature=db8",
            normalizeArtworkUrl(dynamicGen)
        )
    }

    @Test
    fun defaultCatalogCoverDetection() {
        assertTrue(isDefaultCatalogCover("https://music.apple.com/assets/meta/apple-music-60.png"))
        assertTrue(isDefaultCatalogCover("https://music.apple.com/assets/meta/apple-music-1200.png"))
        assertTrue(isDefaultCatalogCover("https://api.deezer.com/playlist//image"))
        assertTrue(isDefaultCatalogCover(null))
        assertTrue(isDefaultCatalogCover(""))
        org.junit.Assert.assertFalse(isDefaultCatalogCover("https://is1-ssl.mzstatic.com/art1/600x600bb.jpg"))
    }

    @Test
    fun savedCatalogPlaylistMosaicCoversExtractsDistinctCovers() {
        val tracksJson = """
            [
              {"id":"ap:1","title":"Song 1","coverUrl":"https://is1-ssl.mzstatic.com/art1/{w}x{h}bb.{f}"},
              {"id":"ap:2","title":"Song 2","coverUrl":"https://is1-ssl.mzstatic.com/art1/{w}x{h}bb.{f}"},
              {"id":"ap:3","title":"Song 3","coverUrl":"https://is1-ssl.mzstatic.com/art2/{w}x{h}bb.{f}"},
              {"id":"ap:4","title":"Song 4","coverUrl":"https://is1-ssl.mzstatic.com/art3/{w}x{h}bb.{f}"},
              {"id":"ap:5","title":"Song 5","coverUrl":"https://is1-ssl.mzstatic.com/art4/{w}x{h}bb.{f}"},
              {"id":"ap:6","title":"Song 6","coverUrl":"https://is1-ssl.mzstatic.com/art5/{w}x{h}bb.{f}"}
            ]
        """.trimIndent()

        val plWithoutCover = com.kike.musicplayer.data.db.SavedCatalogPlaylist(
            id = "ap:onthego",
            title = "On-The-Go 1",
            creator = "User",
            trackCount = 6,
            coverUrl = null,
            cachedTracksJson = tracksJson,
        )

        val covers = plWithoutCover.mosaicCovers()
        assertEquals(4, covers.size)
        assertEquals("https://is1-ssl.mzstatic.com/art1/600x600bb.jpg", covers[0])
        assertEquals("https://is1-ssl.mzstatic.com/art2/600x600bb.jpg", covers[1])
        assertEquals("https://is1-ssl.mzstatic.com/art3/600x600bb.jpg", covers[2])
        assertEquals("https://is1-ssl.mzstatic.com/art4/600x600bb.jpg", covers[3])

        // When playlist has an explicit cover, mosaicCovers returns emptyList
        val plWithCover = plWithoutCover.copy(coverUrl = "https://is1-ssl.mzstatic.com/art_main/600x600.jpg")
        assertTrue(plWithCover.mosaicCovers().isEmpty())

        // When playlist has Apple default placeholder cover, mosaicCovers extracts covers
        val plWithDefaultCover = plWithoutCover.copy(coverUrl = "https://music.apple.com/assets/meta/apple-music-60.png")
        assertEquals(4, plWithDefaultCover.mosaicCovers().size)
    }
}
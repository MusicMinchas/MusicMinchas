# Nebula Add-on Source Integration Plan (`com.kike.musicplayer`)

## Overview

This specification details how to integrate **Nebula (Eclipse Music / Torbox)** into MusicMinchas as an on-demand streaming and search source.
Users configure their own Nebula add-on manifest URL in the app settings (e.g. `https://nebula.eclipsemusic.app/gAAAAAB.../manifest.json`), unlocking lossless FLAC streaming from Torbox's cache and Tidal catalog search without syncing or altering the local Room database.

---

## 1. Nebula Architecture & Protocol

Nebula implements the **Eclipse Music Add-on Protocol** (a music-specialized sibling of Stremio):
- **Manifest URL:** `https://nebula.eclipsemusic.app/{encrypted_config}/manifest.json`
- **Base URL:** The URL prefix without `/manifest.json`.

### Endpoints Used

| Action | Endpoint | Description |
| :--- | :--- | :--- |
| **Verify Manifest** | `GET /manifest.json` | Validates `resources: ["search", "stream"]`, `contentType: "music"`, add-on name, and icon. |
| **Catalog Search** | `GET /search?q={query}` | Returns Tidal metadata: `tracks`, `albums`, `artists`, `playlists` with 1280x1280 artwork URLs. |
| **Album Tracklist** | `GET /album/{albumId}` | Returns album title, artist, year, artwork, and full tracklist with track IDs (`tidal:track:...`). |
| **Artist Profile** | `GET /artist/{artistId}`| Returns artist name, photo, top 20 popular tracks, and full album discography. |
| **Stream Resolution**| `GET /stream/{trackId}` | Resolves track ID to a direct Torbox CDN link: `{"url": "https://nexus-....tb-cdn.st/...", "format": "flac", "quality": "lossless"}`. |

---

## 2. Implementation Steps

### Phase 1: Storage & Models (`data/nebula/`)

1. **`NebulaSettings.kt`** (Stored in `SharedPreferences` or EncryptedSharedPreferences):
   - `manifestUrl: String?` — Full manifest URL entered by the user.
   - `baseUrl: String?` — Base path for requests (`https://nebula.eclipsemusic.app/gAAAAAB...`).
   - `addonName: String?` — Display name (e.g. `"Nebula (Tidal - Torrents)"`).
   - `quality: String` — Default `"auto"` / `"lossless"`.
   - `isConnected: Boolean` — `true` when a valid manifest has been tested.

2. **`NebulaModels.kt`** (Moshi models):
   - `NebulaManifest(id, name, version, resources, types, contentType)`
   - `NebulaSearchResponse(tracks, albums, artists, playlists)`
   - `NebulaTrack(id, title, artist, album, duration, artworkURL, isrc)`
   - `NebulaAlbum(id, title, artist, artworkURL, trackCount, year, tracks)`
   - `NebulaArtist(id, name, artworkURL, topTracks, albums)`
   - `NebulaStream(url, format, quality)`

3. **`NebulaClient.kt`**:
   - `connect(manifestUrl: String): Result<NebulaManifest>`
   - `search(query: String): CatalogSearchResults` (maps to existing `CatalogClient` models)
   - `getAlbum(albumId: String): List<CatalogTrack>`
   - `getArtist(artistId: String): Pair<List<CatalogTrack>, List<CatalogAlbum>>`
   - `resolveStreamUrl(trackId: String): String?`

---

### Phase 2: Media3 Playback & Sonos Casting

1. **URI Convention:**
   - Nebula tracks use the custom scheme: `nebula:track:<id>` (e.g. `nebula:track:tidal:track:17825551`).

2. **`PlaybackService.kt` (`ResolvingDataSource.Factory`):**
   - Intercept `nebula:` URIs:
     ```kotlin
     spec.uri.scheme == "nebula" -> {
         val trackId = spec.uri.schemeSpecificPart
         val streamUrl = runBlocking { container.nebulaClient.resolveStreamUrl(trackId) }
         streamUrl?.let { spec.withUri(Uri.parse(it)) } ?: spec
     }
     ```

3. **`TrackServer.kt` (Sonos HTTP Relay on port 8765):**
   - In `serveTrack()`, handle `uri.startsWith("nebula:")`:
     - Resolve the fresh CDN streaming link via `nebulaClient.resolveStreamUrl(trackId)`.
     - Relay the audio stream to Sonos with the existing 512 KB block cache and range prefetch.

---

### Phase 3: Settings UI (`SourcesScreen.kt`)

Add a dedicated **"Nebula (Torbox Add-on)"** card in `MusicSourcesScreen`:
- **Disconnected State:**
  - "Connect Add-on" button opening a text input dialog for the user's manifest URL.
  - Test connection against `/manifest.json`, showing an error if unreachable or invalid.
- **Connected State:**
  - Displays add-on logo/icon, provider name (`Nebula (Tidal - Torrents)`), and status badge (`Connected`).
  - Audio quality display (`Lossless FLAC`).
  - "Disconnect" button with confirmation.

---

### Phase 4: Search & Navigation

1. **Search Integration:**
   - In `LibraryViewModel` / `SearchText.kt`, query `nebulaClient.search(query)` in parallel with local/Drive/Subsonic searches.
   - Results display under a "Nebula" chip or alongside other catalog results.
2. **Detail Screens:**
   - Tapping an album opens `AlbumScreen` with tracks fetched via `nebulaClient.getAlbum(id)`.
   - Tapping an artist opens `ArtistScreen` with discography & top tracks.

---

### Phase 5: Downloads & Favourites

1. **Favourites:**
   - Add "Add to Favourites" in track menus for Nebula songs (stored in `favourites` table).
2. **Downloads:**
   - Tapping the download button on a Nebula track/album delegates to `Downloader.kt`.
   - `Downloader` fetches the direct Torbox FLAC stream and writes it to `Music/MusicMinchas/{Artist}/{Album}/{Track}.flac` as a local file.

---

## 3. Key Design Principles Maintained

- **No Room Database Migrations Required:** Because Step 6 (library sync) is omitted, tracks are resolved on-demand without altering Room database tables or indexes.
- **"Own Copy Wins":** Local SAF files, Google Drive files, and Subsonic server tracks take precedence over external Nebula matches in search and recommender flows.
- **Case-Sensitive Artists:** Display and grouping remain strictly case-sensitive.
- **Sonos Compatibility:** Torbox CDN links support `accept-ranges: bytes`, ensuring smooth seeking and relay via `TrackServer`.

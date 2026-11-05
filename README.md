# MusicMinchas

A native Android music player built with Kotlin, Jetpack Compose, Media3/ExoPlayer, and Room. Designed for power users and audiophiles who want unified multi-source streaming, bit-perfect playback, local and cast listening, and rich metadata.

---

## Features

### Multi-Source Library & Streaming
- **Local Storage**: Storage Access Framework (SAF) folder selection with persistent URI permissions (no broad storage permissions required).
- **Google Drive**: In-app folder browser with streaming via HTTP Range requests (OAuth 2.0 PKCE with `drive.readonly` scope).
- **Navidrome / Subsonic**: Connect to any Subsonic-compatible server (`/rest` API) with token/salt authentication, library browsing, search, and streaming.
- **Lossless Streaming Add-ons**: Stremio / Eclipse streaming add-on support via Torbox debrid integration.
- **Unified Library**: Merged multi-source artist and album views with customizable source priority preferences (Local, Drive, Server).

### Audio Engine & Bit-Perfect Playback
- **Media3 & Background Service**: ExoPlayer integrated into a foreground `MediaLibraryService` with Android Auto support, lock screen controls, and persistent queue restoration.
- **Custom ALAC Decoder**: Built-in bit-exact Kotlin port of Apple's reference ALAC decoder (`playback/alac`) bypassing Qualcomm hardware decoder bugs; supports 16-bit and 24-bit/96 kHz audio with float output.
- **Audio Processing**: Bit-perfect output with optional Loudness Normalization, Crossfade, and Equalizer.

### Play on Sonos
- **Direct Casting**: Discover Sonos speakers and groups via SSDP and mirror the app queue to Sonos.
- **Local HTTP Relay**: Built-in high-throughput streaming relay (`sonos/TrackServer`) on port 8765 with block caching and prefetching.
- **Hi-Res Downsampling**: On-the-fly 24-bit/48 kHz downsampling for hi-res files (>48 kHz) to ensure seamless playback on Sonos S2 hardware.
- **Native App Shuffle**: App-managed playback order prevents Sonos queue reshuffling quirks.

### Public Catalog & Music Discovery
- **Deezer Public Catalog**: Explore Trending Charts, Genre playlists, and "100% Artist Essentials".
- **Intelligent Caching**: 7-day persistent disk cache for catalog playlists with background artwork prefetching.
- **Catalog Matching**: Instant cross-referencing between catalog tracks and your personal library for quick playback.

### On-Device Recommendations & Replay
- **100% On-Device Engine**: Recommender powered by MusicBrainz metadata tags, listen counts, skip history, and favourites (`recommend/`).
- **Daily Mixes**: Daily seeded mixes including *Because you listened to...*, Top Genres, *Forgotten Favourites*, *Rediscover*, and *Fresh Picks*.
- **Replay Statistics**: Detailed monthly and annual listening statistics, top tracks, artists, and albums.

### Lyrics & Rich Metadata
- **LRCLIB Lyrics**: Synced and plain-text lyrics with tap-to-seek and interactive 3D album cover flip.
- **Enrichment Pipeline**: Reads embedded ID3/Vorbis/MP4 tags, with online fallbacks to MusicBrainz, Cover Art Archive, Deezer, and Wikidata artist images.
- **Case-Sensitive Artist Grouping**: Preserves tag casing integrity across your library.

### Scrobbling & Imports
- **Last.fm Scrobbling**: Real-time scrobbling with persistent offline outbox and automatic retry.
- **Favourites & Loved Tracks**: Two-way synchronization between Loved tracks and in-app Favourites.
- **Scrobble History Import**: Full Last.fm scrobble history importer with fuzzy track matching.

### Offline Downloads & File Upgrades
- **Direct Downloads**: Download cloud/server tracks to local storage (`Music/MusicMinchas/`).
- **Replacement Tracking**: Checksum-based replacement system that seamlessly transfers play history, scrobbles, and playlist positions when upgrading audio files to higher-quality formats.

### Modular Settings & Storage Control
- **Organized Settings**: Dedicated submenus for *Music Sources & Library Health*, *Integrations*, *Data & Storage*, *Audio Settings*, and *General Preferences*.
- **Cache Management**: Configurable Coil 3 disk image cache (250 MB – 2 GB) with cache size monitoring and instant one-tap cleanup.

---

## Tech Stack

- **UI**: Jetpack Compose, Material 3
- **Audio**: Media3 / ExoPlayer 1.11, custom ALAC decoder
- **Database**: Room 2.8 with KSP and Paging 3
- **Network**: Retrofit 3, OkHttp, Moshi
- **Image Loading**: Coil 3 (custom disk cache)
- **Toolchain**: AGP 9.4, Kotlin 2.4, Gradle 9.8, compileSdk 37, targetSdk 36, minSdk 30

---

## Build

To build the debug APK:

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
```

---

## Google Drive Setup (One-Time)

The app does not require embedded client secrets. Google matches the app by its package name and signing certificate:

1. In [Google Cloud Console](https://console.cloud.google.com/), create or open a project.
2. Under **APIs & Services → Library**, enable the **Google Drive API**.
3. Under **Google Auth Platform → Branding / Audience**, configure your app and OAuth consent screen.
4. Under **Data Access**, add the scope `https://www.googleapis.com/auth/drive.readonly`.
5. Under **Clients → Create Client → Android**:
   - Package name: `com.kike.musicplayer`
   - SHA-1 certificate fingerprint: Obtain your signing certificate's SHA-1 fingerprint by running:
     ```bash
     ./gradlew signingReport
     ```
     or using `keytool`:
     ```bash
     keytool -list -v -keystore <path-to-keystore>
     ```
   - If signing release builds with a separate key, add a second Android client with the release certificate's SHA-1.

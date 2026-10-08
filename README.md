# MusicMinchas

A native Android music player: Kotlin, Jetpack Compose, Media3/ExoPlayer, Room, Retrofit, and Coil.

## Features

- **Playback** with ExoPlayer inside a `MediaLibraryService`: background playback, notification, lock screen, hardware/Bluetooth buttons, Android Auto browse tree (Songs / Albums plus search), and resume after the app was killed.
- **Formats**: MP3, AAC, FLAC, ALAC, and WAV, using ExoPlayer's built-in extractors (no FFmpeg). MP3, AAC, FLAC, and WAV use the platform decoders. ALAC uses a built-in Kotlin port of Apple's reference decoder (`playback/alac`), because Qualcomm's `c2.qti.alac.*` decoders reject valid files. It is verified bit-exact against FFmpeg for 16-bit stereo, 16-bit mono, and 24-bit/96 kHz stereo. Float output is enabled so 24-bit sources keep their resolution.
- **Queue**: play, pause, skip, seek, shuffle, and repeat (off / all / one). The Up Next list follows the shuffle order. Drag-to-reorder works when shuffle is off. You can remove items, and add songs with *Play next* or *Add to queue*. The queue and position are saved in Room and restored.
- **Sources**
  Only folders the user picks are imported, so ringtones, app sounds and other stray audio stay out.
  - *Device folders*: picked through the Storage Access Framework, with a persisted grant, so no storage permission is needed. Sub-folders are included.
  - *Google Drive folders*: picked in an in-app browser (My Drive and Shared with me). The app lists all folders and audio files once and filters by ancestry, so it doesn't make one request per folder. Drive access uses OAuth 2.0 through Google Identity Services `AuthorizationClient` with the `drive.readonly` scope. Files are streamed with HTTP Range requests through OkHttp, so playback starts before the file is downloaded.
- **Play on Sonos**: the cast button in Now Playing lists Sonos rooms and groups (SSDP discovery). The app's queue is copied into the group's Sonos queue, and Sonos fetches each track from a small HTTP server in the app (`sonos/TrackServer`). Drive files are relayed with the OAuth token, which never leaves the phone; folder files are served directly; Range requests are supported, so Sonos seeks and reads ahead (about 4× real time). Sonos plays the audio itself. While casting, the media session uses `SonosPlayer` (a Media3 `SimpleBasePlayer`), so the app UI, notification, lock screen and volume keys control the speaker. Picking "This phone" hands the queue back at the same position.
- **Playlists**: two automatic playlists built from the play history (a play is recorded when a track starts, on the phone or on Sonos): *Recently Played* (last 30 different songs) and *Recently Played Albums* (last 10 different albums). Tiles show a strip of covers fading from 30% to 80% transparency.
- **Drive sync**: automatic sync of the chosen Drive folders is *No automatic sync*, *Every 24 h* (default) or *At app start*; *Sync* runs it any time. Device folders are rescanned at each start (local and quick).
- **Metadata pipeline**, cached in Room:
  1. Local: tags and embedded artwork are read from the file, 12 files at a time, and written to Room in batches. Device files use `MediaMetadataRetriever`. Drive files use ExoPlayer's extractors (`media3-inspector` `MetadataRetriever`) with each request capped at 1 MB, because tags sit in the header. Artwork is stored once per album in `files/artwork`.
  2. Online fallback for songs still missing artist, album, or artwork: MusicBrainz release search (once per album) or recording search gives the MBIDs, then the Cover Art Archive gives the front cover. Requests are limited to 1 per second as MusicBrainz requires. You can turn this off in *Sources & metadata*.

## Build

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
```

Toolchain: AGP 9.4.1, Kotlin 2.4.20, KSP 2.3.12, Gradle 9.8; compileSdk 37, targetSdk 36, minSdk 30.

## Google Drive setup (one time)

The app holds no client ID. Google matches the app by its package name and signing certificate:

1. In [Google Cloud Console](https://console.cloud.google.com/), create a project (or reuse one).
2. *APIs & Services → Library*: enable **Google Drive API**.
3. *Google Auth Platform → Branding / Audience*: set the app to **External**, keep it in **Testing**, and add your Google account as a **test user**.
4. *Data Access*: add the scope `https://www.googleapis.com/auth/drive.readonly`.
5. *Clients → Create client → Android*:
   - Package name: `com.kike.musicplayer`
   - SHA-1: the certificate that signs the APK. For this PC's debug key that is
     `95:AD:8F:35:81:53:F9:F8:25:03:D9:4C:46:92:CE:60:F0:7F:37:82`.
     If you sign a release build with another key, add a second Android client with that key's SHA-1.

Because the app stays in Testing, Google shows an "unverified app" warning at consent. That is expected for a personal app.

## Layout

```
data/db          Room: tracks, saved queue, session state
data/local       MediaStore + SAF scanning
data/drive       OAuth, Drive REST API, auth interceptor (also used by ExoPlayer)
data/metadata    MusicBrainz / Cover Art Archive APIs, MetadataEnricher
data/LibraryRepository   merges scans into Room, triggers enrichment
playback         PlaybackService (ExoPlayer + MediaLibrarySession), MediaItem mapping
ui               Compose screens, PlayerViewModel (MediaController), LibraryViewModel
```

## Known limits

- The first tag scan of a large Drive library reads each file's header over the network, about 140 files per minute. Results are cached, so later scans only read new files.
- Casting needs the phone on the same Wi-Fi as Sonos, with the app running (a foreground service with wake and Wi-Fi locks keeps it serving). Sonos S2 plays FLAC/ALAC up to 24-bit/48 kHz; higher sample rates won't play there. Up next ignores Sonos' own shuffle order.
- Android Auto cannot load artwork from `file://` (extracted embedded art). Online covers (https) do show.
- Drive has no tag index, so the first metadata pass for Drive songs reads each file's header over the network.

# Music Player

A native Android music player: Kotlin, Jetpack Compose, Media3/ExoPlayer, Room, Retrofit, and Coil.

## Features

- **Playback** with ExoPlayer inside a `MediaLibraryService`: background playback, notification, lock screen, hardware/Bluetooth buttons, Android Auto browse tree (Songs / Albums plus search), and resume after the app was killed.
- **Formats**: MP3, AAC, FLAC, ALAC, and WAV, using ExoPlayer's built-in extractors and the platform decoders (no FFmpeg). Float output is enabled so 24-bit sources keep their resolution.
- **Queue**: play, pause, skip, seek, shuffle, and repeat (off / all / one). The Up Next list follows the shuffle order. Drag-to-reorder works when shuffle is off. You can remove items, and add songs with *Play next* or *Add to queue*. The queue and position are saved in Room and restored.
- **Sources**
  - *This device*: the MediaStore index (`READ_MEDIA_AUDIO`).
  - *Folders*: any folder picked through the Storage Access Framework, with a persisted grant.
  - *Google Drive*: OAuth 2.0 through Google Identity Services `AuthorizationClient` with the `drive.readonly` scope. Files are streamed with HTTP Range requests through OkHttp, so playback starts before the file is downloaded.
- **Metadata pipeline**, cached in Room:
  1. Local: `MediaMetadataRetriever` reads ID3, Vorbis, and MP4 tags plus embedded artwork. Artwork is stored once per album in `files/artwork`.
  2. Online fallback for songs still missing artist, album, or artwork: a MusicBrainz recording search gives the MBIDs, then the Cover Art Archive gives the front cover. Requests are limited to 1 per second as MusicBrainz requires. You can turn this off in *Sources & metadata*.

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

- ALAC needs the device's platform decoder. Current Android versions have one.
- Android Auto cannot load artwork from `file://` (extracted embedded art). Online covers (https) do show.
- Drive has no tag index, so the first metadata pass for Drive songs reads each file's header over the network.

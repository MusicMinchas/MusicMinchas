package com.kike.musicplayer

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.kike.musicplayer.data.LibraryIndexer
import com.kike.musicplayer.data.LibraryRepository
import com.kike.musicplayer.data.Settings
import com.kike.musicplayer.data.db.AppDatabase
import com.kike.musicplayer.data.discover.DiscoverRepository
import com.kike.musicplayer.data.download.Downloader
import com.kike.musicplayer.data.drive.DriveApi
import com.kike.musicplayer.data.lastfm.LastFm
import com.kike.musicplayer.data.lastfm.LastFmClient
import com.kike.musicplayer.data.lastfm.ScrobbleImporter
import com.kike.musicplayer.data.lastfm.Scrobbler
import com.kike.musicplayer.data.drive.DriveAuth
import com.kike.musicplayer.data.drive.DriveAuthInterceptor
import com.kike.musicplayer.data.drive.DriveLibrary
import com.kike.musicplayer.data.local.LocalLibrary
import com.kike.musicplayer.data.subsonic.SubsonicAccount
import com.kike.musicplayer.data.subsonic.SubsonicClient
import com.kike.musicplayer.data.subsonic.SubsonicCoverMapper
import com.kike.musicplayer.data.subsonic.SubsonicLibrary
import com.kike.musicplayer.data.subsonic.SubsonicUrls
import com.kike.musicplayer.data.subsonic.ServerPlays
import com.kike.musicplayer.data.subsonic.ServerMatch
import com.kike.musicplayer.data.subsonic.ServerCollections
import com.kike.musicplayer.data.metadata.CoverArtApi
import com.kike.musicplayer.data.metadata.MetadataEnricher
import com.kike.musicplayer.data.lyrics.LyricsRepository
import com.kike.musicplayer.data.metadata.ArtistImages
import com.kike.musicplayer.data.metadata.MusicBrainzApi
import com.kike.musicplayer.recommend.SavedMixes
import com.kike.musicplayer.sonos.CastManager
import com.kike.musicplayer.sonos.TrackServer
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class MusicApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                val covers = SubsonicCoverMapper(container.subsonic)
                add(covers)
                add(SubsonicCoverMapper.ForUri(covers))
                add(OkHttpNetworkFetcherFactory(callFactory = { container.baseHttp }))
            }
            .build()
}

/** Hand-wired dependencies; small enough not to need a DI framework. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Debug benchmark mode: synthetic library of this many songs on separate data (see [Bench]). */
    val benchSize: Int? = Bench.size(app)
    private val suffix = if (benchSize != null) "-bench" else ""

    val settings = Settings(app, "settings$suffix")
    val database = AppDatabase.create(app, if (benchSize != null) "bench.db" else "music.db").also { db ->
        benchSize?.let { n -> appScope.launch { Bench.seed(db, n) } }
    }
    val driveAuth = DriveAuth(app, settings)

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    val baseHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Used for the Drive API and for streaming Drive files in ExoPlayer. */
    val driveHttp: OkHttpClient = baseHttp.newBuilder()
        .addInterceptor(DriveAuthInterceptor(driveAuth))
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .build()

    /**
     * Drive tag scanning gets its own dispatcher: Media3's OkHttpDataSource goes through OkHttp's
     * async queue, whose default of 5 requests per host would make the scan's parallel reads queue
     * up and block streaming and API calls to the same host.
     */
    val driveScanHttp: OkHttpClient = driveHttp.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 32; maxRequests = 64 })
        .build()

    private val metadataHttp: OkHttpClient = baseHttp.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    private fun retrofit(baseUrl: String, client: OkHttpClient) = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val driveApi: DriveApi = retrofit(DriveApi.BASE_URL, driveHttp).create(DriveApi::class.java)
    private val musicBrainz = retrofit(MusicBrainzApi.BASE_URL, metadataHttp).create(MusicBrainzApi::class.java)
    private val coverArt = retrofit(CoverArtApi.BASE_URL, metadataHttp).create(CoverArtApi::class.java)

    val lyrics = LyricsRepository(database.lyrics(), metadataHttp, moshi)

    val artistImages = ArtistImages(app, database.artistImages(), database.artistInfo(), metadataHttp, moshi)

    val discover = DiscoverRepository(
        database.discover(), database.artistInfo(), settings, metadataHttp, moshi, BuildConfig.TICKETMASTER_KEY, appScope,
    )

    /** The Home mixes as last made. */
    val savedMixes = SavedMixes(app, "mixes$suffix")

    /** Album, artist and genre summaries for paging large libraries. */
    val indexer = LibraryIndexer(database, appScope).also { indexer ->
        indexer.start(settings.indexVersion) { settings.indexVersion = it }
    }

    val enricher = MetadataEnricher(app, database.tracks(), settings, driveAuth, driveScanHttp, database.artistInfo(), musicBrainz, coverArt)

    /** Subsonic / Navidrome server sign-in. */
    val subsonic = SubsonicAccount(app, SubsonicClient(baseHttp, moshi), "subsonic$suffix")
    val serverLibrary = SubsonicLibrary(subsonic, database, settings, appScope)
    /** Signed URLs for server songs and covers, made when they're opened. */
    val subsonicUrls = SubsonicUrls(subsonic)
    /** Plays reported to the server, as the user chose (off by default). */
    private val serverMatch = ServerMatch(database)
    val serverPlays = ServerPlays(subsonic, database, serverMatch, appScope).also { it.flush() }

    /** Lets Sonos fetch tracks from the phone while casting. */
    val trackServer = TrackServer(
        app, database.tracks(),
        // Sonos reads ahead in bursts and then pauses for minutes; a normal read timeout would drop the relay.
        driveHttp.newBuilder().readTimeout(10, TimeUnit.MINUTES).build(),
        subsonicUrls,
    )
    val castManager = CastManager(app, appScope)

    val downloader = Downloader(app, database.tracks(), driveHttp, subsonicUrls, appScope).also { it.verify() }

    val lastFm = LastFm(app, LastFmClient(baseHttp, moshi, BuildConfig.LASTFM_API_KEY, BuildConfig.LASTFM_SECRET), "lastfm$suffix")

    val scrobbler = Scrobbler(lastFm, database.lastFmOutbox(), database.tracks(), appScope).also { scrobbler ->
        lastFm.onConnected = scrobbler::flush
        scrobbler.flush() // whatever was left unsent last time
    }
    /** Favourites ↔ stars and playlists, both ways. */
    val serverCollections = ServerCollections(
        app, subsonic, database, serverMatch, scrobbler, songsSynced = { settings.lastServerSync.value > 0 }, scope = appScope,
        prefsName = "subsonic-sync$suffix",
    ).also { collections ->
        serverLibrary.onSynced = collections::syncNow
        collections.start()
    }
    val scrobbleImporter = ScrobbleImporter(
        lastFm, database.lastFmScrobbles(), database.history(), database.tracks(), database.favourites(), scrobbler, appScope,
    )

    val library = LibraryRepository(
        dao = database.tracks(),
        settings = settings,
        local = LocalLibrary(app),
        drive = DriveLibrary(driveApi),
        driveAuth = driveAuth,
        enricher = enricher,
        replacements = database.replacements(),
        downloader = downloader,
        scope = appScope,
    )

    private companion object {
        // MusicBrainz asks every client to identify itself: https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting
        const val USER_AGENT = "MusicMinchas/${BuildConfig.VERSION_NAME} ( personal Android app )"
    }
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as MusicApp).container

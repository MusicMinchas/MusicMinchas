package com.kike.musicplayer

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.kike.musicplayer.data.LibraryRepository
import com.kike.musicplayer.data.Settings
import com.kike.musicplayer.data.db.AppDatabase
import com.kike.musicplayer.data.drive.DriveApi
import com.kike.musicplayer.data.drive.DriveAuth
import com.kike.musicplayer.data.drive.DriveAuthInterceptor
import com.kike.musicplayer.data.drive.DriveLibrary
import com.kike.musicplayer.data.local.LocalLibrary
import com.kike.musicplayer.data.metadata.CoverArtApi
import com.kike.musicplayer.data.metadata.MetadataEnricher
import com.kike.musicplayer.data.metadata.MusicBrainzApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.baseHttp })) }
            .build()
}

/** Hand-wired dependencies; small enough not to need a DI framework. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val database = AppDatabase.create(app)
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

    val enricher = MetadataEnricher(app, database.tracks(), settings, driveAuth, driveScanHttp, musicBrainz, coverArt)

    val library = LibraryRepository(
        dao = database.tracks(),
        settings = settings,
        local = LocalLibrary(app),
        drive = DriveLibrary(driveApi),
        driveAuth = driveAuth,
        enricher = enricher,
        scope = appScope,
    )

    private companion object {
        // MusicBrainz asks every client to identify itself: https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting
        const val USER_AGENT = "KikeMusicPlayer/${BuildConfig.VERSION_NAME} ( personal Android app )"
    }
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as MusicApp).container

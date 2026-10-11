import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Signing key details live in keystore.properties (git-ignored; master copy in ../MusicPlayer-signing).
// Without it, builds fall back to the default debug key.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Last.fm API account (git-ignored). Without it the Last.fm section says it isn't set up.
val lastFmProps = Properties().apply {
    val file = rootProject.file("lastfm.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.kike.musicplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kike.musicplayer"
        minSdk = 30
        targetSdk = 36
        versionCode = 19
        versionName = "1.7.2"
        buildConfigField("String", "LASTFM_API_KEY", "\"${lastFmProps.getProperty("apiKey", "")}\"")
        buildConfigField("String", "LASTFM_SECRET", "\"${lastFmProps.getProperty("sharedSecret", "")}\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val releaseKey = signingConfigs.findByName("release")
        release {
            // No R8: Moshi reads the API models by reflection and size doesn't matter for a sideloaded app.
            isMinifyEnabled = false
            if (releaseKey != null) signingConfig = releaseKey
        }
        debug {
            // Same key as release, so debug and release builds can replace each other and Drive
            // sign-in (registered per signing certificate) works for both.
            if (releaseKey != null) signingConfig = releaseKey
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Playback
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-inspector:$media3") // MetadataRetriever (tag parsing for Drive files)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    // Network
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-moshi:3.0.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    // Google Drive authorization (OAuth 2.0 via Google Identity Services)
    implementation("com.google.android.gms:play-services-auth:22.0.0")

    // Database
    val room = "2.8.5"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // Drag-to-reorder for the queue
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    testImplementation("junit:junit:4.13.2")
}

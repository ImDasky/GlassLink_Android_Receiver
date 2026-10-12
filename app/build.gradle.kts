plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glasslink.receiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glasslink.receiver"
        minSdk = 23
        targetSdk = 35
        versionCode = 17
        versionName = "3.4.6"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    val releaseStoreFile = providers.gradleProperty("glasslinkReleaseStoreFile").orNull
    val releaseStorePassword = providers.gradleProperty("glasslinkReleaseStorePassword").orNull
    val releaseKeyAlias = providers.gradleProperty("glasslinkReleaseKeyAlias").orNull
    val releaseKeyPassword = providers.gradleProperty("glasslinkReleaseKeyPassword").orNull

    signingConfigs {
        if (releaseStoreFile != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null
        ) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseStoreFile != null &&
                releaseStorePassword != null &&
                releaseKeyAlias != null &&
                releaseKeyPassword != null
            ) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    val media3 = "1.8.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.webkit:webkit:1.15.0")
    testImplementation("junit:junit:4.13.2")
}

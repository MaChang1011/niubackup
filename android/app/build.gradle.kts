plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.machang.backup"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "net.machang.backup"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk {
            // The EasyTier JNI library is built for arm64-v8a only (M2 scope).
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Ship the .so files from src/main/jniLibs as-is.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Vendored from easytier-contrib/easytier-android-jni releases (see BUILD-NOTES.md):
    // app/libs/easytier-android-jni.aar (JNI Kotlin API) and
    // app/src/main/jniLibs/arm64-v8a/libeasytier_android_jni.so (native core, arm64 only).
    // (consumed via the flatDir repository in settings.gradle.kts)

    // M4c: Media3 (ExoPlayer) for tunnel-streamed playback (§6.3) and
    // WorkManager for constrained auto-backup (§6).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}

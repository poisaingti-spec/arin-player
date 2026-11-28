plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.arin.player"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.arin.player"
        minSdk = 26
        targetSdk = 35
        // Every CI run gets a higher versionCode, so the in-app updater knows what's newer.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.$run"
        buildConfigField("String", "UPDATE_REPO", "\"poisaingti-spec/arin-player\"")

        ndk { abiFilters += listOf("arm64-v8a") }

        externalNativeBuild {
            cmake {
                // whisper.cpp is unusably slow without optimisation, so always build it as Release.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // One permanent key for every build: updates install over the old app, never needing an uninstall.
    // The key comes from GitHub Secrets (KEYSTORE_BASE64 / KEYSTORE_PASSWORD); it is never in the repo.
    signingConfigs {
        create("arin") {
            storeFile = rootProject.file("keystore/arin-player.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = "arinplayer"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("arin")
        }
        debug {
            signingConfig = signingConfigs.getByName("arin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "**/libc++_shared.so"
            pickFirsts += "**/libonnxruntime.so"
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Plays practically every container/codec (MKV, AVI, WMV, FLV, TS, AC3, DTS, DivX…)
    implementation("org.videolan.android:libvlc-all:3.6.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Meta Omnilingual ASR (1,600+ languages) on the phone via sherpa-onnx.
    // The .aar is downloaded by the GitHub build (see .github/workflows/build.yml).
    implementation(files("libs/sherpa-onnx.aar"))

    // Free on-device translation (Google ML Kit) for offline / backup subtitles
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // yt-dlp (+ Python + QuickJS) for YouTube and 1000+ other websites
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
}

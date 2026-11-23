plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The GitHub build number becomes the app version, so the updater can compare versions.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.tvcast.airplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvcast.airplay"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "2.0.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"mfshafqat1989-glitch/tvcast-airplay\"")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DOPENSSL_ROOT=${rootDir}/openssl-android",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }

    signingConfigs {
        // A fixed key, so every new version installs over the old one (needed for auto-update).
        create("release") {
            storeFile = file("../keystore/release.jks")
            storePassword = "tvcastairplay"
            keyAlias = "tvcast"
            keyPassword = "tvcastairplay"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}

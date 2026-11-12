plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ahmadhadi.archivoloco"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ahmadhadi.archivoloco"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // A stable signing key lets new builds install over old ones. CI decodes it from the
    // ARCHIVOLOCO_KEYSTORE_B64 secret when present; otherwise the default debug key is used.
    val ksFile = rootProject.file("release.keystore")
    signingConfigs {
        if (ksFile.exists()) {
            create("stable") {
                storeFile = ksFile
                storePassword = System.getenv("ARCHIVOLOCO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ARCHIVOLOCO_KEY_ALIAS") ?: "archivoloco"
                keyPassword = System.getenv("ARCHIVOLOCO_KEY_PASSWORD")
                    ?: System.getenv("ARCHIVOLOCO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile.exists()) signingConfigs.getByName("stable")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

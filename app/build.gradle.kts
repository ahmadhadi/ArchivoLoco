plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
                // Unset GitHub secrets arrive as empty strings, so treat blank as missing.
                fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
                storePassword = env("ARCHIVOLOCO_KEYSTORE_PASSWORD")
                keyAlias = env("ARCHIVOLOCO_KEY_ALIAS") ?: "archivoloco"
                keyPassword = env("ARCHIVOLOCO_KEY_PASSWORD") ?: env("ARCHIVOLOCO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

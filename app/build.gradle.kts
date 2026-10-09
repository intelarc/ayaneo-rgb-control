// Kotlin is compiled by AGP 9's built-in Kotlin support (no kotlin-android plugin needed).
import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: keystore.properties + the keystore live next to the project and are never committed.
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.ambientrgb"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rgbplus.ayaneo"
        minSdk = 30          // AYANEO Pocket Air Mini ships Android 11
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (signing.containsKey("storeFile")) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

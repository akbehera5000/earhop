import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from a file outside the repository, so the key and its passwords are never committed:
// ~/.earhop/android-signing.properties with storeFile, storePassword, keyAlias and keyPassword. Without it the release
// build is unsigned.
val signingFile = File(System.getProperty("user.home"), ".earhop/android-signing.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "com.earhop.logger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.earhop.logger"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "1.4.1"
    }

    signingConfigs {
        create("release") {
            if (!signing.isEmpty) {
                storeFile = File(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!signing.isEmpty) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Shizuku (Apache-2.0): lets EarHop start its clipboard helper as the shell user without a computer.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // ZXing Android Embedded (Apache-2.0): scans the Mac's pairing QR code inside EarHop.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // The scanner calls androidx.core (ContextCompat) but does not bring it along; without it the camera screen crashed
    // on opening (NoClassDefFoundError, 2026-11-13). Its BarcodeEncoder also draws this device's pairing QR code.
    implementation("androidx.core:core:1.12.0")
    testImplementation("junit:junit:4.13.2")
}

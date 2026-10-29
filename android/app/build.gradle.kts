plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.earhop.logger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.earhop.logger"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.1.0"
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
    testImplementation("junit:junit:4.13.2")
}

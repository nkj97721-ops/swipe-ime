plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ime.swipe"
    compileSdk = 34

    defaultConfig {
        applicationId = "ime.swipe.k202611120747"
        minSdk = 26
        targetSdk = 34
        versionCode = 2026111247
        versionName = "2026.11.12-0747"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    kotlinOptions {
        jvmTarget = "17"
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ime.swipe"
    compileSdk = 34

    defaultConfig {
        applicationId = "ime.swipe.k202611022119"
        minSdk = 26
        targetSdk = 34
        versionCode = 2026110225
        versionName = "2026.11.02-2119"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

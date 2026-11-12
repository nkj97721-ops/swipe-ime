plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ime.swipe"
    compileSdk = 34

    defaultConfig {
        applicationId = "ime.swipe.k202611120807"
        minSdk = 26
        targetSdk = 34
        versionCode = 202611120807
        versionName = "2026.11.12-0807"
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

dependencies {
    testImplementation("junit:junit:4.13.2")
}

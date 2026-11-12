plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ime.swipe"
    compileSdk = 34

    defaultConfig {
        applicationId = "ime.swipe.k202611120842"
        minSdk = 26
        targetSdk = 34
        versionCode = 2026111242
        versionName = "2026.11.12-0842"
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

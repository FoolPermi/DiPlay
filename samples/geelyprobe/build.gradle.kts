// Geely Probe: lists Geely/Lynk packages, services and vendor actions on the head unit and
// tries to bind the wheel-key, media-center and online-music services.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.diplay.geelyprobe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.diplay.geelyprobe"
        minSdk = 25
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

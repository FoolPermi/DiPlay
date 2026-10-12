// Audio Probe: plays a tone on each Android audio stream/usage and shows live stream
// volumes, so a head unit's audio routing and volume knob can be identified.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.diplay.audioprobe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.diplay.audioprobe"
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

// Settings Probe: a tiny diagnostic app that tries to open every system settings
// screen a head unit may still expose, to find which ones the firmware kept.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.diplay.settingsprobe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.diplay.settingsprobe"
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

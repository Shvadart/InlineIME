plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.shvadart.inlineime"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.shvadart.inlineime"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha01"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

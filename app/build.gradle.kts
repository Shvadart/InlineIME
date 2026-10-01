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

        // CI gives every build a monotonically increasing versionCode.
        // Local builds keep a small deterministic fallback.
        versionCode = providers.environmentVariable("INLINEIME_VERSION_CODE")
            .orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("INLINEIME_VERSION_NAME")
            .orNull ?: "0.1.0-dev"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

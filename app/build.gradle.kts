plugins {
    id("com.android.application")
}

android {
    namespace = "dev.tomppi.airpodsprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tomppi.airpodsprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("ci") {
            val storeFilePath = project.findProperty("AIRPODS_PROBE_KEYSTORE") as String?
            val storePass = project.findProperty("AIRPODS_PROBE_KEYSTORE_PASSWORD") as String?
            val keyAliasValue = project.findProperty("AIRPODS_PROBE_KEY_ALIAS") as String?
            val keyPass = project.findProperty("AIRPODS_PROBE_KEY_PASSWORD") as String?
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = storePass
                keyAlias = keyAliasValue
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ci")
        }
    }
}

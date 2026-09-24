plugins {
    id("com.android.application")
}

android {
    namespace = "com.siper.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.siper.vpn"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-real"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    implementation("com.wgtunnel:backend:1.6.16")
    implementation("com.wgtunnel:backend-android-jni:1.6.16")
    implementation("com.wgtunnel:hevtunnel:1.6.16")
    implementation("com.wgtunnel:parser:1.6.16")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}

plugins {
    id("com.android.application")
}

android {
    namespace = "com.siper.vpn"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.siper.vpn"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.7.0-rescue"
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
        jniLibs {
            useLegacyPackaging = true
        }
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
    implementation("ca.psiphon:psiphontunnel:2.0.41")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}

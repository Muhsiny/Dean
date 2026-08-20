plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "org.sayeh.wificontrol"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.sayeh.wificontrol"
        minSdk = 23
        targetSdk = 35
        versionCode = 41
        versionName = "4.1.0-hybrid-auth"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

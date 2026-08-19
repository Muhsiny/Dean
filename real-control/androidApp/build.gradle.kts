plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "org.sayeh.realwifi"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.sayeh.realwifi"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0-real"
    }
    sourceSets.getByName("main").assets.srcDir("../shared")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

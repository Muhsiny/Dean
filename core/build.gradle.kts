plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { jvmToolchain(17) }
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:3.14.9")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("commons-net:commons-net:3.11.1")
}

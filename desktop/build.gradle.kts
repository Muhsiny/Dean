plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("org.sayeh.wificontrol.desktop.DesktopMainKt") }

dependencies {
    implementation(project(":core"))
    implementation(kotlin("stdlib"))
}

tasks.jar {
    manifest { attributes["Main-Class"] = "org.sayeh.wificontrol.desktop.DesktopMainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) })
}

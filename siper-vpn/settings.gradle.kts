pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven {
            name = "psiphon"
            url = uri("https://raw.githubusercontent.com/Psiphon-Labs/psiphon-tunnel-core-Android-library/master")
            content { includeGroup("ca.psiphon") }
        }
    }
}
rootProject.name = "SiperVPN"
include(":app")

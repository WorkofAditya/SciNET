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
    }
}

rootProject.name = "SciNET"

// Declare the Android application as a real Gradle subproject and point it
// explicitly at its Kotlin DSL build file. This is not a Run Configuration.
include(":app")
project(":app").projectDir = file("app")
project(":app").buildFileName = "build.gradle.kts"

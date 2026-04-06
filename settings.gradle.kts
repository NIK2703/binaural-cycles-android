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

rootProject.name = "BinauralBeats"

// App module
include(":app")

// Core modules
include(":core:audio")
include(":core:ui")
include(":core:domain")

// Data modules
include(":data:preferences")

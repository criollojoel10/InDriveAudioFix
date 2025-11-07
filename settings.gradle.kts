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
        // Repositorio oficial del Xposed API
        maven(url = "https://api.xposed.info/")
    }
}

rootProject.name = "InDriveAudioFix"
include(":app")
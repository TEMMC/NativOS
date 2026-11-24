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
    }
}

rootProject.name = "NativOS"
include(":app", ":gnome", ":hosted-runtime", ":x11-runtime")
project(":hosted-runtime").projectDir = file("../vendor/magicdesk/hosted-runtime")
project(":x11-runtime").projectDir = file("../vendor/magicdesk/x11-runtime")

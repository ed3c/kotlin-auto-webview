pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jogamp.org/deployment/maven")
    }
}

rootProject.name = "kotlin-auto-webview"
include(":composeApp")

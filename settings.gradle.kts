pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // 👉 Versiones de los plugins (AJUSTADAS para Gradle 8.x)
        id("com.android.application") version "8.6.1"
        id("org.jetbrains.kotlin.android") version "1.9.24"
        id("com.chaquo.python") version "15.0.1"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android_app"
include(":app")
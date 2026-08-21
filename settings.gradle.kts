rootProject.name = "redflagdeals-revanced-patches"

pluginManagement {
    includeBuild("build-logic/revanced-patches-gradle-plugin")

    repositories {
        gradlePluginPortal()
        google()
    }
}

plugins {
    id("app.revanced.patches") version "1.0.0-dev.10"
}

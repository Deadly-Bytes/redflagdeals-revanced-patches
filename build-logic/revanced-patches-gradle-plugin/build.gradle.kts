@file:OptIn(ExperimentalAbiValidation::class)

import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.vanniktech.mavenPublish)
    `java-gradle-plugin`
}

group = "app.revanced"

dependencies {
    implementation(libs.android.application)
    implementation(libs.guava)
    implementation(libs.kotlin)
    implementation(libs.kotlin.android)
    implementation(libs.vanniktech.mavenPublish)

    implementation(gradleApi())
    implementation(gradleKotlinDsl())
}

kotlin {
    abiValidation {
        enabled = true
    }

    compilerOptions {
        // The Stage 2 build runtime is pinned to Java 21. Patch bytecode remains
        // targeted to JVM 17 by PatchesPlugin; this only compiles the build plugin.
        jvmToolchain(21)
    }
}

gradlePlugin {
    website = "https://revanced.app"
    vcsUrl = "ssh://git@github.com:revanced/revanced-patches-gradle-plugin.git"

    plugins {
        create("patchesSettingsPlugin") {
            id = "app.revanced.patches"
            implementationClass = "app.revanced.patches.gradle.SettingsPlugin"
            version = version
            description = "Plugin to configure a ReVanced Patches project."
            displayName = "ReVanced Patches Gradle settings plugin"
        }
    }
}

mavenPublishing {
    // This vendored build is consumed locally and is never published.
    signAllPublications()
    extensions.getByType<SigningExtension>().useGpgCmd()

    coordinates(group.toString(), project.name, version.toString())
}

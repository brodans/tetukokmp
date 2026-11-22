pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://jitpack.io")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "TETUKO"

include(
    ":composeApp",
    ":desktopApp",
    ":androidApp",
    ":core:common",
    ":core:domain",
    ":core:data",
    ":proxyServer",
)

project(":core:common").projectDir = file("core/common")
project(":core:domain").projectDir = file("core/domain")
project(":core:data").projectDir = file("core/data")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

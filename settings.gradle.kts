pluginManagement {
    includeBuild("build-logic")

    repositories {
        gradlePluginPortal()
        mavenCentral()
        // build-logic's own dependencies (tiny-remapper, mapping-io) resolve through the buildscript classpath of the consuming build, so this repo is needed here too.
        maven("https://maven.fabricmc.net") { name = "fabricmc" }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.spongepowered.org/repository/maven-public") { name = "spongepowered" }
        maven("https://maven.fabricmc.net") { name = "fabricmc" }
    }
}

rootProject.name = "alaphant"

include(":mod")

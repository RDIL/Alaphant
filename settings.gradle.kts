pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.spongepowered.org/repository/maven-public") { name = "spongepowered" }
        maven("https://maven.fabricmc.net") { name = "fabricmc" }
        maven("https://maven.quiltmc.org/repository/release") { name = "quiltmc" }
        // Enigma is taken from a fork built by JitPack.
        maven("https://jitpack.io") {
            name = "jitpack"
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "alaphant"

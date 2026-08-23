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
    }
}

rootProject.name = "alaphant"

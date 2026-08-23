dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net") { name = "fabricmc" }
    }

    // buildSrc gets its own view of the catalog; same file, so versions stay in one place.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "buildSrc"

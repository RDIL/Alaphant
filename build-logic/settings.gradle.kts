dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net") { name = "fabricmc" }
    }

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = "alaphant"
version = rootProject.version

kotlin {
    jvmToolchain(17)
}

/** The remapped Charles jar, produced by the root project's `remapNamed`. */
val charlesNamed = configurations.create("charlesNamed")

configurations.named("compileOnly") {
    extendsFrom(charlesNamed)
}

dependencies {
    charlesNamed(project(mapOf("path" to ":", "configuration" to "namedJar")))

    compileOnly(libs.mixin)
    compileOnly(libs.annotations)
    implementation(libs.java.jwt)
}

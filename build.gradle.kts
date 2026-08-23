plugins {
    id("alaphant.charles")
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = "alaphant"
version = "2.0.0-SNAPSHOT"

// Charles 5.0.3 is compiled to class version 61 and ships a Java 17 runtime image.
kotlin {
    jvmToolchain(17)
}

dependencies {
    // The remapped Charles jar, at a fixed path so IntelliJ treats it as a library and picks up
    // renames as soon as `remapNamed` rewrites it. `builtBy` keeps Gradle's task graph honest
    // without turning it back into a configuration the IDE cannot see through.
    compileOnly(files(charles.namedJar).builtBy("remapNamed"))

    // Charles' own module path, rather than a hand-maintained list of Maven coordinates.
    compileOnly(files(charles.libraryJars))

    compileOnly(libs.mixin)
    compileOnly(libs.annotations)
    implementation(libs.java.jwt)

    charlesDecompiler(libs.vineflower)

    // Enigma's shaded distribution: everything it needs in one jar, taken non-transitively so its
    // native-classifier dependencies never have to resolve.
    enigmaClasspath(variantOf(libs.enigma.swing) { classifier("all") }) { isTransitive = false }
}

// The named store is source, not output, so nothing else would ever check it.
tasks.named("check") {
    dependsOn("validateMappings")
}

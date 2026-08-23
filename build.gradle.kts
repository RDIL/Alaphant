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
    // A plain file dependency at a fixed path: IntelliJ re-reads it as `remapNamed` rewrites it,
    // which a configuration would break. `builtBy` keeps the task graph correct.
    compileOnly(files(charles.namedJar).builtBy("remapNamed"))

    // Charles' own module path, rather than a hand-maintained list of Maven coordinates.
    compileOnly(files(charles.libraryJars))

    compileOnly(libs.mixin)
    compileOnly(libs.annotations)
    implementation(libs.java.jwt)

    charlesDecompiler(libs.vineflower)

    // Enigma's shaded distribution, non-transitive so its native-classifier deps never resolve.
    enigmaClasspath(variantOf(libs.enigma.swing) { classifier("all") }) { isTransitive = false }
}

// The named store is source, not output, so nothing else would ever check it.
tasks.named("check") {
    dependsOn("validateMappings")
}

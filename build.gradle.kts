import alaphant.build.tasks.AgentJarTask

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

    compileOnly(libs.annotations)

    // Bundled into the agent jar, so runtime scope rather than compileOnly. Mixin publishes an empty
    // POM: ASM, Guava and Gson are its undeclared runtime dependencies and have to be named here.
    implementation(libs.mixin)
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    implementation(libs.asm.analysis)
    implementation(libs.asm.util)
    implementation(libs.guava)
    implementation(libs.gson)
    implementation(libs.java.jwt)

    charlesDecompiler(libs.vineflower)

    // Enigma's shaded distribution, non-transitive so its native-classifier deps never resolve.
    enigmaClasspath(variantOf(libs.enigma.swing) { classifier("all") }) { isTransitive = false }
}

/**
 * The `-javaagent` jar that patches Charles: the mod, Mixin, and everything Mixin needs, in one
 * archive on a fixed path. `runWithMod` reads it from there; so can anything launching Charles by
 * hand. See `AgentJarTask` for why it is assembled rather than shadowed.
 */
val agentJar = tasks.register<AgentJarTask>("agentJar") {
    group = "build"
    description = "Bundles the mod and its runtime dependencies into the Charles patching agent."
    modClasses.from(sourceSets.main.map { it.output })
    libraries.from(configurations.runtimeClasspath)
    premainClass.set("alaphant.agent.AlaphantAgent")
    outputJar.set(charles.agentJar)
}

tasks.named("assemble") {
    dependsOn(agentJar)
}

// The named store is source, not output, so nothing else would ever check it. `checkLinkage` is
// here rather than in the plugin for the same reason: it is the gate on what the mappings produce.
// `checkMatcher` is the only test the version matcher has -- and the one piece of the build whose
// mistakes are silent, since a wrongly carried ID looks exactly like a correctly carried one.
tasks.named("check") {
    dependsOn("validateMappings", "checkLinkage", "checkMatcher")
}

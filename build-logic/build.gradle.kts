plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.tiny.remapper)
    implementation(libs.mapping.io)
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.gson)
}

gradlePlugin {
    plugins {
        register("charles") {
            id = "alaphant.charles"
            implementationClass = "alaphant.build.CharlesPlugin"
        }
    }
}

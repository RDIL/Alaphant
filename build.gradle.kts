plugins {
    id("alaphant.charles")
}

group = "alaphant"
version = "2.0.0-SNAPSHOT"

dependencies {
    charlesDecompiler(libs.vineflower)
}

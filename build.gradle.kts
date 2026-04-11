plugins {
    id("java")
}

group = "com.github.groundbreakingmc"
version = "1.0.2"

repositories {
    mavenCentral() // Configurate
    maven("https://repo.papermc.io/repository/maven-public/") // Paper
    maven("https://repo.codemc.io/repository/maven-releases/") // PacketEvents
    maven("https://repo.helpch.at/releases") // PlaceholderAPI
    maven("https://jitpack.io") // VaultAPI
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.11.2")
    compileOnly("me.clip:placeholderapi:2.11.7")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        isTransitive = false
    }
    compileOnly("org.spongepowered:configurate-yaml:4.2.0")
}

java.toolchain {
    languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.encoding = "UTF-8"
}

plugins {
    java
    alias(libs.plugins.shadow)
}

group = "com.github.groundbreakingmc"
version = "2.0.1"

repositories {
    mavenCentral() // JCTools
    maven("https://repo.papermc.io/repository/maven-public/") // Paper
    maven("https://repo.codemc.io/repository/maven-releases/") // PacketEvents
    maven("https://repo.helpch.at/releases") // PlaceholderAPI
    maven("https://jitpack.io") // MyLib, GikyMessage, VaultAPI
}

dependencies {
    compileOnly(libs.paper.api)

    // Source: https://github.com/retrooper/packetevents
    compileOnly(libs.packetevents.spigot)

    // Source: https://github.com/groundbreakingmc/MyLib
    implementation(libs.mylib)

    // Source: https://github.com/groundbreakingmc/GikyMessage
    implementation(libs.gikymessage)

    // Source: https://github.com/PlaceholderAPI/PlaceholderAPI
    compileOnly(libs.placeholderapi)

    // Source: https://github.com/MilkBowl/VaultAPI
    compileOnly(libs.vaultapi) {
        isTransitive = false
    }

    // Source: https://luckperms.net/wiki/Developer-API
    compileOnly(libs.luckperms.api)

    // Source: https://github.com/JCTools/JCTools
    implementation(libs.jstools)
}

java.toolchain {
    languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    withType<JavaCompile> {
        options.release = 21
        options.encoding = "UTF-8"
    }

    shadowJar {
        relocate(
            "com.github.groundbreakingmc.mylib",
            "com.github.groundbreakingmc.moderntags.libs.mylib"
        )

        relocate(
            "com.github.groundbreakingmc.gikymessage",
            "com.github.groundbreakingmc.moderntags.libs.gikymessage"
        )

        relocate(
            "org.jctools",
            "com.github.groundbreakingmc.moderntags.libs.jctools"
        )

        minimize()
    }

    build {
        dependsOn(shadowJar)
    }
}

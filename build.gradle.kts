plugins {
    java
    alias(libs.plugins.shadow)
}

group = "com.github.groundbreakingmc"
version = "3.0.0"

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

    // Netty is provided by the server; use its channel/event-loop API directly.
    compileOnly(libs.netty.transport)

    // Source: https://github.com/groundbreakingmc/MyLib
    implementation(libs.mylib)

    // Source: https://github.com/groundbreakingmc/GikyMessage
    implementation(libs.gikymessage)

    // Spigot has no Adventure serializers, and PacketEvents bundles adventure-api without them.
    // Paper loads its own copies first, so the packaged classes are used only on Spigot.
    // Source: https://github.com/KyoriPowered/adventure
    implementation(libs.adventure.api) { isTransitive = false }
    implementation(libs.adventure.minimessage) { isTransitive = false }
    implementation(libs.adventure.serializer.legacy) { isTransitive = false }
    implementation(libs.adventure.serializer.plain) { isTransitive = false }

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

    // Source: https://github.com/Bastian/bstats-metrics
    implementation(libs.bstats.bukkit)
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

        relocate("org.bstats", "com.github.groundbreakingmc.moderntags.libs.bstats")

        // Adventure stays unrelocated to share Component with the server or PacketEvents.
        // Of adventure-api, only the serializer interfaces that PacketEvents strips are packaged.
        exclude {
            it.path.startsWith("net/kyori/")
                    && !it.path.startsWith("net/kyori/adventure/text/serializer/")
                    && !it.path.startsWith("net/kyori/adventure/text/minimessage/")
        }

        minimize()
    }

    build {
        dependsOn(shadowJar)
    }
}

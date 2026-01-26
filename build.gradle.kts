plugins {
    java
    id("com.gradleup.shadow") version "9.3.1"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
    id("xyz.jpenilla.run-paper") version "2.3.0"
}

group = "net.opmasterleo"
version = "1.0.0"
description = "Multi-player inventory synchronization plugin with team support"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    paperweight.paperDevBundle("1.21.1-R0.1-SNAPSHOT")
    compileOnly("com.github.booksaw:BetterTeams:4.15.2")
    implementation("org.bstats:bstats-bukkit:3.1.0")
    implementation("redis.clients:jedis:7.2.1")
    implementation("com.google.code.gson:gson:2.13.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    assemble {
        dependsOn(reobfJar)
    }

    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        configurations = listOf(project.configurations.runtimeClasspath.get())
        dependencies {
            exclude { it.moduleGroup != "org.bstats" && it.moduleGroup != "redis.clients" && it.moduleGroup != "com.google.code.gson" }
        }
        relocate("org.bstats", "${project.group}.bstats")
        relocate("redis.clients.jedis", "${project.group}.jedis")
        relocate("com.google.gson", "${project.group}.gson")
    }

    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(21)
    }

    javadoc {
        options.encoding = Charsets.UTF_8.name()
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name()
        val props = mapOf(
            "name" to project.name,
            "version" to project.version,
            "description" to project.description,
            "apiVersion" to "1.21"
        )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}

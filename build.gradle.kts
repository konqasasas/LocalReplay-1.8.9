import net.fabricmc.loom.task.RemapJarTask

plugins {
    idea
    java
    id("gg.essential.loom") version "0.10.0.+"
    id("dev.architectury.architectury-pack200") version "0.1.3"
}

val baseGroup: String by project
val mcVersion: String by project
val modVersion: String by project
val modid: String by project

group = baseGroup
version = modVersion

// Minecraft 1.8.9 / Forge code must be compiled for Java 8.
// Run Gradle itself with JDK 17 (see SETUP.md).
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

loom {
    forge {
        // Required for legacy Forge distributions that still use Pack200.
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
    }

    // Local Replay is client-side, so keep the IDE/run list simple.
    runConfigs {
        remove(getByName("server"))
    }
}

// Legacy Minecraft expects resources next to compiled classes in dev runs.
sourceSets.main {
    output.setResourcesDir(sourceSets.main.flatMap { it.java.classesDirectory })
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings("de.oceanlabs.mcp:mcp_stable:22-$mcVersion")
    forge("net.minecraftforge:forge:$mcVersion-11.15.1.2318-$mcVersion")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mcversion", mcVersion)

    filesMatching("mcmod.info") {
        expand(inputs.properties)
    }
}

// Keep the normal development jar separate from the remapped distributable jar.
tasks.jar {
    archiveBaseName.set("$modid-$mcVersion")
    archiveClassifier.set("dev")
}

tasks.named<RemapJarTask>("remapJar") {
    archiveBaseName.set("$modid-$mcVersion")
    archiveClassifier.set("")
}

tasks.assemble {
    dependsOn(tasks.named("remapJar"))
}

project.afterEvaluate {
    tasks.named("runClient", JavaExec::class) {
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    }
}

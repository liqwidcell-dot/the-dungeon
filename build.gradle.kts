plugins {
    // The standard plugin for compiling Java.
    java

    // Adds a `runPaper` task that downloads and starts a real Paper server with
    // your plugin already installed. `./gradlew runPaper` is your test loop.
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "club.hackclub"
version = "0.2.0"

java {
    // Paper 26.2 runs on Java 25. The toolchain pins the *compiled output* to
    // Java 25 bytecode no matter which JDK you build with — the compiler will
    // fetch a JDK 25 for you if your machine does not have one.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    // Paper's own Maven repo. Not on Maven Central.
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // compileOnly, not implementation: the server already has the Paper API at
    // runtime, so bundling it would be redundant and can cause conflicts.
    //
    // The `+` means "newest build of 26.2", so a patch release of Paper updates
    // itself. Pin it exactly (e.g. "26.2-R0.1-SNAPSHOT") if you ever need a
    // reproducible build.
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

tasks {
    // Copy the version number from this build file into plugin.yml, so you only
    // ever change it in one place.
    processResources {
        val pluginVersion = project.version.toString()
        inputs.property("version", pluginVersion)

        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
        }
    }

    // `runPaper` downloads the latest Paper release automatically. If you want
    // to pin it to one version so the test server never changes under you, add
    // a `minecraftVersion` setting here — see the run-paper README for the
    // exact syntax for the version you have installed (3.1.0).
}

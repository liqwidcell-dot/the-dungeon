// Settings for the plugin's Gradle build.
//
// rootProject.name becomes the folder name of the JAR that ./gradlew build
// produces, so this is the first thing to rename.
rootProject.name = "hackclub-plugin"

plugins {
    // Lets Gradle download the exact JDK this project needs (Java 25) instead of
    // using whatever JDK happens to be installed. Your machine currently has
    // JDK 27, which cannot produce class files a Paper server will load, so this
    // is not optional here.
    //
    // Must stay 1.0.0 or newer: 0.10.0 crashes on Gradle 9.x.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

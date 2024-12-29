// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // Apply plugins for Android and Kotlin explicitly here if needed for all modules
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Include dependencies for the build system
        classpath("com.android.tools.build:gradle:8.1.0") // Use the matching Gradle Plugin version
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.10") // Match Kotlin version
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// Optional: Tasks common to all modules can be added here
tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

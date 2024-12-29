pluginManagement {
    repositories {
        maven { url = uri("https://mvnrepository.com/artifact/ai.onnxruntime/onnxruntime-mobile") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MyApp"
include(":app")

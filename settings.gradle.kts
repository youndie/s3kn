pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "s3kn"

include(":s3-core")
include(":s3-sigv4")
include(":s3-client")
include(":s3-testing")
include(":examples:tls-check")
include(":examples:quickstart")

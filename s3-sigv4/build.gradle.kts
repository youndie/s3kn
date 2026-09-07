plugins {
    kotlin("multiplatform")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.publish")
}

// Signing and presigning. Pure Kotlin: it must build and pass its tests without an HTTP engine
// and without a network, because that is what lets the AWS test vectors run on every target.

kotlin {
    // THE TARGETS STAY HERE, and the reasons with them. `sborka.kmp` gives the mechanics — explicit
    // API, the toolchain, warnings as errors, the jvm target compiled to the floor — and declares no
    // target of its own: the set below is a decision this repository argued out, not a portfolio
    // default.
    //
    // Target platform number one; milestones are closed against it. Apple: the engine there is
    // `ktor-client-darwin` — `ktor-client-curl` publishes nothing for iOS at all
    // (docs/research/research-architecture.md, fact 1.11). `watchos` and `tvos` are left out although
    // every dependency publishes them: no test has ever run there, and "it compiles" is a different
    // claim from "it works". `macosX64` is left out for a stronger reason: Kotlin has deprecated the
    // target and nothing here has ever run on it.
    jvm()
    linuxX64()
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(projects.s3Core)
            implementation(libs.kotlincrypto.hash.sha2)
            implementation(libs.kotlincrypto.hmac.sha2)
        }
        commonTest.dependencies {
            implementation(projects.s3Testing)
        }
    }
}

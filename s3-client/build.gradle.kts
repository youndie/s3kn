plugins {
    kotlin("multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

// The seven operations on top of an HttpClient supplied by the caller. Knows about the client
// core, never about a particular engine — which engine can carry HTTPS is a property of the
// target, and on linuxX64 there is exactly one that can.

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
            api(projects.s3Sigv4)
            api(libs.ktor.client.core)
            // A listing is a Flow of pages: a bucket can hold millions of keys, so the whole
            // listing is never assembled.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(projects.s3Testing)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        // The engine used by the tests that talk to a real server, declared per source set because
        // no single engine covers all of them: curl is the only one that speaks HTTPS on Linux,
        // Darwin the only one that exists on iOS, and neither of them exists on the JVM.
        linuxTest.dependencies {
            implementation(libs.ktor.client.curl)
        }
        appleTest.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.cio)
        }
    }
}

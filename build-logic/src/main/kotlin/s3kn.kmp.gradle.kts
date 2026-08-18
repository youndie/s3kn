import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("s3kn.publish")
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    // A library: every public declaration spells out its visibility and its return type.
    explicitApi()

    jvmToolchain(25)

    jvm {
        compilerOptions {
            // Deliberately far below the toolchain: the JDK that builds this library is not the
            // JDK that has to run it.
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Target platform number one; milestones are closed against it.
    linuxX64()

    // Apple. The engine there is `ktor-client-darwin` — `ktor-client-curl` publishes nothing for
    // iOS at all (docs/research/research-architecture.md, fact 1.11).
    //
    // `watchos` and `tvos` are left out although every dependency publishes them: no test has ever
    // run there, and "it compiles" is a different claim from "it works".
    //
    // `macosX64` is left out too, and for a stronger reason: Kotlin has deprecated the target —
    // "will be removed in a future release" — and nothing here has ever run on it, since the CI
    // runner is Apple Silicon. Publishing a deprecated target nobody exercises is a claim with
    // nothing behind it.
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

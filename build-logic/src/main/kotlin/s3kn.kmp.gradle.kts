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

    // The host target for the local TDD loop: linuxX64 tests do not run on macOS.
    macosArm64()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

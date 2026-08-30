plugins {
    kotlin("multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
}

// The measuring binary. Not published, not an example — it exists to answer risk 5 of the
// research, and it has to run on a machine that is not the one running the server.

kotlin {
    // NOT A LIBRARY: nothing here is published and nothing depends on it, so there is no consumer for
    // a spelled-out public API to be spelled out FOR. `sborka.kmp` turns explicit API on because that
    // is right for the four modules that are published; this switches it back off for the three that
    // are binaries. The rest of what the convention brings — the toolchain, warnings as errors — is
    // wanted here as much as anywhere.
    explicitApi = null

    linuxX64 {
        binaries.executable {
            entryPoint = "io.github.youndie.s3.benchmark.main"
        }
    }

    sourceSets {
        linuxX64Main.dependencies {
            implementation(projects.s3Client)
            implementation(libs.ktor.client.curl)
            // Plain HTTP only, and only here — see engineOf() for why the library cannot use it.
            implementation(libs.ktor.client.cio)
        }
    }
}

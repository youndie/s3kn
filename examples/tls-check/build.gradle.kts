plugins {
    kotlin("multiplatform")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
}

// A binary whose only job is to prove that a native image can complete a TLS handshake.
//
// The library links OpenSSL statically, inside the cinterop klib of ktor-client-curl, so nothing
// has to be installed for TLS itself. The root certificates are another matter: they come from the
// filesystem, and a minimal image has none (docs/research/research-architecture.md, risk 2).

kotlin {
    // NOT A LIBRARY: nothing here is published and nothing depends on it, so there is no consumer for
    // a spelled-out public API to be spelled out FOR. `sborka.kmp` turns explicit API on because that
    // is right for the four modules that are published; this switches it back off for the three that
    // are binaries. The rest of what the convention brings — the toolchain, warnings as errors — is
    // wanted here as much as anywhere.
    explicitApi = null

    linuxX64 {
        binaries.executable {
            entryPoint = "io.github.youndie.s3.example.main"
        }
    }

    sourceSets {
        linuxX64Main.dependencies {
            implementation(projects.s3Client)
            implementation(libs.ktor.client.curl)
        }
    }
}

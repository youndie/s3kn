plugins {
    id("s3kn.example")
}

// A binary whose only job is to prove that a native image can complete a TLS handshake.
//
// The library links OpenSSL statically, inside the cinterop klib of ktor-client-curl, so nothing
// has to be installed for TLS itself. The root certificates are another matter: they come from the
// filesystem, and a minimal image has none (docs/research/research-architecture.md, risk 2).

kotlin {
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

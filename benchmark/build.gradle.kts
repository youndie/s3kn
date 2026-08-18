plugins {
    id("s3kn.example")
}

// The measuring binary. Not published, not an example — it exists to answer risk 5 of the
// research, and it has to run on a machine that is not the one running the server.

kotlin {
    linuxX64 {
        binaries.executable {
            entryPoint = "io.github.youndie.s3.benchmark.main"
        }
    }

    sourceSets {
        linuxX64Main.dependencies {
            implementation(projects.s3Client)
            implementation(libs.ktor.client.curl)
        }
    }
}

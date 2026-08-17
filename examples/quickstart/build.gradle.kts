plugins {
    id("s3kn.example")
}

// The code the README shows, as something the build compiles.
//
// A README example that nobody compiles drifts from the API within two milestones, and the drift is
// invisible until a reader tries it.

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

plugins {
    id("s3kn.kmp")
}

// The seven operations on top of an HttpClient supplied by the caller. Knows about the client
// core, never about a particular engine — which engine can carry HTTPS is a property of the
// target, and on linuxX64 there is exactly one that can.

kotlin {
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

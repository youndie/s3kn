plugins {
    id("s3kn.kmp")
}

// Signing and presigning. Pure Kotlin: it must build and pass its tests without an HTTP engine
// and without a network, because that is what lets the AWS test vectors run on every target.

kotlin {
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

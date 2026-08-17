plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// An example binary, not a published module: no explicitApi, no jvm target, no tests.
//
// Only linuxX64, and only because that is where the examples have something to show — how a native
// image behaves is the whole point of them.
kotlin {
    jvmToolchain(25)

    linuxX64()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

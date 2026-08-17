plugins {
    id("s3kn.kmp")
}

// Signing and presigning. Pure Kotlin: it must build and pass its tests without an HTTP engine
// and without a network, because that is what lets the AWS test vectors run on every target.
// Fills up in M2 — see BACKLOG.md.

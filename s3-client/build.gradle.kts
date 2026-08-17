plugins {
    id("s3kn.kmp")
}

// The seven operations on top of an HttpClient supplied by the caller. Knows about the client
// core, never about a particular engine.
// Fills up in M4 — see BACKLOG.md.

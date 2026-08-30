plugins {
    base
    alias(libs.plugins.sborkaLint)
    // Declared here with `apply false` so the VERSION is named once, in the catalog, and the modules
    // ask for these by id alone. Asking for a version in a module as well is refused: this project
    // applies `sborka.lint` at the root, which puts the conventions jar on the buildscript classpath
    // every module inherits — and a plugin already on the classpath cannot have its version checked.
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.sborkaKmp) apply false
    alias(libs.plugins.sborkaPublish) apply false
}

// The root used to hold three things: the coordinates, and a ktlint CLI wired in by hand because
// this project wanted exactly version 1.8.0 and exactly its behaviour. The coordinates are now one
// line in `gradle.properties` (`sborka.group`), and `sborka.lint` pins the same 1.8.0 — the
// difference being that it pins it for every repository at once, together with the `.editorconfig`
// the tool reads, which is the other half of what a formatter's behaviour is.

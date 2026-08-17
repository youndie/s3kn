plugins {
    base
}

// The coordinates every module publishes under. Confirmed in the research, decision R1.
group = "io.github.youndie"
version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")

subprojects {
    group = rootProject.group
    version = rootProject.version
}

// ktlint is wired in as a CLI tool rather than through a wrapper plugin: this project wants
// exactly version 1.8.0 and exactly its behaviour.
val ktlint: Configuration = configurations.create("ktlint")

dependencies {
    // The `-all.jar`, requested through artifact-only notation (`:all@jar`): ktlint-cli publishes
    // two variants in its Gradle metadata, and resolving the plain one turns into a fight with the
    // Bundling/Usage attributes — first clikt goes missing (it is runtime-scoped), then
    // kotlin-stdlib (it has KMP variants of its own). `@jar` ignores the metadata and fetches
    // exactly the jar that ships as the CLI.
    ktlint("${libs.ktlint.cli.get().module}:${libs.versions.ktlint.get()}:all@jar")
}

private val ktlintTargets =
    listOf(
        "**/src/**/*.kt",
        "**/*.kts",
        "!build-logic/build/**",
    )

val ktlintCheck =
    tasks.register<JavaExec>("ktlintCheck") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Checks the code style with ktlint as configured in .editorconfig"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        args = ktlintTargets + listOf("--relative")
    }

tasks.register<JavaExec>("ktlintFormat") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Applies the fixes ktlint can make on its own"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args = ktlintTargets + listOf("--relative", "--format")
}

tasks.check {
    dependsOn(ktlintCheck)
}

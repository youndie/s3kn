plugins {
    id("maven-publish")
}

// Where the artefacts go.
//
// Snapshots only, to a private Reposilite. Credentials are never written here: they come from the
// environment, so a checkout of this repository builds and tests but cannot publish.
//
// Nothing is signed. A signature is Maven Central's requirement, not a snapshot repository's, and
// demanding a GPG key for every snapshot would put one into CI for no reason.
publishing {
    repositories {
        maven {
            name = "WipSnapshots"
            url = uri("https://reposilite.kotlin.website/snapshots")
            credentials {
                username = providers.environmentVariable("REPOSILITE_USER").orNull
                password = providers.environmentVariable("REPOSILITE_SECRET").orNull
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set(project.name)
            description.set(
                "S3 client for Kotlin/Native: SigV4 signing, presigned URLs and the object " +
                    "operations, built to run where the AWS SDK does not.",
            )
            inceptionYear.set("2026")
            url.set("https://github.com/youndie/s3kn")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://github.com/youndie/s3kn/blob/main/LICENSE")
                    distribution.set("repo")
                }
            }

            developers {
                developer {
                    id.set("youndie")
                    name.set("Pavel Votyakov")
                    url.set("https://github.com/youndie")
                }
            }

            scm {
                url.set("https://github.com/youndie/s3kn")
                connection.set("scm:git:git://github.com/youndie/s3kn.git")
                developerConnection.set("scm:git:ssh://git@github.com/youndie/s3kn.git")
            }
        }
    }
}

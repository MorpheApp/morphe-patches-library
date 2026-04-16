plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "app.morphe"
base.archivesName = "morphe-extensions-library"

android {
    namespace = "app.morphe.extension.library"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    compileOnly(libs.annotation)
}

afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/MorpheApp/morphe-patches-library")
                credentials {
                    username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR"))
                    password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN"))
                }
            }
        }

        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "app.morphe"
                artifactId = "morphe-extensions-library"
                version = project.version.toString()

                pom {
                    name = "Morphe Extensions Library"
                    description = "Common extension utilities for Morphe patch bundles"
                    url = "https://morphe.software"
                    licenses {
                        license {
                            name = "GNU General Public License v3.0"
                        }
                    }
                    developers {
                        developer {
                            name = "MorpheApp"
                        }
                    }
                    scm {
                        url = "https://github.com/MorpheApp/morphe-patches-library"
                    }
                }
            }
        }
    }
}


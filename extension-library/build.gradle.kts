plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "app.morphe"
base.archivesName = "morphe-extensions-library"

android {
    namespace = "app.morphe.extension.library"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
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


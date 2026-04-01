plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "app.morphe"
base.archivesName = "morphe-patches-library"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11

    withSourcesJar()
    withJavadocJar()
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        freeCompilerArgs = listOf("-Xcontext-receivers")
    }
}

dependencies {
    // Used by JsonGenerator.
    implementation(libs.gson)

    implementation(libs.morphe.patcher)
    implementation(libs.smali)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            groupId = "app.morphe"
            artifactId = "morphe-patches-library"
            version = project.version.toString()

            pom {
                name = "Morphe Patches Library"
                description = "Common patch utilities for Morphe patch bundles"
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

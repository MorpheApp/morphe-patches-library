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
    }
}

val dexInput = configurations.create("dexInput") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // Used by JsonGenerator.
    implementation(libs.gson)

    implementation(libs.morphe.patcher)
    implementation(libs.smali)

    dexInput(project(":extensions:signature", configuration = "dexOutput"))
}

tasks.named<Jar>("jar") {
    dependsOn(":extensions:signature:generateDex")
    from(dexInput) {
        into("generated/extensions")
        rename("classes.dex", "signature.dex")
    }
}

publishing {
    publications {
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
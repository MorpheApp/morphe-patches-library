import com.android.tools.r8.*
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

plugins {
    alias(libs.plugins.android.library)
}

val minApi = 23

android {
    namespace = "app.morphe.extension.signature"
    compileSdk = 36

    defaultConfig {
        minSdk = minApi
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.annotation)
}

val dexOutput = configurations.create("dexOutput") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(dexOutput.name, layout.buildDirectory.dir("outputs/dex").map { it.file("classes.dex") }) {
        builtBy("generateDex")
    }
}


tasks.register("generateDex") {
    group = "build"

    val compileTask = tasks.named<JavaCompile>("compileReleaseJavaWithJavac")

    dependsOn(compileTask)

    val classesDir = compileTask.flatMap { it.destinationDirectory }
    val bootClasspath = androidComponents.sdkComponents.bootClasspath
    val jarFile = layout.buildDirectory.file("intermediates/dex-input/classes-for-dex.jar")
    val outputDir = layout.buildDirectory.dir("outputs/dex")

    doLast {
        val jar = jarFile.get().asFile.apply { parentFile.mkdirs() }
        JarOutputStream(jar.outputStream()).use { jos ->
            classesDir.get().asFile.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(classesDir.get().asFile).invariantSeparatorsPath
                jos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(jos) }
                jos.closeEntry()
            }
        }

        val outDir = outputDir.get().asFile.apply { mkdirs() }
        val command = D8Command.builder()
            .addProgramFiles(jar.toPath())
            .addLibraryFiles(bootClasspath.get().map { it.asFile.toPath() })
            .setOutput(outDir.toPath(), OutputMode.DexIndexed)
            .setMinApiLevel(minApi)
            .build()

        D8.run(command)
    }
}
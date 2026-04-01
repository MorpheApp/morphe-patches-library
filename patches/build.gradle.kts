group = "app.morphe"

patches {
    about {
        name = "Morphe Patches Library"
        description = "Common utilities for Morphe patch bundles"
        source = "git@github.com:MorpheApp/morphe-patches-library.git"
        author = "MorpheApp"
        contact = "na"
        website = "https://morphe.software"
        license = "GNU General Public License v3.0, with additional GPL section 7 requirements"
    }
}

dependencies {
    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-receivers")
    }
}


plugins {
    id("com.android.application")
}

// The app ships the same viewer page and sample sessions as the Termux tool.
// They are copied in from the repo root at build time, so there is one copy to maintain.
abstract class CopyTsvAssets : DefaultTask() {
    @get:InputFile abstract val template: RegularFileProperty
    @get:InputDirectory abstract val examples: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val ex = File(out, "examples").apply { mkdirs() }
        template.get().asFile.copyTo(File(out, "TSViewer.html"), overwrite = true)
        examples.get().asFile.listFiles { f -> f.name.endsWith(".jsonl") }
            ?.forEach { it.copyTo(File(ex, it.name), overwrite = true) }
    }
}

val copyTsvAssets = tasks.register<CopyTsvAssets>("copyTsvAssets") {
    template.set(rootProject.layout.projectDirectory.file("../TSViewer.html"))
    examples.set(rootProject.layout.projectDirectory.dir("../examples"))
    outputDir.set(layout.buildDirectory.dir("generated/tsvAssets"))
}

val keystorePath: String? = System.getenv("TSV_KEYSTORE")

android {
    namespace = "com.vecbandz.tsviewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vecbandz.tsviewer"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0.0"
    }

    signingConfigs {
        create("upload") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("TSV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TSV_KEY_ALIAS")
                keyPassword = System.getenv("TSV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyTsvAssets, CopyTsvAssets::outputDir)
    }
}

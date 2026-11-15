import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile as CompressZipFile
import java.util.zip.ZipFile as JdkZipFile

buildscript {
    repositories { mavenCentral() }
    // Rewrites the bundled Python zip keeping unix symlinks, which java.util.zip cannot write.
    // AGP pulls in an older commons-compress, so only APIs present in both are used below.
    dependencies { classpath("org.apache.commons:commons-compress:1.27.1") }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.salvami.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.salvami.app"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number, so every published build can update the previous one.
        versionCode = System.getenv("SALVAMI_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("SALVAMI_VERSION_NAME") ?: "1.0.0"
    }
    // Release signing comes from the environment (GitHub secrets in CI); without it, the debug key.
    val keystore = System.getenv("SALVAMI_KEYSTORE")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("SALVAMI_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SALVAMI_KEY_ALIAS")
            keyPassword = System.getenv("SALVAMI_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    // python is ~14 MB per ABI: build one APK per architecture instead of a fat one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
    buildFeatures { compose = true }
    // yt-dlp / python ship as native libs that must be extracted to disk.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Our stripped copy (see stripPython) comes first and replaces the library's one.
            pickFirsts += "**/libpython.zip.so"
        }
    }
}

val youtubedlAndroid = "0.18.1"
val pythonAar: Configuration by configurations.creating { isTransitive = false }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    pythonAar("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid@aar")
}

/**
 * youtubedl-android bundles a full CPython (libpython.zip.so, unpacked on first run). Drop what
 * yt-dlp never uses here: 14.1 -> 9.3 MB in the APK, 41.7 -> 27.9 MB unpacked. The library
 * re-unpacks Python whenever the zip size changes, so app updates pick this up by themselves.
 */
abstract class StripPython : DefaultTask() {
    @get:InputFiles abstract val aar: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    private val drop = listOf(
        Regex("""/quickjs/.*\.a$"""), // static library: QuickJS runs from libqjs.so
        Regex("""/site-packages/(Cryptodome|mutagen)/"""), // yt-dlp has pure-Python AES; no tag embedding
        Regex("""(libsqlite3|/_sqlite3\.|/sqlite3/)"""), // only for --cookies-from-browser
        Regex("""(libncurses|libformw|libmenuw|libpanelw|libreadline|libhistory|libgdbm|/_curses|/readline\.cpython|/_gdbm|/_dbm\.|/curses/|/etc/inputrc)"""),
        Regex("""(/lib-dynload/_test|/_xxtest|/python3\.\d+/(test|unittest|idlelib|tkinter|turtledemo|ensurepip|lib2to3|pydoc_data|venv)/|/python3\.\d+/(pydoc|doctest|turtle)\.py$)"""),
    )

    @TaskAction
    fun strip() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        JdkZipFile(aar.singleFile).use { aarZip ->
            for (entry in aarZip.entries()) {
                val abi = Regex("""^jni/([^/]+)/libpython\.zip\.so$""").find(entry.name)?.groupValues?.get(1) ?: continue
                val original = File(temporaryDir, "$abi.zip")
                aarZip.getInputStream(entry).use { input -> original.outputStream().use { input.copyTo(it) } }
                val target = File(out, "$abi/libpython.zip.so").apply { parentFile.mkdirs() }
                @Suppress("DEPRECATION") CompressZipFile(original).use { zip -> rewrite(zip, target) }
            }
        }
    }

    private fun rewrite(zip: CompressZipFile, target: File) {
        val entries = zip.entries.toList()
        val removed = entries.filter { e -> drop.any { it.containsMatchIn(e.name) } }.map { it.name }.toMutableSet()
        // Drop symlinks left pointing at removed files (e.g. libcurses.so -> libncursesw.so.6.5).
        val links = entries.filter { it.isUnixSymlink }.associate { e ->
            e.name to e.name.substringBeforeLast('/') + "/" + zip.getInputStream(e).use { it.readBytes().decodeToString() }
        }
        while (true) {
            val dangling = links.filter { (name, to) -> name !in removed && to in removed }.keys
            if (dangling.isEmpty()) break
            removed += dangling
        }
        ZipArchiveOutputStream(target).use { zos ->
            for (e in entries) {
                if (e.name in removed) continue
                zip.getRawInputStream(e).use { zos.addRawArchiveEntry(ZipArchiveEntry(e), it) }
            }
        }
    }
}

val stripPython = tasks.register<StripPython>("stripPython") {
    aar.from(pythonAar)
    outputDir.set(layout.buildDirectory.dir("generated/python-jni"))
}

androidComponents {
    onVariants { variant -> variant.sources.jniLibs?.addGeneratedSourceDirectory(stripPython, StripPython::outputDir) }
}

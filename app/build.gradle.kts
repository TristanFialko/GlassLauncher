import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glasslauncher"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.glasslauncher"
        minSdk = 23
        targetSdk = 30
        versionCode = 7
        versionName = "2.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // One APK, no product flavors. Kotlin + Rust only; NativeHomeActivity is the sole HOME / Leanback entry.
    // ABIs: libglass.so is packaged for armeabi-v7a + arm64-v8a (glass.rustAbis, see the Rust section below).
    // android.util.Log / android.os.Trace are stubs on the host JVM; RustCore only logs/traces.
    testOptions { unitTests.isReturnDefaultValues = true }
    // Sideloaded onto Android 11 boxes, not published on Google Play; targetSdk 30 is deliberate (see MIGRATION_STATUS.md).
    lint { disable += "ExpiredTargetSdkVersion" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar's org.json is a stub on the JVM
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}

// ---------------------------------------------------------------------------------------------------------------------
// Rust core (rust/) -> libglass.so, packaged per ABI. Formerly the Groovy script app/rust.gradle; migrated to Kotlin DSL
// in N7 with the same task names, properties and outputs.
//
// Properties (gradle.properties, -P, or ~/.gradle/gradle.properties):
//   glass.rust=false              build without the native core (Search uses its Kotlin fallback)
//   glass.rustAbis=a,b            ABIs to build (default armeabi-v7a,arm64-v8a; add x86_64 for emulators)
//   glass.cargo=/path/to/cargo    cargo executable if it is not on PATH
//   glass.rustProfile=release     cargo profile (release|dev)
// ---------------------------------------------------------------------------------------------------------------------

abstract class CargoBuildTask : DefaultTask() {
    @get:Inject abstract val execOps: ExecOperations
    @get:Inject abstract val fsOps: FileSystemOperations

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal abstract val rustDir: DirectoryProperty
    @get:Input abstract val cargo: Property<String>
    @get:Input abstract val profile: Property<String>

    /** abi -> rust target triple; empty map = build for the host (JVM unit tests) */
    @get:Input abstract val targets: MapProperty<String, String>

    /** abi -> absolute path of the NDK clang used as linker */
    @get:Input abstract val linkers: MapProperty<String, String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val rust = rustDir.get().asFile
        val prof = profile.get()
        val profDir = if (prof == "dev") "debug" else prof
        val outRoot = outputDir.get().asFile
        fsOps.delete { delete(outRoot) }
        outRoot.mkdirs()
        val targetMap = targets.get()
        if (targetMap.isEmpty()) {
            runCargo(rust, listOf(cargo.get(), "build", "--locked", "-p", "glass-jni", "--profile", prof), emptyMap())
            val dir = File(rust, "target/$profDir")
            fsOps.copy {
                from(dir) { include("libglass.so", "libglass.dylib", "glass.dll") }
                into(outRoot)
            }
            return
        }
        val linkerMap = linkers.get()
        for ((abi, triple) in targetMap) {
            val key = "CARGO_TARGET_" + triple.uppercase().replace("-", "_") + "_LINKER"
            val linker = linkerMap[abi] ?: throw GradleException("No NDK linker configured for $abi")
            runCargo(
                rust,
                listOf(cargo.get(), "build", "--locked", "-p", "glass-jni", "--profile", prof, "--target", triple),
                mapOf(key to linker),
            )
            val so = File(rust, "target/$triple/$profDir/libglass.so")
            if (!so.isFile) throw GradleException("Rust build for $abi did not produce $so")
            fsOps.copy {
                from(so)
                into(File(outRoot, abi))
            }
        }
    }

    private fun runCargo(dir: File, cmd: List<String>, env: Map<String, String>) {
        try {
            execOps.exec {
                workingDir = dir
                commandLine(cmd)
                environment(env)
            }
        } catch (e: Exception) {
            throw GradleException(
                "Rust build failed (${cmd.joinToString(" ")}: ${e.message}). Install Rust (https://rustup.rs) and the Android targets " +
                    "(rustup target add armv7-linux-androideabi aarch64-linux-android), or build without the native core using -Pglass.rust=false.",
                e,
            )
        }
    }
}

val rustEnabled = (findProperty("glass.rust") ?: "true").toString() == "true"
val rustAbis = (findProperty("glass.rustAbis") ?: "armeabi-v7a,arm64-v8a").toString()
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
val cargoExe = (findProperty("glass.cargo") ?: "cargo").toString()
val rustProfile = (findProperty("glass.rustProfile") ?: "release").toString()
val rustRoot: Directory = rootProject.layout.projectDirectory.dir("rust")
val rustSources = rootProject.fileTree("rust") {
    include("**/*.rs", "**/Cargo.toml", "Cargo.lock", "rust-toolchain.toml")
    exclude("target/**")
}

val rustTriples = mapOf(
    "armeabi-v7a" to "armv7-linux-androideabi",
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android",
)
val clangPrefixes = mapOf(
    "armeabi-v7a" to "armv7a-linux-androideabi",
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android",
)
rustAbis.forEach { if (it !in rustTriples) throw GradleException("Unsupported glass.rustAbis entry '$it'") }

val hostOs = System.getProperty("os.name").lowercase()
val isWindows = hostOs.contains("windows")
val ndkHost = when {
    isWindows -> "windows-x86_64"
    hostOs.contains("mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val androidExt = android

val cargoAndroid = tasks.register<CargoBuildTask>("cargoBuildAndroid") {
    group = "build"
    description = "Builds libglass.so for the configured Android ABIs."
    enabled = rustEnabled
    sources.from(rustSources)
    rustDir.set(rustRoot)
    cargo.set(cargoExe)
    profile.set(rustProfile)
    targets.set(rustAbis.associateWith { rustTriples.getValue(it) })
    linkers.set(
        project.provider {
            val minSdk = androidExt.defaultConfig.minSdk
            val bin = File(androidExt.ndkDirectory, "toolchains/llvm/prebuilt/$ndkHost/bin")
            val suffix = if (isWindows) ".cmd" else ""
            rustAbis.associateWith { File(bin, "${clangPrefixes.getValue(it)}$minSdk-clang$suffix").absolutePath }
        },
    )
    outputDir.set(project.layout.buildDirectory.dir("rust/jniLibs"))
}

val cargoHost = tasks.register<CargoBuildTask>("cargoBuildHost") {
    group = "verification"
    description = "Builds libglass for this computer so JVM unit tests can exercise the real JNI layer."
    enabled = rustEnabled
    sources.from(rustSources)
    rustDir.set(rustRoot)
    cargo.set(cargoExe)
    profile.set(rustProfile)
    targets.set(emptyMap<String, String>())
    linkers.set(emptyMap<String, String>())
    outputDir.set(project.layout.buildDirectory.dir("rust/host"))
}

if (rustEnabled) {
    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoAndroid, CargoBuildTask::outputDir)
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("glass.expectNative", rustEnabled.toString())
    if (rustEnabled) {
        dependsOn(cargoHost)
        systemProperty("java.library.path", cargoHost.flatMap { it.outputDir }.get().asFile.absolutePath)
    }
}

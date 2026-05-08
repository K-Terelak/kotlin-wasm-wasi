@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.internal.os.OperatingSystem
import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.internal.file.archive.compression.*
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.testing.internal.KotlinTestReport
import java.io.*
import java.net.*
import java.util.Locale

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.undercouchDownload) apply false
}

buildscript {
    dependencies {
        // to extract `tar.xz`
        classpath("org.tukaani:xz:1.10")
    }
}

repositories {
    mavenCentral()
}

kotlin {
    wasmWasi {
        nodejs()
        binaries.executable()
    }
}

// Uncomment the following block to turn off using the Exception Handling proposal.
// Note, with this option, the compiler will generate `unreachable` instruction instead of throw,
// and a Wasm module will stop execution in this case.
//
// tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile>().configureEach {
//     compilerOptions.freeCompilerArgs.addAll(listOf("-Xwasm-use-traps-instead-of-exceptions"))
// }

// Uncomment the following block to force using the old version of the Exception Handling proposal.
//
// tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile>().configureEach {
//     compilerOptions.freeCompilerArgs.addAll(listOf("-Xwasm-use-new-exception-proposal=false"))
// }

enum class OsName { WINDOWS, MAC, LINUX, UNKNOWN }
enum class OsArch { X86_32, X86_64, ARM64, UNKNOWN }
data class OsType(val name: OsName, val arch: OsArch)

val currentOsType = run {
    val gradleOs = OperatingSystem.current()
    val osName = when {
        gradleOs.isMacOsX -> OsName.MAC
        gradleOs.isWindows -> OsName.WINDOWS
        gradleOs.isLinux -> OsName.LINUX
        else -> OsName.UNKNOWN
    }

    val osArch = when (providers.systemProperty("sun.arch.data.model").get()) {
        "32" -> OsArch.X86_32
        "64" -> when (providers.systemProperty("os.arch").get().lowercase(Locale.getDefault())) {
            "aarch64" -> OsArch.ARM64
            else -> OsArch.X86_64
        }
        else -> OsArch.UNKNOWN
    }

    OsType(osName, osArch)
}


// Wasmtime tasks
val wasmtimeVersion = "40.0.0"

val wasmtimeSuffix = when (currentOsType) {
    OsType(OsName.LINUX, OsArch.X86_64)   -> "x86_64-linux"
    OsType(OsName.LINUX, OsArch.ARM64)    -> "aarch64-linux"
    OsType(OsName.MAC, OsArch.X86_64)     -> "x86_64-macos"
    OsType(OsName.MAC, OsArch.ARM64)      -> "aarch64-macos"
    OsType(OsName.WINDOWS, OsArch.X86_32),
    OsType(OsName.WINDOWS, OsArch.X86_64) -> "x86_64-windows"

    else                                  -> error("unsupported os type $currentOsType")
}

val wasmtimeArtifactName = "wasmtime-v$wasmtimeVersion-$wasmtimeSuffix"

val unzipWasmtime = run {
    val wasmtimeDirectory = "https://github.com/bytecodealliance/wasmtime/releases/download/v$wasmtimeVersion"
    val archiveType = if (currentOsType.name == OsName.WINDOWS) "zip" else "tar.xz"
    val wasmtimeArchiveName = "$wasmtimeArtifactName.$archiveType"
    val wasmtimeLocation = "$wasmtimeDirectory/$wasmtimeArchiveName"

    val downloadedTools = File(layout.buildDirectory.asFile.get(), "tools")

    val downloadWasmtime = tasks.register("wasmtimeDownload", Download::class) {
        src(wasmtimeLocation)
        dest(File(downloadedTools, wasmtimeArchiveName))
        overwrite(false)
    }

    tasks.register("wasmtimeUnzip", Copy::class) {
        dependsOn(downloadWasmtime)

        val archive = downloadWasmtime.get().dest

        from(if (archive.extension == "zip") zipTree(archive) else tarTree(XzArchiver(archive)))

        into(downloadedTools.resolve(wasmtimeArtifactName))
    }
}

private class XzArchiver(private val file: File) : CompressedReadableResource {
    override fun read(): InputStream = org.tukaani.xz.XZInputStream(file.inputStream().buffered())
    override fun getURI(): URI = URIBuilder(file.toURI()).schemePrefix("xz:").build()
    override fun getBackingFile(): File = file
    override fun getBaseName(): String = file.name
    override fun getDisplayName(): String = file.path
}

fun Project.createWasmtimeExec(
    nodeMjsFile: RegularFileProperty,
    taskName: String,
    taskGroup: String?,
    startFunction: String
): TaskProvider<Exec> {
    val outputDirectory = nodeMjsFile.map { it.asFile.parentFile }
    val wasmFileName = nodeMjsFile.map { "${it.asFile.nameWithoutExtension}.wasm" }

    return tasks.register(taskName, Exec::class) {
        dependsOn(unzipWasmtime)
        inputs.property("wasmFileName", wasmFileName)

        taskGroup?.let { group = it }

        description = "Executes tests with Wasmtime"

        val wasmtimeDirectory = unzipWasmtime.get().destinationDir.resolve(wasmtimeArtifactName)

        val executableName = when (currentOsType.name) {
            OsName.WINDOWS -> "wasmtime.exe"
            else           -> "wasmtime"
        }
        executable = wasmtimeDirectory.resolve(executableName).absolutePath

        doFirst {
            val newArgs = mutableListOf<String>()

            newArgs.add("-W")
            newArgs.add("function-references,gc,exceptions")


            newArgs.add("--invoke")
            newArgs.add(startFunction)

            newArgs.add(wasmFileName.get())

            args(newArgs)
            workingDir(outputDirectory)

            // to show stacktraces
            environment("RUST_BACKTRACE", "full")
        }
    }
}

tasks.withType<KotlinJsTest>().all {
    val wasmtimeRunTask = createWasmtimeExec(
        inputFileProperty,
        name.replace("Node", "Wasmtime"),
        group,
        "startUnitTests"
    )

    wasmtimeRunTask.configure {
        dependsOn(
            project.provider { this@all.taskDependencies }
        )
    }

    tasks.withType<KotlinTestReport> {
        dependsOn(wasmtimeRunTask)
    }
}

tasks.register("runWasm", Exec::class) {
    dependsOn(unzipWasmtime, "compileDevelopmentExecutableKotlinWasmWasi")

    group = "run"
    description = "Compiles the Wasm/WASI binary and runs it with Wasmtime"

    standardInput = System.`in`

    val wasmtimeDir = unzipWasmtime.get().destinationDir.resolve(wasmtimeArtifactName)
    executable = wasmtimeDir.resolve(if (currentOsType.name == OsName.WINDOWS) "wasmtime.exe" else "wasmtime").absolutePath

    doFirst {
        val execDir = layout.buildDirectory
            .dir("compileSync/wasmWasi/main/developmentExecutable/kotlin")
            .get().asFile

        val wasmFile = execDir.listFiles { file ->
            file.extension == "wasm"
        }.firstOrNull()
        args("-W", "function-references,gc,exceptions", wasmFile?.absolutePath)
    }
}

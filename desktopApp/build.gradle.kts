import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.conveyor)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

version = providers.gradleProperty("app.versionName").getOrElse("1.0.0")

kotlin {
    jvmToolchain(21)
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
    }

    sourceSets {
        val jvmMain = getByName("jvmMain") {
            resources.srcDir(rootProject.file("app-resources"))
            dependencies {
                implementation(projects.composeApp)
                implementation(projects.core.common)
                implementation(projects.core.domain)
                implementation(projects.core.data)
                implementation(projects.proxyServer)
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.koin.core)
                implementation(libs.slf4j.api)
                implementation(libs.logback.classic)
                implementation(libs.favre.bcrypt)
                implementation(libs.webcam.capture)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

dependencies {
    // Only the host Linux target is needed for the Compose Desktop AppImage/tar.gz
    // release pipeline; non-host targets add needless runtime resolution overhead.
    linuxAmd64(compose.desktop.linux_x64)
}

tasks.named<hydraulic.conveyor.gradle.WriteConveyorConfigTask>("writeConveyorConfig") {
    doLast {
        val v = providers.gradleProperty("app.versionName").getOrElse("1.0.0")
        destination.get().asFile.appendText(
            """
            |app.version = $v
            |app.fsname = tetuko
            |app.display-name = TETUKO
            |app.rdns-name = id.tetuko.app
            |
            """.trimMargin()
        )
    }
}

compose.desktop {
    application {
        mainClass = "id.tetuko.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "TETUKO"
            packageVersion = providers.gradleProperty("app.versionName").getOrElse("1.0.0")
            description = "TETUKO - Teknologi Terpadu Komprehensif"
            vendor = "TETUKO"

            linux {
                iconFile.set(rootProject.file("app-resources/icons/icon.png"))
            }
            windows {
                iconFile.set(rootProject.file("app-resources/icons/icon.ico"))
            }
        }

        // Mirrors the Tauri release profile (opt-level="z", lto, strip=symbols):
        // R8 shrinks/optimizes bytecode (notably unused Material icons), native
        // symbols are stripped by slimDesktopDistribution below.
        //
        // ProGuard/R8 shrink is enabled for release distributions. Rules keep the
        // desktop entry point, application/domain/data model graph, Ktor, ONNX,
        // serialization, logging, and Compose runtime internals used by reflection.
        buildTypes.release.proguard {
            isEnabled.set(true)
            configurationFiles.from(file("proguard-rules.pro"))
        }
    }
}

tasks.register("slimDesktopDistribution") {
    group = "distribution"
    description = "Strip native symbols and drop non-Linux ONNX runtimes from the packaged app."
    dependsOn("packageReleaseAppImage")
    notCompatibleWithConfigurationCache("Post-processes build outputs in place")
    doLast {
        val releaseDir = layout.buildDirectory.dir("compose/binaries/main-release/app/TETUKO").get().asFile
        val mainDir = layout.buildDirectory.dir("compose/binaries/main/app/TETUKO").get().asFile
        val appRoot = if (releaseDir.exists()) releaseDir else mainDir
        val appLib = File(appRoot, "lib/app")
        val onnx = appLib.listFiles()?.firstOrNull {
            it.name.startsWith("onnxruntime-") && it.name.endsWith(".jar")
        }
        if (onnx != null && onnx.exists()) {
            val slim = File(onnx.parentFile, onnx.name + ".slim")
            val skip = listOf(
                "ai/onnxruntime/native/win/",
                "ai/onnxruntime/native/osx/",
                "linux-aarch64",
                ".pdb",
                ".dSYM",
            )
            ZipFile(onnx).use { zin ->
                ZipOutputStream(slim.outputStream().buffered()).use { zout ->
                    zout.setLevel(Deflater.BEST_COMPRESSION)
                    zin.entries().toList().forEach { entry ->
                        if (skip.any { entry.name.contains(it) }) return@forEach
                        var bytes = zin.getInputStream(entry).readBytes()
                        if (entry.name.endsWith(".so")) {
                            val tmpSo = File.createTempFile("onnx-native", ".so")
                            tmpSo.writeBytes(bytes)
                            val ok = ProcessBuilder("strip", "--strip-unneeded", tmpSo.absolutePath)
                                .redirectErrorStream(true).start().waitFor() == 0
                            if (ok) bytes = tmpSo.readBytes()
                            tmpSo.delete()
                        }
                        val outEntry = ZipEntry(entry.name).apply { time = entry.time }
                        zout.putNextEntry(outEntry)
                        zout.write(bytes)
                        zout.closeEntry()
                    }
                }
            }
            check(slim.length() < onnx.length()) { "Slim ONNX jar is not smaller; aborting" }
            println("[slim] onnxruntime: ${onnx.length() / 1024}K -> ${slim.length() / 1024}K")
            onnx.delete()
            check(slim.renameTo(onnx)) { "Failed to replace slim ONNX jar" }
        }
        appRoot.walkTopDown().filter { it.isFile && (it.name.endsWith(".so") || it.name == "TETUKO") }
            .forEach { file ->
                val before = file.length()
                val proc = ProcessBuilder("strip", "--strip-unneeded", file.absolutePath)
                    .redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().readText()
                val exit = proc.waitFor()
                if (exit == 0 && file.length() < before) {
                    println("[slim] ${file.name}: ${before / 1024}K -> ${file.length() / 1024}K")
                }
            }
        // Bundle runtime .env (generated from CI env/secrets/vars) into the app
        // so DesktopEnv can load it at runtime without hardcoded values in source.
        val envFile = rootProject.file(".env")
        if (envFile.exists()) {
            val dest = File(appRoot, ".env")
            envFile.copyTo(dest, overwrite = true)
            println("[env] Bundled runtime .env into app image")
        }
    }
}

// Wrap the Compose app-image directory into a portable AppImage.
tasks.register("packageComposeAppImage") {
    group = "distribution"
    description = "Wrap the Compose Desktop app-image directory into a real AppImage."
    dependsOn("slimDesktopDistribution")
    notCompatibleWithConfigurationCache("Downloads and executes appimagetool at execution time")
    doLast {
        val appName = "TETUKO"
        val releaseDir = layout.buildDirectory.dir("compose/binaries/main-release/app/TETUKO").get().asFile
        val mainDir = layout.buildDirectory.dir("compose/binaries/main/app/TETUKO").get().asFile
        val appDirSource = if (releaseDir.exists()) releaseDir else mainDir
        if (!appDirSource.resolve("bin/TETUKO").exists()) {
            throw GradleException("Compose app-image not found at ${appDirSource}. Run :desktopApp:packageReleaseAppImage first.")
        }
        val tmpDir = layout.buildDirectory.dir("tmp").get().asFile
        val appimagetool = tmpDir.resolve("appimagetool-x86_64.AppImage")
        if (!appimagetool.exists()) {
            tmpDir.mkdirs()
            uri("https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage")
                .toURL().openStream().use { input -> appimagetool.outputStream().use { input.copyTo(it) } }
        }
        appimagetool.setExecutable(true)

        val appDir = layout.buildDirectory.dir("appimage/$appName.AppDir").get().asFile
        if (appDir.exists()) appDir.deleteRecursively()
        appDir.mkdirs()
        appDirSource.copyRecursively(appDir)

        val iconSrc = rootProject.file("app-resources/icons/icon.png")
        val iconDst = appDir.resolve("tetuko.png")
        if (iconSrc.exists()) iconSrc.copyTo(iconDst, overwrite = true)

        appDir.resolve("tetuko.desktop").writeText("""
            [Desktop Entry]
            Type=Application
            Version=1.0
            Name=TETUKO
            Comment=TETUKO - Teknologi Terpadu Komprehensif
            Exec=bin/TETUKO %u
            Icon=tetuko
            Terminal=false
            Categories=Office;Network;Utility;
            StartupWMClass=TETUKO
            MimeType=x-scheme-handler/tetuko;
        """.trimIndent() + "\n")

        val appRun = appDir.resolve("AppRun")
        appRun.writeText("""
            #!/bin/sh
            SELF=${'$'}(readlink -f "${'$'}0")
            HERE=${'$'}{SELF%/*}
            ICON_DIR="${'$'}HOME/.local/share/icons/hicolor/256x256/apps"
            if [ ! -f "${'$'}ICON_DIR/tetuko.png" ]; then
                mkdir -p "${'$'}ICON_DIR"
                cp "${'$'}HERE/tetuko.png" "${'$'}ICON_DIR/tetuko.png" 2>/dev/null || true
            fi
            DESKTOP_DIR="${'$'}HOME/.local/share/applications"
            mkdir -p "${'$'}DESKTOP_DIR"
            APPIMAGE_PATH="${'$'}{APPIMAGE:-${'$'}SELF}"
            sed "s|Exec=bin/TETUKO|Exec=${'$'}APPIMAGE_PATH|" "${'$'}HERE/tetuko.desktop" > "${'$'}DESKTOP_DIR/id.tetuko.app.desktop" 2>/dev/null || true
            update-desktop-database "${'$'}DESKTOP_DIR" 2>/dev/null || true
            export MALLOC_ARENA_MAX=2
            cd "${'$'}HERE"
            exec bin/TETUKO "${'$'}@"
        """.trimIndent() + "\n")
        appRun.setExecutable(true)
        appDir.resolve("bin/TETUKO").setExecutable(true)
        // appimagetool rejects modes that are not world-readable/executable.
        ProcessBuilder("chmod", "-R", "a+rX", appDir.absolutePath)
            .inheritIO().start().waitFor()

        val outImage = appDir.parentFile.resolve("$appName-x86_64.AppImage")
        val pb = ProcessBuilder(appimagetool.absolutePath, "$appName.AppDir", outImage.name)
            .directory(appDir.parentFile)
            .inheritIO()
        pb.environment()["ARCH"] = "x86_64"
        // Works both with FUSE installed (CI) and without it (local/headless).
        pb.environment()["APPIMAGE_EXTRACT_AND_RUN"] = "1"
        val exit = pb.start().waitFor()
        if (exit != 0) throw GradleException("appimagetool failed: $exit")
        println("[AppImage] Built: ${outImage.absolutePath}")
    }
}

tasks.register("packageConveyorAppImage") {
    group = "distribution"
    description = "Wrap Conveyor linux-app output into AppImage."
    notCompatibleWithConfigurationCache("Reads project/layout at execution time")
    doLast {
        val appName = "TETUKO"
        val conveyorOutput = rootDir.resolve("output")
        if (!conveyorOutput.exists()) {
            throw GradleException("Conveyor output ./output not found. Run conveyor make linux-app first.")
        }
        val tmpDir = layout.buildDirectory.dir("tmp").get().asFile
        val appimagetool = tmpDir.resolve("appimagetool-x86_64.AppImage")
        if (!appimagetool.exists()) {
            tmpDir.mkdirs()
            uri("https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage")
                .toURL().openStream().use { input -> appimagetool.outputStream().use { input.copyTo(it) } }
        }
        appimagetool.setExecutable(true)

        val appDir = layout.buildDirectory.dir("appimage/$appName.AppDir").get().asFile
        if (appDir.exists()) appDir.deleteRecursively()
        copy { from(conveyorOutput); into(appDir) }

        val iconSrc = rootProject.file("app-resources/icons/icon.png")
        val iconDst = appDir.resolve("tetuko.png")
        if (iconSrc.exists()) iconSrc.copyTo(iconDst, overwrite = true)

        val desktopFile = appDir.resolve("tetuko.desktop")
        desktopFile.writeText("""
            [Desktop Entry]
            Type=Application
            Version=1.0
            Name=TETUKO
            Comment=TETUKO - Teknologi Terpadu Komprehensif
            Exec=bin/tetuko %u
            Icon=tetuko
            Terminal=false
            Categories=Office;Network;Utility;
            StartupWMClass=TETUKO
            MimeType=x-scheme-handler/tetuko;
        """.trimIndent() + "\n")

        val appRun = appDir.resolve("AppRun")
        appRun.writeText("""
            #!/bin/sh
            SELF=${'$'}(readlink -f "${'$'}0")
            HERE=${'$'}{SELF%/*}

            # Install icon into XDG dirs so GNOME/KDE pick it up on first run.
            ICON_DIR="${'$'}HOME/.local/share/icons/hicolor/256x256/apps"
            if [ ! -f "${'$'}ICON_DIR/tetuko.png" ] || [ "${'$'}HERE/tetuko.png" -nt "${'$'}ICON_DIR/tetuko.png" ]; then
                mkdir -p "${'$'}ICON_DIR"
                cp "${'$'}HERE/tetuko.png" "${'$'}ICON_DIR/tetuko.png"
                gtk-update-icon-cache -f -t "${'$'}HOME/.local/share/icons/hicolor" 2>/dev/null || true
            fi

            # Install .desktop with the absolute AppImage path (protocol handler + menu entry).
            DESKTOP_DIR="${'$'}HOME/.local/share/applications"
            mkdir -p "${'$'}DESKTOP_DIR"
            APPIMAGE_PATH="${'$'}{APPIMAGE:-${'$'}SELF}"
            sed "s|Exec=bin/tetuko|Exec=${'$'}APPIMAGE_PATH|" "${'$'}HERE/tetuko.desktop" > "${'$'}DESKTOP_DIR/id.tetuko.app.desktop"
            update-desktop-database "${'$'}DESKTOP_DIR" 2>/dev/null || true

            # Cap glibc per-thread malloc arenas (keeps idle RSS predictable).
            export MALLOC_ARENA_MAX=2
            cd "${'$'}HERE"
            exec bin/tetuko "${'$'}@"
        """.trimIndent() + "\n")
        appRun.setExecutable(true)

        val bin = appDir.resolve("bin/tetuko")
        if (bin.exists()) bin.setExecutable(true)

        val outImage = appDir.parentFile.resolve("$appName-x86_64.AppImage")
        val pb = ProcessBuilder(appimagetool.absolutePath, "$appName.AppDir", outImage.name)
            .directory(appDir.parentFile)
            .inheritIO()
        pb.environment()["ARCH"] = "x86_64"
        val exit = pb.start().waitFor()
        if (exit != 0) throw GradleException("appimagetool failed: $exit")
        println("[AppImage] Built: ${outImage.absolutePath}")
    }
}

val conveyorMakeLinuxApp = tasks.register<Exec>("conveyorMakeLinuxApp") {
    group = "distribution"
    description = "Run conveyor make linux-app for Linux x86_64"
    dependsOn(":desktopApp:writeConveyorConfig")
    workingDir = rootDir
    commandLine("conveyor", "--agree-to-license=1", "-Kapp.machines=linux.amd64.glibc", "make", "linux-app")
}

tasks.register("buildLinuxAppImage") {
    group = "distribution"
    description = "Build Linux AppImage (conveyor linux-app + wrap)"
    dependsOn(conveyorMakeLinuxApp)
    finalizedBy("packageConveyorAppImage")
}

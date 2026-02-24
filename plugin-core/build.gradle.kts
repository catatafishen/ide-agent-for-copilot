plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Use the local IDE installation (configured via intellijPlatform.localPath in gradle.properties)
        local(providers.gradleProperty("intellijPlatform.localPath"))
        instrumentationTools()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    // Kotlin stdlib for UI layer
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Force annotations version to match the platform (TYPE_USE support required)
    implementation("org.jetbrains:annotations:26.0.2")

    // JSON processing (Gson)
    implementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")

    testImplementation("org.junit.jupiter:junit-jupiter:${providers.gradleProperty("junitVersion").get()}")
    testImplementation(
        "junit:junit:${
            providers.gradleProperty("junit4Version").get()
        }"
    )  // Required by IntelliJ test framework
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:${providers.gradleProperty("junitVersion").get()}")
}

// Copy MCP server JAR into plugin lib for bundling
tasks.named("prepareSandbox") {
    dependsOn(project(":mcp-server").tasks.named("jar"))
    doLast {
        val mcpJar = project(":mcp-server").tasks.named("jar").get().outputs.files.singleFile
        // Copy to the versioned sandbox directory where the IDE actually runs
        val ideDirs = File(
            layout.buildDirectory.asFile.get(),
            "idea-sandbox"
        ).listFiles { f -> f.isDirectory && f.name.startsWith("IU-") }
        ideDirs?.forEach { ideDir ->
            val sandboxLib = File(ideDir, "plugins/plugin-core/lib")
            sandboxLib.mkdirs()
            mcpJar.copyTo(File(sandboxLib, "mcp-server.jar"), overwrite = true)
        }

        // Restore persisted sandbox config (disabled plugins, settings, etc.)
        val persistentConfig = rootProject.file(".sandbox-config")
        if (persistentConfig.exists() && persistentConfig.isDirectory) {
            ideDirs?.forEach { ideDir ->
                val configDir = File(ideDir, "config")
                configDir.mkdirs()
                persistentConfig.walkTopDown().forEach { src ->
                    if (src.isFile) {
                        val rel = src.relativeTo(persistentConfig)
                        val dest = File(configDir, rel.path)
                        dest.parentFile.mkdirs()
                        src.copyTo(dest, overwrite = true)
                    }
                }
            }
            logger.lifecycle("Restored sandbox config from .sandbox-config/")
        }

        // Restore marketplace-installed plugins (zips/jars in system/plugins/)
        val persistentPlugins = rootProject.file(".sandbox-plugins")
        if (persistentPlugins.exists() && persistentPlugins.isDirectory) {
            ideDirs?.forEach { ideDir ->
                // Extract plugin zips into the plugins/ directory (alongside plugin-core)
                // IntelliJ loads plugins from plugins/, not system/plugins/
                val pluginsDir = File(ideDir, "plugins")
                pluginsDir.mkdirs()
                persistentPlugins.listFiles()?.filter { it.extension == "zip" }?.forEach { zipFile ->
                    val pluginName = zipFile.nameWithoutExtension
                    val extractedDir = File(pluginsDir, pluginName)
                    if (!extractedDir.exists()) {
                        logger.lifecycle("Extracting marketplace plugin: ${zipFile.name}")
                        project.copy {
                            from(project.zipTree(zipFile))
                            into(pluginsDir)
                        }
                    }
                }
                // Copy standalone jars
                persistentPlugins.listFiles()?.filter { it.extension == "jar" }?.forEach { jarFile ->
                    val dest = File(pluginsDir, jarFile.name)
                    if (!dest.exists()) {
                        jarFile.copyTo(dest)
                    }
                }
                // Also keep the zips in system/plugins/ for IntelliJ's plugin manager UI
                val systemPlugins = File(ideDir, "system/plugins")
                systemPlugins.mkdirs()
                persistentPlugins.listFiles()?.filter { it.extension == "zip" || it.extension == "jar" }
                    ?.forEach { src ->
                        val dest = File(systemPlugins, src.name)
                        if (!dest.exists()) {
                            src.copyTo(dest)
                        }
                    }
            }
            logger.lifecycle("Restored marketplace plugins from .sandbox-plugins/")
        }
    }
}

// Also include in the distribution ZIP
tasks.named<Zip>("buildPlugin") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(project(":mcp-server").tasks.named("jar"))
    from(project(":mcp-server").tasks.named("jar")) {
        into("lib")
        rename { "mcp-server.jar" }
    }
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated/buildinfo"))
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.copilot.intellij"
        name = "Copilot Bridge"
        version = project.version.toString()
        description = """
            Lightweight IntelliJ Platform plugin that embeds GitHub Copilot's agent capabilities
            with full context awareness, planning, and Git integration.
        """.trimIndent()

        ideaVersion {
            sinceBuild = "253"
            untilBuild = "253.*"
        }
    }

    publishing {
        token = providers.environmentVariable("INTELLIJ_PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    // Generate build info properties file
    val generateBuildInfo by registering {
        val outputDir = layout.buildDirectory.dir("generated/buildinfo")
        outputs.dir(outputDir)
        outputs.upToDateWhen { false }
        doLast {
            val propsFile = outputDir.get().file("build-info.properties").asFile
            propsFile.parentFile.mkdirs()
            val gitHash = try {
                providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
                    .standardOutput.asText.get().trim()
            } catch (_: Exception) {
                "unknown"
            }
            val timestamp = System.currentTimeMillis().toString()
            propsFile.writeText("build.timestamp=$timestamp\nbuild.git.hash=$gitHash\nbuild.version=${project.version}\n")
        }
    }

    named("processResources") {
        dependsOn(generateBuildInfo)
    }

    withType<Jar> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    withType<JavaCompile> {
        options.compilerArgs.add("-Xlint:deprecation")
    }

    test {
        useJUnitPlatform {
            excludeTags("integration")
        }
    }

    runIde {
        maxHeapSize = "2g"
        // Enable auto-reload of plugin when changes are built
        autoReload = true

        // Auto-open this project in the sandbox IDE (skips welcome screen)
        args = listOf(project.rootDir.absolutePath)

        // System properties to skip setup and preserve state
        jvmArgs = listOf(
            "-Didea.trust.all.projects=true",           // Skip trust dialog
            "-Didea.is.internal=true",                   // Enable internal mode
            "-Deap.require.license=false",               // Skip license checks
            "-Didea.suppressed.plugins.id=",             // Don't suppress any plugins
            "-Didea.plugin.in.sandbox.mode=true"         // Sandbox mode
        )
    }
}

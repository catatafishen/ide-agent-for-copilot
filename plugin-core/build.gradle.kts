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
        // Use your locally installed IDE instead of downloading
        local(providers.gradleProperty("intellijPlatform.localPath"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    // Kotlin stdlib for UI layer
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    
    // JSON processing (Gson)
    implementation("com.google.code.gson:gson:2.13.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("junit:junit:4.13.2")  // Required by IntelliJ test framework
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.1")
}

// Copy MCP server JAR into plugin lib for bundling
tasks.named("prepareSandbox") {
    dependsOn(project(":mcp-server").tasks.named("jar"))
    doLast {
        val mcpJar = project(":mcp-server").tasks.named("jar").get().outputs.files.singleFile
        // Copy to the versioned sandbox directory where the IDE actually runs
        val ideDirs = File(layout.buildDirectory.asFile.get(), "idea-sandbox").listFiles { f -> f.isDirectory && f.name.startsWith("IU-") }
        ideDirs?.forEach { ideDir ->
            val sandboxLib = File(ideDir, "plugins/plugin-core/lib")
            sandboxLib.mkdirs()
            mcpJar.copyTo(File(sandboxLib, "mcp-server.jar"), overwrite = true)
        }
    }
}

// Also include in the distribution ZIP
tasks.named<Zip>("buildPlugin") {
    dependsOn(project(":mcp-server").tasks.named("jar"))
    from(project(":mcp-server").tasks.named("jar")) {
        into("lib")
        rename { "mcp-server.jar" }
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.copilot.intellij"
        name = "Agentic Copilot"
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
    runIde {
        maxHeapSize = "2g"
        // Enable auto-reload of plugin when changes are built
        autoReload = true
    }
}
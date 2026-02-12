plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.1.0"
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
        
        instrumentationTools()
    }

    // Kotlin stdlib for UI layer
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    
    // JSON processing (Gson)
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
        
        // Workaround for ProductInfo bug in Platform Plugin 2.1.0
        // Try to bypass IDE home variable resolution
        jvmArgs = listOf(
            "-Xmx2048m",
            "-Didea.plugins.path=${layout.buildDirectory.dir("idea-sandbox/plugins").get().asFile.absolutePath}",
            "-Didea.system.path=${layout.buildDirectory.dir("idea-sandbox/system").get().asFile.absolutePath}",
            "-Didea.config.path=${layout.buildDirectory.dir("idea-sandbox/config").get().asFile.absolutePath}",
            "-Didea.log.path=${layout.buildDirectory.dir("idea-sandbox/system/log").get().asFile.absolutePath}"
        )
    }

    // Disable searchable options to fix build
    buildSearchableOptions {
        enabled = false
    }

    // Copy sidecar binary into plugin distribution
    val copySidecarBinary by registering(Copy::class) {
        from(layout.projectDirectory.dir("../copilot-bridge/bin"))
        into(layout.buildDirectory.dir("resources/main/bin"))
        include("copilot-sidecar.exe", "copilot-sidecar")
    }

    processResources {
        dependsOn(copySidecarBinary)
    }

    prepareSandbox {
        dependsOn(copySidecarBinary)
        from(layout.projectDirectory.dir("../copilot-bridge/bin")) {
            into("${intellijPlatform.pluginConfiguration.name.get()}/bin")
            include("copilot-sidecar.exe", "copilot-sidecar")
        }
    }
}
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
    }

    // Kotlin stdlib for UI layer
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    
    // JSON processing (Gson)
    implementation("com.google.code.gson:gson:2.13.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("junit:junit:4.13.2")  // Required by IntelliJ test framework
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
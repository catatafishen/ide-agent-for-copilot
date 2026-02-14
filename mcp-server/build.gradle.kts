plugins {
    id("java")
    application
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

application {
    mainClass.set("com.github.copilot.mcp.McpServer")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.github.copilot.mcp.McpServer"
    }
    // Fat JAR — include all dependencies
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

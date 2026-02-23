plugins {
    id("java")
    application
}

dependencies {
    implementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")
    testImplementation("org.junit.jupiter:junit-jupiter:${providers.gradleProperty("junitVersion").get()}")
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

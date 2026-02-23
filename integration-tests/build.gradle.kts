dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:${providers.gradleProperty("junitVersion").get()}")
    testImplementation(project(":plugin-core"))
    testImplementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

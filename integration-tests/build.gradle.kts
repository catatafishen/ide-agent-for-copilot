dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:${providers.gradleProperty("junitVersion").get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":plugin-core"))
    testImplementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")
}

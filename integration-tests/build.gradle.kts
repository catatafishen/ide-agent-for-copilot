dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":plugin-core"))
    testImplementation("com.google.code.gson:gson:2.13.1")
}

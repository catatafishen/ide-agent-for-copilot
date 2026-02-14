plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10" apply false
    id("org.jetbrains.intellij.platform") version "2.11.0" apply false
}

allprojects {
    group = "com.github.copilot.intellij"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}

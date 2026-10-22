plugins {
    base
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
}

group = "com.example.clusterstatemachine"
version = "0.1.0-SNAPSHOT"

val ktlintVersion = libs.versions.ktlintEngine.get()
val kotlinVersion = libs.versions.kotlin.get()

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    // Keep imported Kotlin libraries compatible with the compiler and IntelliJ 2025.2.
    // Spring Boot's BOM otherwise selects a newer Kotlin runtime than the IDE supports.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        dependencies.add("implementation", dependencies.enforcedPlatform("org.jetbrains.kotlin:kotlin-bom:$kotlinVersion"))
    }

    // Aeron/Agrona require access to Unsafe on modern JDKs.
    tasks.withType<JavaExec>().configureEach {
        workingDir(rootProject.projectDir)
        jvmArgs("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
    }
    plugins.withId("org.springframework.boot") {
        tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
            manifest.attributes("Add-Exports" to "java.base/jdk.internal.misc")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }
}

// Clean generated frontend output alongside the backend build directories.
tasks.named<Delete>("clean") {
    delete("frontend/dist", "frontend/.vite", "frontend/node_modules/.vite")
}

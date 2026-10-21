import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

val taxiGenerator by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

dependencies {
    taxiGenerator("com.example.taxicodegen:taxi-kotlin-codegen:${providers.gradleProperty("taxiCodegenVersion").get()}")
    api("org.agrona:agrona:2.7.0")
    testImplementation(kotlin("test"))
}

val schema = layout.projectDirectory.file("src/main/taxi/counter.taxi")
val baseline = layout.projectDirectory.file("src/main/taxi/baselines/counter-v0.taxi")
val generatedSources = layout.buildDirectory.dir("generated/sources/taxi/main/kotlin")
val generatorLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

val generateCounterCodecs by tasks.registering(JavaExec::class) {
    group = "code generation"
    description = "Generate the shared counter domain messages and buffer codecs from Taxi."
    classpath = taxiGenerator
    javaLauncher.set(generatorLauncher)
    mainClass.set("com.example.taxicodegen.MainKt")
    inputs.file(schema)
    outputs.dir(generatedSources)
    args(schema.asFile.absolutePath, "--codecs", generatedSources.get().asFile.absolutePath)
    doFirst { delete(generatedSources) }
}

val checkCounterSchema by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Check fixed-field compatibility against the preserved counter v0 schema."
    classpath = taxiGenerator
    javaLauncher.set(generatorLauncher)
    mainClass.set("com.example.taxicodegen.MainKt")
    inputs.files(schema, baseline)
    args(schema.asFile.absolutePath, "--check-compatible", baseline.asFile.absolutePath)
}

kotlin.sourceSets.main { kotlin.srcDir(generateCounterCodecs) }
tasks.named("check") { dependsOn(checkCounterSchema) }
ktlint {
    filter {
        exclude {
            it.file.toPath().startsWith(
                layout.buildDirectory
                    .get()
                    .asFile
                    .toPath(),
            )
        }
    }
}

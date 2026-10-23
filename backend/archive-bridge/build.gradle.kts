plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
application {
    applicationDefaultJvmArgs = listOf("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
    mainClass.set("com.example.clusterstatemachine.archive.ArchiveBridgeApplicationKt")
}
dependencies {
    implementation(project(":counter-protocol"))
    implementation(libs.aeron.cluster)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

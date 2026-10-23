plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":counter-protocol"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter)
    implementation(libs.kotlin.reflect)
    implementation(libs.aeron.cluster)
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.mockito:mockito-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Explicit opt-in: starts a real single-node cluster and isolated local clients.
val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(integrationTest.implementationConfigurationName, project(":trading-gateway"))
    add(integrationTest.implementationConfigurationName, project(":archive-bridge"))
    add(integrationTest.implementationConfigurationName, libs.vertx.web)
}
tasks.register<Test>("integrationTest") {
    description = "Verify Taxi codecs, legacy migration, WebSocket broadcasts, and real single-node restart replay."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    systemProperty("junit.jupiter.execution.timeout.default", "90 s")
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
application { mainClass.set("com.example.clusterstatemachine.history.HistoryApplicationKt") }
dependencies {
    implementation(project(":counter-protocol"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.aeron.cluster)
    implementation(libs.vertx.web)
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.flywaydb:flyway-core:12.4.0")
    implementation("org.flywaydb:flyway-database-postgresql:12.4.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
    jvmArgs("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
}

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
tasks.register<Test>("integrationTest") {
    description = "Verify Flyway and transactional history against local PostgreSQL using an isolated schema."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    workingDir(rootProject.projectDir)
}

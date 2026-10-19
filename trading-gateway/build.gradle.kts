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
  implementation(libs.vertx.web)
  testImplementation(kotlin("test-junit5"))
  testImplementation("org.mockito:mockito-core")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

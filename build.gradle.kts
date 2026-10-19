plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.spring) apply false
  alias(libs.plugins.spring.boot) apply false
}

group = "com.example.clusterstatemachine"
version = "0.1.0-SNAPSHOT"

subprojects {
  group = rootProject.group
  version = rootProject.version

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

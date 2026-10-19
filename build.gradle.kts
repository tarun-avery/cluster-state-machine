plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.spring) apply false
  alias(libs.plugins.spring.boot) apply false
}

group = "dev.tarunavery"
version = "0.1.0-SNAPSHOT"

subprojects {
  group = rootProject.group
  version = rootProject.version

  tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
  }
}

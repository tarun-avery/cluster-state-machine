pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.orbitalhq.com/release") {
            content { includeGroupByRegex("org\\.taxilang.*") }
        }
    }
}

// Compile the reusable generator separately; it is a build-time dependency only.
val generatorDirectory = file(providers.gradleProperty("taxiCodegenDir").getOrElse("../taxi-kotlin-codegen"))
require(generatorDirectory.resolve("settings.gradle.kts").isFile) {
    "Taxi generator checkout missing. See docs/taxi-integration.md or set -PtaxiCodegenDir=/path/to/taxi-kotlin-codegen."
}
val expectedRevision = providers.gradleProperty("taxiCodegenRevision").get()
val actualRevision =
    providers
        .exec {
            workingDir(generatorDirectory)
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText
        .get()
        .trim()
require(actualRevision == expectedRevision) {
    "Taxi generator must be checked out at $expectedRevision (found $actualRevision). See docs/taxi-integration.md."
}
val generatorChanges =
    providers
        .exec {
            workingDir(generatorDirectory)
            commandLine("git", "status", "--porcelain", "--untracked-files=normal", "--", ".", ":(exclude)README.md", ":(exclude)docs")
        }.standardOutput.asText
        .get()
        .trim()
require(generatorChanges.isEmpty()) {
    "Taxi generator build inputs must be clean for reproducible generation (README/docs edits are allowed)."
}
includeBuild(generatorDirectory)

rootProject.name = "cluster-state-machine"
include("counter-protocol", "cluster", "trading-gateway")

// Keep Gradle project paths (and existing run commands) stable while grouping sources.
project(":cluster").projectDir = file("backend/cluster")
project(":counter-protocol").projectDir = file("backend/counter-protocol")
project(":trading-gateway").projectDir = file("backend/trading-gateway")

include("history-service")
project(":history-service").projectDir = file("backend/history-service")

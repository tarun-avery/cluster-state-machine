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
    }
}

rootProject.name = "cluster-state-machine"
include("counter-protocol", "cluster", "trading-gateway")

// Keep Gradle project paths (and existing run commands) stable while grouping sources.
project(":cluster").projectDir = file("backend/cluster")
project(":counter-protocol").projectDir = file("backend/counter-protocol")
project(":trading-gateway").projectDir = file("backend/trading-gateway")

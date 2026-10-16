pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "memoryos"

include(":memoryos-core")
include(":memoryos-integration-lark")
include(":memoryos-integration-opensandbox")
include(":memoryos-integration-google")
include(":memoryos-integration-microsoft")
include(":memoryos-integration-zalo")
include(":memoryos-extraction")
include(":memoryos-api")
include(":memoryos-worker")
include(":memoryos-channels")

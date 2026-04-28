rootProject.name = "anchor"

include(
    "anchor-protocol",
    "anchor-server",
    "anchor-client",
    "anchor-shell"
)

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

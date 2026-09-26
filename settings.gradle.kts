pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 文石 Onyx SDK
        maven { url = uri("http://repo.boox.com/repository/maven-public/"); isAllowInsecureProtocol = true }
    }
}

rootProject.name = "ad-note"
include(":app")

import java.util.Properties

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/compose-miuix-ui/miuix")
            credentials {
                val properties = Properties()
                val localProperties = File(rootDir, "local.properties")
                if (localProperties.exists()) {
                    properties.load(localProperties.inputStream())
                }
                username = properties.getProperty("gpr.user")
                    ?: providers.environmentVariable("GITHUB_ACTOR").orNull
                password = properties.getProperty("gpr.key")
                    ?: providers.environmentVariable("GITHUB_TOKEN").orNull
            }
            content {
                includeGroupByRegex("top\\.yukonga\\.miuix\\.kmp(\\..*)?")
            }
        }
        mavenLocal {
            content {
                includeGroup("io.github.libxposed")
            }
        }
    }
    versionCatalogs {
        create("libs")
    }
}

rootProject.name = "LyricInfo"

include(":app")
include(":lite")
include(":debug")




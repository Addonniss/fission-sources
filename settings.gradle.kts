pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "infusion"

include(":app-tv")

include(":core:model")
include(":core:domain")
include(":core:sync")
include(":core:database")
include(":core:network")
include(":core:security")
include(":core:playback-api")
include(":core:playback-media3")
include(":core:metadata")
include(":core:update")
include(":core:artwork-cache")
include(":core:artwork-image")
include(":core:lanweb")
include(":core:pairing")
include(":core:pairing-android")
include(":core:testing")

include(":feature:vertical")
include(":feature:pairing")
include(":feature:configweb")

include(":source:jellyfin")
include(":source:stremio")
include(":source:addon")
include(":source:aggregation")
include(":source:tmdb")
include(":source:communityratings")
include(":source:segments")
include(":source:acquisition")

include(":tracking:simkl")
include(":tracking:trakt")
include(":tracking:mdblist")

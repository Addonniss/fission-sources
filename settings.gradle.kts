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

include(":core:artwork-cache")
include(":core:artwork-image")
include(":core:database")
include(":core:domain")
include(":core:lanweb")
include(":core:metadata")
include(":core:model")
include(":core:network")
include(":core:pairing")
include(":core:pairing-android")
include(":core:playback-api")
include(":core:playback-media3")
include(":core:security")
include(":core:sync")
include(":core:testing")
include(":core:update")

include(":feature:configweb")
include(":feature:pairing")
include(":feature:vertical")

include(":source:acquisition")
include(":source:addon")
include(":source:aggregation")
include(":source:communityratings")
include(":source:jellyfin")
include(":source:segments")
include(":source:stremio")
include(":source:tmdb")

include(":tracking:mdblist")
include(":tracking:simkl")
include(":tracking:trakt")

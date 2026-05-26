package launcher.ui.nav

import launcher.core.CfBedrockProject
import launcher.core.ModrinthProject
import launcher.core.RemoteVersion
import launcher.core.WUDownloadClient

sealed class Route {
    // ── 一级页面 (NavigationRail 直达) ────────────────────────────────────────
    data object Launch : Route()
    data object Versions : Route()
    data object Download : Route()
    data object Mods : Route()
    data object BedrockMods : Route()
    data object Settings : Route()

    // ── 二级页面 (从一级页面跳转进入) ──────────────────────────────────────────
    data class VersionDetail(val version: RemoteVersion) : Route()
    data class BedrockVersionDetail(val version: WUDownloadClient.WUVersion) : Route()
    data class ModDetail(val project: ModrinthProject, val edition: String = "java", val contentType: String = project.projectType) : Route()
    data class CfBedrockDetail(val project: CfBedrockProject) : Route()
    data object DownloadManager : Route()
    data class BedrockPackManager(val versionId: String, val versionDir: String, val packType: String) : Route() // packType: behavior_packs | resource_packs
    data class BedrockWorldManager(val versionId: String, val versionDir: String) : Route()
}

fun Route.primaryTab(): Screen = when (this) {
    is Route.Launch -> Screen.Launch
    is Route.Versions -> Screen.Versions
    is Route.Download, is Route.VersionDetail, is Route.BedrockVersionDetail, is Route.DownloadManager -> Screen.Download
    is Route.Mods, is Route.BedrockMods, is Route.ModDetail, is Route.CfBedrockDetail -> Screen.Mods
    is Route.Settings -> Screen.Settings
    is Route.BedrockPackManager -> Screen.Versions
    is Route.BedrockWorldManager -> Screen.Versions
}

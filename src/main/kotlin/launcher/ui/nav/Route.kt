package launcher.ui.nav

import launcher.core.ModrinthProject
import launcher.core.RemoteVersion
import launcher.core.WUDownloadClient

sealed class Route {
    // ── 一级页面 (NavigationRail 直达) ────────────────────────────────────────
    data object Launch : Route()
    data object Versions : Route()
    data object Download : Route()
    data object Mods : Route()
    data object Settings : Route()

    // ── 二级页面 (从一级页面跳转进入) ──────────────────────────────────────────
    data class VersionDetail(val version: RemoteVersion) : Route()
    data class BedrockVersionDetail(val version: WUDownloadClient.WUVersion) : Route()
    data class ModDetail(val project: ModrinthProject, val edition: String = "java", val contentType: String = project.projectType) : Route()
    data object DownloadManager : Route()
}

fun Route.primaryTab(): Screen = when (this) {
    is Route.Launch -> Screen.Launch
    is Route.Versions -> Screen.Versions
    is Route.Download, is Route.VersionDetail, is Route.BedrockVersionDetail, is Route.DownloadManager -> Screen.Download
    is Route.Mods, is Route.ModDetail -> Screen.Mods
    is Route.Settings -> Screen.Settings
}

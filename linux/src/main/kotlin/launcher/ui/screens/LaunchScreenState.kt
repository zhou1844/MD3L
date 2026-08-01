package launcher.ui.screens

import launcher.core.LocalVersion
import launcher.core.AppSettings
import androidx.compose.runtime.mutableStateOf

object LaunchScreenState {
    val selectedVersion = mutableStateOf<LocalVersion?>(null)

    val versions = mutableStateOf<List<LocalVersion>>(emptyList())

    val bedrockVersions = mutableStateOf<List<LocalVersion>>(emptyList())

    val settings = mutableStateOf(AppSettings())

    val launchMessage = mutableStateOf("")

    var initialized = false
}

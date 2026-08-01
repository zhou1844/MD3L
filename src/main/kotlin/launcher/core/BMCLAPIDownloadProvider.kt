package launcher.core

object BMCLAPIDownloadProvider : DownloadProvider {

    private const val DEFAULT_API_ROOT = "https://bmclapi2.bangbang93.com"

    @Volatile
    var apiRoot: String = DEFAULT_API_ROOT

    private val replacement: List<Pair<String, String>> = listOf(
        "https://bmclapi2.bangbang93.com" to apiRoot,
        "https://launchermeta.mojang.com" to apiRoot,
        "https://piston-meta.mojang.com" to apiRoot,
        "https://piston-data.mojang.com" to apiRoot,
        "https://launcher.mojang.com" to apiRoot,
        "https://libraries.minecraft.net" to "$apiRoot/libraries",
        "http://files.minecraftforge.net/maven" to "$apiRoot/maven",
        "https://files.minecraftforge.net/maven" to "$apiRoot/maven",
        "https://maven.minecraftforge.net" to "$apiRoot/maven",
        "https://maven.neoforged.net/releases/" to "$apiRoot/maven/",
        "http://dl.liteloader.com/versions/versions.json" to "$apiRoot/maven/com/mumfrey/liteloader/versions.json",
        "http://dl.liteloader.com/versions" to "$apiRoot/maven",
        "https://meta.fabricmc.net" to "$apiRoot/fabric-meta",
        "https://maven.fabricmc.net" to "$apiRoot/maven",
        "https://authlib-injector.yushi.moe" to "$apiRoot/mirrors/authlib-injector",
        "https://repo1.maven.org/maven2" to "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
        "https://repo.maven.apache.org/maven2" to "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
        "https://hmcl.glavo.site/metadata/cleanroom" to "https://alist.8mi.tech/d/mirror/HMCL-Metadata/Auto/cleanroom",
        "https://hmcl.glavo.site/metadata/fmllibs" to "https://alist.8mi.tech/d/mirror/HMCL-Metadata/Auto/fmllibs",
        "https://zkitefly.github.io/unlisted-versions-of-minecraft" to "https://alist.8mi.tech/d/mirror/unlisted-versions-of-minecraft/Auto",
        "https://resources.download.minecraft.net" to "$apiRoot/assets",
    )

    private val fallbackReplacement: List<Pair<String, String>> = listOf(
        "https://api.modrinth.com" to "https://mod.mcimirror.top/modrinth",
        "https://cdn.modrinth.com" to "https://mod.mcimirror.top",
        "https://api.curseforge.com" to "https://mod.mcimirror.top/curseforge",
        "https://edge.forgecdn.net" to "https://mod.mcimirror.top",
        "https://mediafilez.forgecdn.net" to "https://mod.mcimirror.top",
        "https://media.forgecdn.net" to "https://mod.mcimirror.top",
    )

    private fun injectURL(replacement: List<Pair<String, String>>, baseURL: String): String {
        for ((key, value) in replacement) {
            if (baseURL.startsWith(key)) {
                return value + baseURL.substring(key.length)
            }
        }
        return baseURL
    }

    override fun injectURL(baseURL: String): String {
        return injectURL(replacement, baseURL)
    }

    override fun injectURLWithCandidates(baseURL: String): List<String> {
        val currentReplacement = if (apiRoot != DEFAULT_API_ROOT) {
            replacement.map { (k, v) ->
                k to v.replace(DEFAULT_API_ROOT, apiRoot)
            }
        } else {
            replacement
        }

        val injected = injectURL(currentReplacement, baseURL)
        if (injected != baseURL) {
            return listOf(injected)
        }

        val fallbackInjected = injectURL(fallbackReplacement, baseURL)
        if (fallbackInjected != baseURL) {

            return listOf(fallbackInjected)
        }

        return listOf(baseURL)
    }

    override fun getConcurrency(): Int {
        return maxOf(Runtime.getRuntime().availableProcessors() * 2, 6)
    }
}

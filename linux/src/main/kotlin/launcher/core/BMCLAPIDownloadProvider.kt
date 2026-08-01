package launcher.core

/**
 * HMCL 风格 BMCLAPI 下载源
 *
 * 精确移植自 HMCL BMCLAPIDownloadProvider：
 * - 两层 URL 替换：replacement（主要镜像）+ fallbackReplacement（备用镜像）
 * - injectURLWithCandidates: 主镜像命中 → 返回注入 URL；否则尝试备用镜像 → 返回 [原始, 备用]
 * - getConcurrency: max(cores*2, 6)
 */
object BMCLAPIDownloadProvider : DownloadProvider {

    /** 默认 BMCLAPI 根地址 */
    private const val DEFAULT_API_ROOT = "https://bmclapi2.bangbang93.com"

    /** 当前使用的 API 根地址 */
    @Volatile
    var apiRoot: String = DEFAULT_API_ROOT

    /** 主要 URL 替换规则（精确复制 HMCL） */
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
        // resources 目录特殊路径
        "https://resources.download.minecraft.net" to "$apiRoot/assets",
    )

    /** 备用 URL 替换规则（Modrinth / CurseForge 镜像） */
    private val fallbackReplacement: List<Pair<String, String>> = listOf(
        "https://api.modrinth.com" to "https://mod.mcimirror.top/modrinth",
        "https://cdn.modrinth.com" to "https://mod.mcimirror.top",
        "https://api.curseforge.com" to "https://mod.mcimirror.top/curseforge",
        "https://edge.forgecdn.net" to "https://mod.mcimirror.top",
        "https://mediafilez.forgecdn.net" to "https://mod.mcimirror.top",
        "https://media.forgecdn.net" to "https://mod.mcimirror.top",
    )

    /** 用替换规则注入 URL */
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

    /**
     * 注入原始 URL，返回候选 URL 列表
     *
     * 逻辑（基于 HMCL，针对国内整合包下载优化）：
     * - 如果主镜像规则命中（injected != baseURL）→ 返回 [injected]
     * - 如果备用镜像规则命中（Modrinth/CurseForge）→ 默认「镜像优先，官方兜底」，
     *   显著加速国内整合包资源下载；仅当用户显式选择官方源（apiRoot 为空）时保持官方优先
     * - 否则 → 返回 [原始 URL]
     */
    override fun injectURLWithCandidates(baseURL: String): List<String> {
        // 动态更新 replacement 中的 apiRoot
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
            // 国内优化：mcimirror 镜像优先、官方源兜底（整合包资源提速关键）；
            // 用户显式选择官方源（apiRoot 为空）时保持官方优先。
            return if (apiRoot.isBlank())
                listOf(baseURL, fallbackInjected)
            else
                listOf(fallbackInjected, baseURL)
        }

        return listOf(baseURL)
    }

    /** max(cores*2, 6) — 与 HMCL 完全一致 */
    override fun getConcurrency(): Int {
        return maxOf(Runtime.getRuntime().availableProcessors() * 2, 6)
    }
}

package launcher.core

object BMCLAPIDownloadProvider : DownloadProvider {

    // 默认 BMCLAPI 根地址 
    private const val DEFAULT_API_ROOT = "https://bmclapi2.bangbang93.com"

    // 当前使用的 API 根地址 
    @Volatile
    var apiRoot: String = DEFAULT_API_ROOT

    // 主要 URL 替换规则 
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

    // 备用 URL 替换规则（Modrinth / CurseForge 镜像） 
    private val fallbackReplacement: List<Pair<String, String>> = listOf(
        "https://api.modrinth.com" to "https://mod.mcimirror.top/modrinth",
        "https://cdn.modrinth.com" to "https://mod.mcimirror.top",
        "https://api.curseforge.com" to "https://mod.mcimirror.top/curseforge",
        "https://edge.forgecdn.net" to "https://mod.mcimirror.top",
        "https://mediafilez.forgecdn.net" to "https://mod.mcimirror.top",
        "https://media.forgecdn.net" to "https://mod.mcimirror.top",
    )

    // 用替换规则注入 URL 
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
     * 逻辑（针对国内整合包下载优化，彻底禁用官方源兜底）：
     * - 如果主镜像规则命中（injected != baseURL）→ 返回 [injected]（仅镜像）
     * - 如果备用镜像规则命中（Modrinth/CurseForge）→ 只返回 [镜像]，
     *   不再追加官方原始 URL。国内直连官方 CDN（api/cdn.modrinth.com、
     *   forgecdn.net）经常连接假死，官方兜底会让每个文件白耗一轮超时/重试，
     *   整体表现为「卡住」。彻底删除官方兜底，只信任镜像源。
     * - 否则 → 返回 [原始 URL]（无对应镜像时的最后手段）
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
            
            return listOf(fallbackInjected)
        }

        return listOf(baseURL)
    }

    // max(cores*2, 6) 
    override fun getConcurrency(): Int {
        return maxOf(Runtime.getRuntime().availableProcessors() * 2, 6)
    }
}

package launcher.core

import java.net.URI
import java.util.LinkedHashSet

/**
 * 下载源接口
 *
 * - injectURLWithCandidates: 生成候选 URL 列表（原 URL + 镜像）
 * - injectURLsWithCandidates: 批量注入（LinkedHashSet 去重）
 * - getConcurrency: 该下载源支持的并发数
 */
interface DownloadProvider {

    // 注入原始 URL，返回等效的镜像 URL 
    fun injectURL(baseURL: String): String

    // 注入原始 URL，返回候选 URL 列表（按优先级从高到低） 
    fun injectURLWithCandidates(baseURL: String): List<String> {
        return listOf(injectURL(baseURL))
    }

    // 批量注入 URL，去重保持顺序 
    fun injectURLsWithCandidates(urls: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        for (url in urls) {
            result.addAll(injectURLWithCandidates(url))
        }
        return result.toList()
    }

    // 该下载源支持的最大并发下载数 
    fun getConcurrency(): Int
}

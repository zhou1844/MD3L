package launcher.core

import java.net.URI
import java.util.LinkedHashSet

interface DownloadProvider {

    fun injectURL(baseURL: String): String

    fun injectURLWithCandidates(baseURL: String): List<String> {
        return listOf(injectURL(baseURL))
    }

    fun injectURLsWithCandidates(urls: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        for (url in urls) {
            result.addAll(injectURLWithCandidates(url))
        }
        return result.toList()
    }

    fun getConcurrency(): Int
}

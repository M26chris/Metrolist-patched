package com.metrolist.innertube

import com.metrolist.innertube.models.response.SearchResponse
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

class ParseTest {
    @Test
    fun testSearchParsing() {
        val jsonString = File("../search_test_utf8.json").readText().removePrefix("\uFEFF")
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
        try {
            val response = json.decodeFromString<SearchResponse>(jsonString)
            val sb = java.lang.StringBuilder()
            sb.appendLine("Successfully parsed. contents is null? ${response.contents == null}")
            if (response.contents != null) {
                sb.appendLine("tabbedSearchResultsRenderer is null? ${response.contents.tabbedSearchResultsRenderer == null}")
                val tabs = response.contents.tabbedSearchResultsRenderer?.tabs
                sb.appendLine("tabs count = ${tabs?.size}")
                val tab = tabs?.firstOrNull()?.tabRenderer
                sb.appendLine("tab is null? ${tab == null}")
                val sectionList = tab?.content?.sectionListRenderer
                sb.appendLine("sectionList is null? ${sectionList == null}")
                sb.appendLine("sectionList contents count = ${sectionList?.contents?.size}")
            }
            File("../test_out.txt").writeText(sb.toString())
        } catch (e: Exception) {
            File("../test_out.txt").writeText("Exception: ${e.message}\n${e.stackTraceToString()}")
        }
    }
}

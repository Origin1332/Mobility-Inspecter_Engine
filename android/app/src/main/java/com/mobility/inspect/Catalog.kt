package com.mobility.engine

data class CatalogEntry(
    val id: String, val partNumber: String, val nameKo: String, val nameEn: String,
    val groupCode: String, val groupTitle: String, val callout: String,
    val options: String, val fromDate: String, val toDate: String, val remarks: String,
    val side: String, val transmission: String, val modelQuantities: String,
    val page: Int, val printedPage: String, val diagramPages: List<Int>,
    val rowRect: List<Float>, val pageWidth: Float, val pageHeight: Float,
    val relations: Map<String,String>
) {
    fun searchText() = listOf(partNumber,nameKo,nameEn,groupCode,options,remarks).joinToString(" ")
}

object CatalogSearch {
    private fun normalize(s: String) = s.lowercase(java.util.Locale.ROOT)
        .filterNot { it.isWhitespace() || it == '-' }
    fun find(entries: List<CatalogEntry>, className: String?, query: String): List<CatalogEntry> {
        val q=normalize(query)
        return entries.filter { (className==null || it.relations.containsKey(className)) &&
            normalize(it.searchText()).contains(q) }
            .sortedWith(compareBy<CatalogEntry> { if (it.relations[className]=="primary") 0 else 1 }
                .thenBy { it.page }.thenBy { it.rowRect[1] })
    }
}

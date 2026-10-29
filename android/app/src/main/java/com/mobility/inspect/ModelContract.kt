package com.mobility.engine

object ModelContract {
    fun classIds(names: List<String>): IntArray {
        require(names.isNotEmpty() && names.distinct().size == names.size && names.all { it in Parts.ids }) { "엔진룸 클래스 불일치" }
        return names.map { Parts.ids.indexOf(it) }.toIntArray()
    }
    fun candidates(size: Int): Long {
        require(size in setOf(640, 960, 1280))
        return listOf(8, 16, 32).sumOf { (size / it).toLong() * (size / it) }
    }
}


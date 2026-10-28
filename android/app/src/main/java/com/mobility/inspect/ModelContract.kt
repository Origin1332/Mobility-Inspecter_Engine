package com.mobility.engine

object ModelContract {
    fun candidates(size: Int): Long {
        require(size in setOf(640, 960, 1280))
        return listOf(8, 16, 32).sumOf { (size / it).toLong() * (size / it) }
    }
}


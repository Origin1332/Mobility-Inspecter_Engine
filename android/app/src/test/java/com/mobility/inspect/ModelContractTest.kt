package com.mobility.engine
import org.junit.Assert.*
import org.junit.Test

class ModelContractTest {
    @Test fun supportedOutputShapes() {
        assertEquals(8400L, ModelContract.candidates(640))
        assertEquals(18900L, ModelContract.candidates(960))
        assertEquals(33600L, ModelContract.candidates(1280))
        assertEquals(12, Parts.ids.size)
        assertEquals(Parts.ids.size, Parts.names.size)
        assertEquals(Parts.ids.size, Parts.notes.size)
    }
    @Test fun reorderedSubsetMapsByName() {
        assertArrayEquals(intArrayOf(6, 0), ModelContract.classIds(listOf("oil_dipstick", "battery")))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsLegacyUnknownClass() { ModelContract.classIds(listOf("engine_cover")) }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSize() { ModelContract.candidates(641) }
}


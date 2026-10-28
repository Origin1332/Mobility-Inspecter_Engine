package com.mobility.engine
import org.junit.Assert.*
import org.junit.Test

class ModelContractTest {
    @Test fun supportedOutputShapes() {
        assertEquals(8400L, ModelContract.candidates(640))
        assertEquals(18900L, ModelContract.candidates(960))
        assertEquals(33600L, ModelContract.candidates(1280))
        assertEquals(10, Parts.ids.size)
        assertEquals(Parts.ids.size, Parts.names.size)
        assertEquals(Parts.ids.size, Parts.notes.size)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSize() { ModelContract.candidates(641) }
}


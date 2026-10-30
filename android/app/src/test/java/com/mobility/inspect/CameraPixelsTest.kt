package com.mobility.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CameraPixelsTest {
    @Test fun cropRespectsOffsetPaddedRowsAndPixelStride() {
        val buffer=ByteBuffer.allocate(3+3*32);buffer.position(3)
        for(y in 0..2) for(x in 0..3) {
            val offset=3+y*32+x*6
            buffer.put(offset,(10+x).toByte());buffer.put(offset+1,(20+y).toByte());buffer.put(offset+2,30.toByte())
        }
        val result=CameraPixels.rgba(buffer,2,2,1,1,32,6)
        assertArrayEquals(intArrayOf(0xff0b151e.toInt(),0xff0c151e.toInt(),0xff0b161e.toInt(),0xff0c161e.toInt()),result)
        assertEquals(3,buffer.position())
    }
    @Test fun fullFramePreservesRgbOrder() {
        val buffer=ByteBuffer.wrap(byteArrayOf(1,2,3,0,4,5,6,0))
        assertArrayEquals(intArrayOf(0xff010203.toInt(),0xff040506.toInt()),CameraPixels.rgba(buffer,2,1,0,0,8,4))
    }
    @Test(expected=IllegalArgumentException::class) fun truncatedBufferIsRejected() {
        CameraPixels.rgba(ByteBuffer.allocate(7),2,1,0,0,8,4)
    }
}

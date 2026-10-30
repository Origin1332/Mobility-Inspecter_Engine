package com.mobility.engine

import java.nio.ByteBuffer

/** Reads the unrotated crop without copying padded rows or changing the buffer position. */
object CameraPixels {
    fun rgba(buffer: ByteBuffer, width: Int, height: Int, left: Int, top: Int, rowStride: Int, pixelStride: Int): IntArray {
        require(width>0 && height>0 && left>=0 && top>=0 && pixelStride>=4 && rowStride>0)
        val base=buffer.position()
        val last=base.toLong()+(top.toLong()+height-1)*rowStride+(left.toLong()+width-1)*pixelStride+3
        require(last<buffer.limit()) {"카메라 RGBA 버퍼가 잘렸습니다."}
        return IntArray(width*height) { index ->
            val x=index%width;val y=index/width
            val offset=base+(y+top)*rowStride+(x+left)*pixelStride
            val r=buffer.get(offset).toInt() and 255
            val g=buffer.get(offset+1).toInt() and 255
            val b=buffer.get(offset+2).toInt() and 255
            (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

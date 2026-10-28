package com.mobility.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

object CameraFrame {
    fun bitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixels = IntArray(image.width*image.height)
        val base = buffer.position()
        // CameraX OUTPUT_IMAGE_FORMAT_RGBA_8888 has bytes R,G,B,A, including row padding.
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val offset = base + y*plane.rowStride + x*plane.pixelStride
            val r = buffer.get(offset).toInt() and 255
            val g = buffer.get(offset+1).toInt() and 255
            val b = buffer.get(offset+2).toInt() and 255
            pixels[y*image.width+x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        val raw = Bitmap.createBitmap(pixels,image.width,image.height,Bitmap.Config.ARGB_8888)
        val degrees = image.imageInfo.rotationDegrees
        if (degrees == 0) return raw
        val rotated = Bitmap.createBitmap(raw,0,0,raw.width,raw.height,Matrix().apply { postRotate(degrees.toFloat()) },true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }
}

package com.mobility.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

object CameraFrame {
    fun bitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        // Match ImageProxyTransformFactory(usingCropRect=true, usingRotationDegrees=true).
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        require(width > 0 && height > 0)
        val pixels = CameraPixels.rgba(buffer,width,height,crop.left,crop.top,plane.rowStride,plane.pixelStride)
        val raw = Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
        val degrees = image.imageInfo.rotationDegrees
        if (degrees == 0) return raw
        val rotated = Bitmap.createBitmap(raw,0,0,raw.width,raw.height,Matrix().apply { postRotate(degrees.toFloat()) },true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }
}

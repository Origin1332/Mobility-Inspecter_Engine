package com.mobility.engine

import kotlin.math.*

data class Detection(val classId: Int, val score: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float)
data class Letterbox(val width: Int, val height: Int, val size: Int) {
    val scale = min(size.toFloat() / width, size.toFloat() / height)
    val resizedWidth = (width * scale).roundToInt()
    val resizedHeight = (height * scale).roundToInt()
    val left = (size - resizedWidth) / 2
    val top = (size - resizedHeight) / 2
    fun restore(c: Int, score: Float, cx: Float, cy: Float, w: Float, h: Float) = Detection(c, score,
        ((cx-w/2-left) * width / resizedWidth).coerceIn(0f,width.toFloat()),
        ((cy-h/2-top) * height / resizedHeight).coerceIn(0f,height.toFloat()),
        ((cx+w/2-left) * width / resizedWidth).coerceIn(0f,width.toFloat()),
        ((cy+h/2-top) * height / resizedHeight).coerceIn(0f,height.toFloat()))
}
object Geometry {
    fun iou(a: Detection, b: Detection): Float {
        val intersection = max(0f,min(a.x2,b.x2)-max(a.x1,b.x1)) * max(0f,min(a.y2,b.y2)-max(a.y1,b.y1))
        val union = (a.x2-a.x1)*(a.y2-a.y1)+(b.x2-b.x1)*(b.y2-b.y1)-intersection
        return if (union > 0) intersection/union else 0f
    }
    fun nms(boxes: List<Detection>, threshold: Float = .45f, limit: Int = 50): List<Detection> {
        val result = mutableListOf<Detection>()
        for (box in boxes.sortedByDescending { it.score }.take(3000)) {
            if (result.none { it.classId == box.classId && iou(it,box) > threshold }) result.add(box)
            if (result.size == limit) break
        }
        return result
    }
}

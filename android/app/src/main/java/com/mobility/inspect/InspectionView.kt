package com.mobility.engine

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class InspectionView(context: Context) : View(context) {
    private var frame: Bitmap? = null
    private var reviewed = false
    private var boxes: List<Detection> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scale = 1f
    private var dx = 0f
    private var dy = 0f
    var onSelection: (Detection) -> Unit = {}
    fun show(bitmap: Bitmap, detections: List<Detection>, reviewedBoxes: Boolean = false) {
        reviewed = reviewedBoxes
        val old = frame
        frame = bitmap
        boxes = detections
        old?.recycle()
        invalidate()
    }
    fun clear() { frame?.recycle(); frame=null; boxes=emptyList(); invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12,19,28))
        val bitmap = frame ?: return
        scale = min(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
        dx = (width-bitmap.width*scale)/2
        dy = (height-bitmap.height*scale)/2
        paint.style = Paint.Style.FILL
        canvas.drawBitmap(bitmap,null,RectF(dx,dy,dx+bitmap.width*scale,dy+bitmap.height*scale),paint)
        for (b in boxes) {
            val rect = RectF(dx+b.x1*scale,dy+b.y1*scale,dx+b.x2*scale,dy+b.y2*scale)
            paint.color = Color.rgb(65,230,180); paint.style=Paint.Style.STROKE; paint.strokeWidth=3f*resources.displayMetrics.density
            canvas.drawRect(rect,paint)
            paint.style=Paint.Style.FILL; paint.textSize=14f*resources.displayMetrics.scaledDensity
            val label = Parts.names[b.classId] + if (reviewed) " · 검수" else " ${(b.score*100).toInt()}%"
            val textWidth = paint.measureText(label)
            val x = rect.left.coerceIn(0f,(width-textWidth).coerceAtLeast(0f))
            val y = (rect.top-paint.textSize-6).coerceAtLeast(0f)
            paint.color=Color.argb(220,0,0,0)
            canvas.drawRect(x,y,x+textWidth+6,y+paint.textSize+8,paint)
            paint.color=Color.WHITE
            canvas.drawText(label,x+3,y+paint.textSize,paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x=(event.x-dx)/scale; val y=(event.y-dy)/scale
            boxes.filter { x in it.x1..it.x2 && y in it.y1..it.y2 }
                .minByOrNull { (it.x2-it.x1)*(it.y2-it.y1) }?.let(onSelection)
            performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

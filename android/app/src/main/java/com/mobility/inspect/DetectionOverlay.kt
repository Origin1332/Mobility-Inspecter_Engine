package com.mobility.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Transparent overlay. Rectangles are already mapped into PreviewView coordinates. */
class DetectionOverlay(context: Context): View(context) {
    private var rows: List<Pair<Detection,RectF>> = emptyList()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    var onSelection: (Detection)->Unit = {}
    fun show(mapped: List<Pair<Detection,RectF>>) {rows=mapped;invalidate()}
    fun clear() {rows=emptyList();invalidate()}
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for((box,rect) in rows) {
            paint.color=Color.rgb(65,230,180);paint.style=Paint.Style.STROKE
            paint.strokeWidth=2f*resources.displayMetrics.density
            canvas.drawRect(rect,paint)
            paint.style=Paint.Style.FILL;paint.textSize=14f*resources.displayMetrics.scaledDensity
            val label="${Parts.names[box.classId]} ${(box.score*100).toInt()}%"
            val w=paint.measureText(label)+8
            val x=rect.left.coerceIn(0f,(width-w).coerceAtLeast(0f))
            val y=(rect.top-paint.textSize-8).coerceAtLeast(0f)
            paint.color=Color.argb(220,0,0,0);canvas.drawRect(x,y,x+w,y+paint.textSize+8,paint)
            paint.color=Color.WHITE;canvas.drawText(label,x+4,y+paint.textSize,paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.action==MotionEvent.ACTION_UP) {
            rows.filter { it.second.contains(event.x,event.y) }
                .minByOrNull { it.second.width()*it.second.height() }?.let {onSelection(it.first)}
            performClick()
        }
        return true
    }
    override fun performClick(): Boolean {super.performClick();return true}
}

package com.mobility.engine
import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun letterboxRecoversLandscapeCorners() {
        val lb=Letterbox(1280,720,640)
        val d=lb.restore(0,.9f,320f,320f,640f,360f)
        assertEquals(0f,d.x1,.001f); assertEquals(0f,d.y1,.001f)
        assertEquals(1280f,d.x2,.001f); assertEquals(720f,d.y2,.001f)
    }
    @Test fun letterboxRecoversPortraitCorners() {
        val d=Letterbox(720,1280,640).restore(0,.9f,320f,320f,360f,640f)
        assertEquals(720f,d.x2,.001f); assertEquals(1280f,d.y2,.001f)
    }
    @Test fun nmsSuppressesSameClassButPreservesDifferentParts() {
        val a=Detection(0,.9f,0f,0f,100f,100f)
        val b=a.copy(score=.7f)
        val c=a.copy(classId=1,score=.8f)
        assertEquals(listOf(a,c),Geometry.nms(listOf(b,c,a)))
    }
    @Test fun zeroAreaDoesNotCreateNan() {
        val d=Detection(0,1f,0f,0f,0f,0f)
        assertEquals(0f,Geometry.iou(d,d),0f)
    }
}

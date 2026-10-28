package com.mobility.engine

import ai.onnxruntime.*
import android.graphics.*
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

class Detector(directory: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    val size: Int
    init {
        val manifest = JSONObject(File(directory,"manifest.json").readText())
        require(manifest.getInt("schema_version") == 1 && manifest.getString("architecture") in setOf("yolov8", "yolo11")) { "지원하지 않는 모델 패키지" }
        size = manifest.getInt("input_size")
        require(size in setOf(640, 960, 1280)) { "640/960/1280 입력 모델이 필요합니다" }
        require(manifest.getString("task") == "rexton_sports_2023_engine_parts") { "엔진룸 모델이 필요합니다" }
        val names = manifest.getJSONArray("classes")
        require((0 until names.length()).map { names.getString(it) } == Parts.ids) { "클래스 순서 불일치" }
        val model = File(directory,"model.onnx")
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val block = ByteArray(65536)
            while (true) { val n = input.read(block); if (n < 0) break; digest.update(block,0,n) }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == manifest.getString("sha256")) { "모델 체크섬 불일치" }
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            env.createSession(model.absolutePath,options)
        }
        try {
            require(session.inputInfo.size == 1 && session.outputInfo.size == 1)
            val input = session.inputInfo.values.first().info as TensorInfo
            val output = session.outputInfo.values.first().info as TensorInfo
            require(input.type == OnnxJavaType.FLOAT && input.shape.contentEquals(longArrayOf(1,3,size.toLong(),size.toLong()))) { "입력 형식 불일치" }
            require(output.type == OnnxJavaType.FLOAT && output.shape.contentEquals(longArrayOf(1,(4+Parts.ids.size).toLong(), ModelContract.candidates(size)))) { "출력 형식 불일치" }
        } catch (e: Exception) { session.close(); throw e }
    }
    fun detect(bitmap: Bitmap, threshold: Float): List<Detection> {
        val lb = Letterbox(bitmap.width,bitmap.height,size)
        val padded = Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.rgb(114,114,114))
        canvas.drawBitmap(bitmap,null,Rect(lb.left,lb.top,lb.left+lb.resizedWidth,lb.top+lb.resizedHeight),Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(size*size)
        padded.getPixels(pixels,0,size,0,0,size,size)
        padded.recycle()
        val data = FloatArray(3*size*size)
        for (i in pixels.indices) {
            data[i] = ((pixels[i] shr 16) and 255)/255f
            data[i+pixels.size] = ((pixels[i] shr 8) and 255)/255f
            data[i+2*pixels.size] = (pixels[i] and 255)/255f
        }
        return OnnxTensor.createTensor(env,FloatBuffer.wrap(data),longArrayOf(1,3,size.toLong(),size.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val rows = (output[0].value as Array<Array<FloatArray>>)[0]
                val boxes = mutableListOf<Detection>()
                for (i in rows[0].indices) {
                    var cls = 0
                    for (c in 1 until Parts.ids.size) if (rows[4+c][i] > rows[4+cls][i]) cls = c
                    val score = rows[4+cls][i]
                    if (!score.isFinite() || score < threshold) continue
                    val values = floatArrayOf(rows[0][i],rows[1][i],rows[2][i],rows[3][i])
                    if (values.any { !it.isFinite() } || values[2] <= 0 || values[3] <= 0) continue
                    val box = lb.restore(cls,score,values[0],values[1],values[2],values[3])
                    if (box.x2 > box.x1 && box.y2 > box.y1) boxes.add(box)
                }
                Geometry.nms(boxes)
            }
        }
    }
    override fun close() { session.close() }
}

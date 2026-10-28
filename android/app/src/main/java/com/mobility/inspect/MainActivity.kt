package com.mobility.engine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val pending=AtomicBoolean(false)
    private var detector: Detector?=null // Only accessed on worker.
    private var provider: ProcessCameraProvider?=null
    private var analysis: ImageAnalysis?=null
    private lateinit var view: InspectionView
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var importButton: Button
    @Volatile private var paused=false
    @Volatile private var active=false
    @Volatile private var threshold=.35f
    private var lastShown=0L
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text="카메라 권한이 필요합니다. 권한 버튼 또는 시스템 설정에서 허용하세요."
    }
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importButton.isEnabled=false
            status.text="모델 검증 중…"
            worker.execute {
                try {
                    val folder=ModelPackage.importPackage(this,uri)
                    val next=try { Detector(folder) } catch(e: Exception) { folder.deleteRecursively(); throw e }
                    val oldFolder=ModelPackage.active(this)
                    if (!getSharedPreferences("model",0).edit().putString("active",folder.name).commit()) {
                        next.close(); folder.deleteRecursively(); error("모델 설정 저장 실패")
                    }
                    detector?.close(); detector=next
                    oldFolder?.takeIf { it != folder }?.deleteRecursively()
                    runOnUiThread { status.text="모델 준비 완료"; importButton.isEnabled=true }
                } catch(e: Exception) {
                    runOnUiThread { status.text="가져오기 실패: ${e.message}"; importButton.isEnabled=true }
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(0xff0c131c.toInt()) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        fun label(text: String)=TextView(this).apply { this.text=text; setTextColor(-1); setPadding(16,8,16,8) }
        root.addView(label("MOBILITY INSPECTER · 2023 렉스턴 스포츠 엔진룸"))
        status=label("모델을 가져오면 실시간 탐지가 시작됩니다.")
        root.addView(status)
        val controls=LinearLayout(this)
        importButton=Button(this).apply { text="모델 ZIP"; setOnClickListener { picker.launch(arrayOf("application/zip","application/octet-stream")) } }
        controls.addView(importButton)
        controls.addView(Button(this).apply { text="카메라 권한"; setOnClickListener { permission.launch(Manifest.permission.CAMERA) } })
        controls.addView(Button(this).apply { text="일시정지"; setOnClickListener { paused=!paused; text=if(paused) "재개" else "일시정지" } })
        root.addView(controls)
        val confidence=label("신뢰도 기준: 35% · 박스를 눌러 부품 정보 확인")
        root.addView(confidence)
        root.addView(SeekBar(this).apply {
            max=85; progress=25
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    threshold=(value+10)/100f; confidence.text="신뢰도 기준: ${value+10}% · 박스를 눌러 부품 정보 확인"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
        view=InspectionView(this)
        root.addView(view,LinearLayout.LayoutParams(-1,0,1f))
        details=label("부품 탐지는 고장·손상 판정이 아닙니다.\n엔진 정지·냉각 후 외관을 확인하세요. 미검출은 부품 누락 판정이 아닙니다.")
        view.onSelection={ b -> details.text="${Parts.names[b.classId]} · 신뢰도 ${(100*b.score).toInt()}%\n${Parts.notes[b.classId]}\n좌표(px): ${b.x1.toInt()}, ${b.y1.toInt()} – ${b.x2.toInt()}, ${b.y2.toInt()}\n보이지 않는 부품은 판정할 수 없습니다. 고장·손상 판정이 아닙니다." }
        root.addView(details)
        setContentView(root)
        worker.execute {
            try {
                ModelPackage.active(this)?.let { detector=Detector(it) }
            } catch(e: Exception) { runOnUiThread { status.text="저장 모델 오류: ${e.message}. 모델 ZIP을 다시 가져오세요." } }
        }
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }
    private fun startCamera() {
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed) return@addListener
            try {
                provider=future.get()
                provider!!.unbindAll()
                analysis=ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(view.display?.rotation ?: 0)
                    .build()
                analysis!!.setAnalyzer(worker) { image ->
                    try {
                        if (!active || paused || !pending.compareAndSet(false,true)) return@setAnalyzer
                        val started=SystemClock.elapsedRealtime()
                        var frame: android.graphics.Bitmap?=null
                        try {
                            val ready=CameraFrame.bitmap(image)
                            frame=ready
                            val model=detector
                            val boxes=model?.detect(ready,threshold) ?: emptyList()
                            val elapsed=SystemClock.elapsedRealtime()-started
                            runOnUiThread {
                                try {
                                    if (!isDestroyed && active && !paused) {
                                        view.show(ready,boxes)
                                        val now=SystemClock.elapsedRealtime()
                                        val fps=if(lastShown==0L) 0f else 1000f/(now-lastShown).coerceAtLeast(1)
                                        lastShown=now
                                        status.text=if(model==null) "카메라 준비 · 모델 ZIP을 가져오세요." else "${boxes.size}개 부품 · 처리 ${elapsed}ms · 표시 %.1f FPS".format(fps)
                                    } else ready.recycle()
                                } finally { pending.set(false) }
                            }
                        } catch(e: Exception) {
                            frame?.recycle(); pending.set(false)
                            paused=true
                            runOnUiThread { status.text="분석 중지: ${e.message}. 모델 확인 후 재개하세요." }
                        }
                    } finally { image.close() }
                }
                provider!!.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,analysis!!)
            } catch(e: Exception) { status.text="카메라 시작 실패: ${e.message}" }
        },ContextCompat.getMainExecutor(this))
    }
    override fun onStart() { super.onStart(); active=true; lastShown=0L }
    override fun onStop() { active=false; super.onStop() }
    override fun onDestroy() {
        active=false
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        worker.execute { detector?.close(); detector=null }
        worker.shutdown()
        view.clear()
        super.onDestroy()
    }
}

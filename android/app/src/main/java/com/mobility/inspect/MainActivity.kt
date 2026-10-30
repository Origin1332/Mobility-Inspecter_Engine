package com.mobility.engine

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.CoordinateTransform
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
class MainActivity : ComponentActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val frameGate=FrameGate()
    private var detector: Detector?=null // Worker only.
    private var provider: ProcessCameraProvider?=null
    private var analysis: ImageAnalysis?=null
    private lateinit var view: InspectionView
    private lateinit var preview: PreviewView
    private lateinit var overlay: DetectionOverlay
    private data class AnalyzedFrame(val bitmap: Bitmap,val boxes: List<Detection>,val model: String?,val threshold: Float,val started: Long)
    private var latestFrame: AnalyzedFrame?=null
    private var expireOverlay: Runnable?=null
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var imageLabel: TextView
    private lateinit var modelButton: Button
    private lateinit var bundleButton: Button
    private lateinit var partsButton: Button
    private var bundle: InspectionBundle?=null // UI thread only.
    private var reviewIndex=0
    private var visibleBoxes: List<Detection> = emptyList()
    @Volatile private var paused=false
    @Volatile private var active=false
    @Volatile private var reviewing=true
    private var importing=false
    @Volatile private var threshold=.35f
    private var capturing=false
    private var lastShown=0L
    private var boundRotation=-1
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener=object: DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if(::preview.isInitialized && preview.display?.displayId==displayId &&
                preview.display?.rotation!=boundRotation && active && !reviewing && !paused) startCamera()
        }
    }

    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted && !reviewing) startCamera()
        else if(!granted) status.text="카메라 권한이 필요합니다. 데이터셋 검수는 권한 없이 가능합니다."
    }
    private val modelPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            frameGate.stop()
            if(!reviewing && !paused) clearLiveResult()
            modelButton.isEnabled=false
            status.text="모델 검증 중…"
            worker.execute {
                try {
                    val folder=ModelPackage.importPackage(this,uri)
                    val next=try { Detector(folder) } catch(e: Exception) { folder.deleteRecursively();throw e }
                    val oldFolder=ModelPackage.active(this)
                    if(!getSharedPreferences("model",0).edit().putString("active",folder.name).commit()) {
                        next.close();folder.deleteRecursively();error("모델 설정 저장 실패")
                    }
                    detector?.close();detector=next
                    oldFolder?.takeIf { it!=folder }?.deleteRecursively()
                    runOnUiThread { status.text="모델 준비 완료 · 실시간 버튼으로 시작";modelButton.isEnabled=true;if(active && !reviewing && !paused) startCamera() }
                } catch(e: Exception) {
                    runOnUiThread { status.text="모델 가져오기 실패: ${e.message}";modelButton.isEnabled=true;if(active && !reviewing && !paused) startCamera() }
                }
            }
        }
    }
    private val bundlePicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            reviewing=true;stopCamera();clearLiveResult();preview.visibility=View.GONE;overlay.visibility=View.GONE;view.visibility=View.VISIBLE;importing=true
            bundleButton.isEnabled=false;partsButton.isEnabled=false
            status.text="검수 팩과 카탈로그 확인 중…"
            worker.execute {
                try {
                    val next=InspectionBundle.import(this,uri)
                    runOnUiThread {
                        bundle=next;reviewIndex=0;importing=false
                        bundleButton.isEnabled=true;partsButton.isEnabled=true
                        if(!isDestroyed) showReview()
                    }
                } catch(e: Exception) {
                    runOnUiThread {
                        importing=false;bundleButton.isEnabled=true;partsButton.isEnabled=true
                        status.text="검수 팩 가져오기 실패: ${e.message}"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff0c131c.toInt()) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        fun label(text: String)=TextView(this).apply { this.text=text;setTextColor(-1);setPadding(16,6,16,6) }
        fun row(vararg buttons: Button) {
            root.addView(HorizontalScrollView(this).apply { addView(LinearLayout(this@MainActivity).apply {
                buttons.forEach { addView(it) }
            }) })
        }
        fun button(text: String,action: ()->Unit)=Button(this).apply { this.text=text;setOnClickListener { action() } }
        root.addView(label("MOBILITY · 엔진룸 부품 → 카탈로그"))
        status=label("검수 팩 ZIP을 가져오면 사진의 박스와 카탈로그를 확인할 수 있습니다.")
        root.addView(status)
        bundleButton=button("검수 팩 ZIP") { bundlePicker.launch(arrayOf("application/zip","application/octet-stream")) }
        modelButton=button("YOLO 모델 ZIP") { modelPicker.launch(arrayOf("application/zip","application/octet-stream")) }
        partsButton=button("카탈로그 검색") {
            if(!importing) bundle?.let { CatalogDialog.show(this,it) } ?: toast("검수 팩을 먼저 가져오세요.")
        }
        row(bundleButton,modelButton,partsButton,button("오프라인 웹 검사") {
            startActivity(android.content.Intent(this, WebInspectionActivity::class.java))
        })
        row(button("검수 사진") { if(!importing) showReview() },button("실시간 카메라") { if(!importing) cameraMode() },
            button("이전 사진") { if(!importing && reviewing && reviewIndex>0) {reviewIndex--;showReview()} },
            button("다음 사진") { if(!importing && reviewing && reviewIndex+1<(bundle?.images?.size ?: 0)) {reviewIndex++;showReview()} })
        imageLabel=label("검수 라벨과 AI 탐지는 별도 모드입니다.");root.addView(imageLabel)
        val confidence=label("AI 신뢰도 기준: 35% · 검수 박스에는 적용하지 않음");root.addView(confidence)
        root.addView(SeekBar(this).apply {
            max=85;progress=25
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?,value: Int,fromUser: Boolean) {
                    threshold=(value+10)/100f;confidence.text="AI 신뢰도 기준: ${value+10}% · 검수 박스에는 적용하지 않음"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
        row(button("분석 장면 고정·검사") { captureInspection() },button("분석 장면 일시정지/재개") { if(!reviewing && !capturing) togglePause() },button("화면 부품 목록") {
            if(visibleBoxes.isEmpty()) toast("표시된 부품이 없습니다.") else {
                val boxes=visibleBoxes.toList()
                android.app.AlertDialog.Builder(this).setTitle("부품을 선택하세요")
                    .setItems(boxes.mapIndexed { i,b->"${i+1}. ${Parts.names[b.classId]}" }.toTypedArray()) { _,i->select(boxes[i]) }.show()
            }
        })
        preview=PreviewView(this).apply {
            implementationMode=PreviewView.ImplementationMode.COMPATIBLE
            scaleType=PreviewView.ScaleType.FIT_CENTER
            visibility=View.GONE
        }
        overlay=DetectionOverlay(this).apply {visibility=View.GONE;onSelection={ select(it) }}
        view=InspectionView(this);view.onSelection={ select(it) }
        val cameraArea=FrameLayout(this)
        cameraArea.addView(preview,FrameLayout.LayoutParams(-1,-1))
        cameraArea.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        cameraArea.addView(view,FrameLayout.LayoutParams(-1,-1))
        root.addView(cameraArea,LinearLayout.LayoutParams(-1,0,1f))
        preview.addOnLayoutChangeListener { _,l,t,r,b,ol,ot,or,ob ->
            if(r-l>0 && b-t>0 && (r-l!=or-ol || b-t!=ob-ot) && active && !reviewing && !paused) startCamera()
        }
        details=label("박스 또는 부품 목록을 누르면 관련 품번·부속품·원문 도면을 찾습니다.")
        details.maxLines=4;root.addView(details)
        setContentView(root)
        worker.execute {
            var modelError: String?=null
            try { ModelPackage.active(this)?.let { detector=Detector(it) } } catch(e: Exception) {modelError=e.message}
            try {
                val loaded=InspectionBundle.active(this)
                runOnUiThread {
                    if(!isDestroyed && !importing && bundle==null && loaded!=null) { bundle=loaded;if(reviewing) showReview() }
                    modelError?.let { status.text="저장 모델 오류: $it · 모델 ZIP을 다시 가져오세요." }
                }
            } catch(e: Exception) { runOnUiThread { status.text="저장 검수 팩 오류: ${e.message}" } }
        }
    }
    private fun toast(text: String)=Toast.makeText(this,text,Toast.LENGTH_SHORT).show()
    private fun select(box: Detection) {
        if(importing) return
        details.text="${Parts.names[box.classId]} · "+(if(reviewing) "검수 라벨" else "AI ${(box.score*100).toInt()}%")+
            "\n좌표: ${box.x1.toInt()}, ${box.y1.toInt()} – ${box.x2.toInt()}, ${box.y2.toInt()}"+
            "\n${Parts.notes[box.classId]}"
        val pack=bundle
        if(pack!=null) CatalogDialog.show(this,pack,box.classId) else toast("카탈로그를 사용하려면 검수 팩 ZIP을 가져오세요.")
    }
    private fun showReview() {
        val pack=bundle ?: run { toast("검수 팩 ZIP을 먼저 가져오세요.");return }
        reviewing=true;paused=false;stopCamera();clearLiveResult();preview.visibility=View.GONE;overlay.visibility=View.GONE;view.visibility=View.VISIBLE;view.clear();visibleBoxes=emptyList()
        reviewIndex=reviewIndex.coerceIn(0,pack.images.lastIndex)
        val item=pack.images[reviewIndex]
        val bitmap=BitmapFactory.decodeFile(File(pack.folder,"images/"+item.fileName).absolutePath)
        if(bitmap==null) {status.text="사진 읽기 실패";return}
        visibleBoxes=item.boxes
        view.show(bitmap,item.boxes,true)
        status.text="검수 라벨 · ${item.boxes.size}개 박스 · AI 예측 결과가 아닙니다."
        imageLabel.text="${reviewIndex+1}/${pack.images.size} · ${item.fileName} · ${item.width}×${item.height}"
        details.text="박스를 누르거나 화면 부품 목록에서 선택하세요. 라벨이 없는 부품은 없다고 판정하지 않습니다."
    }
    private fun clearLiveResult() {
        expireOverlay?.let {overlay.removeCallbacks(it)};expireOverlay=null
        overlay.clear();visibleBoxes=emptyList()
        latestFrame?.bitmap?.recycle();latestFrame=null;lastShown=0L
    }
    private fun stopCamera() {
        frameGate.stop();analysis?.clearAnalyzer();provider?.unbindAll()
    }
    private fun cameraMode() {
        if(capturing) return
        reviewing=false;paused=false;view.clear();clearLiveResult()
        view.visibility=View.GONE;preview.visibility=View.VISIBLE;overlay.visibility=View.VISIBLE
        imageLabel.text="실시간 미리보기 · 박스는 최근 분석 결과 · 고정은 마지막 분석 사진 기준"
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }
    private fun togglePause() {
        if(paused) {cameraMode();return}
        freezeAnalyzedFrame()
    }
    private fun freezeAnalyzedFrame(): Boolean {
        val frame=latestFrame ?: run {toast("분석 프레임이 아직 없습니다. 잠시 기다려 주세요.");return false}
        val frozen=frame.bitmap.copy(Bitmap.Config.ARGB_8888,false) ?: return false
        paused=true;stopCamera()
        expireOverlay?.let {overlay.removeCallbacks(it)};expireOverlay=null
        overlay.clear();overlay.visibility=View.GONE;preview.visibility=View.GONE;view.visibility=View.VISIBLE
        visibleBoxes=frame.boxes;view.show(frozen,frame.boxes,false)
        val age=SystemClock.elapsedRealtime()-frame.started
        imageLabel.text="고정된 분석 사진 · %.1f초 전 프레임 · 사진과 박스가 같은 프레임입니다.".format(age/1000.0)
        status.text="분석 장면 일시정지 · 재개 또는 검사 편집으로 진행하세요."
        return true
    }
    private fun startCamera() {
        if(!active || reviewing || paused || isDestroyed || preview.width==0 || preview.height==0) return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) return
        val token=frameGate.start()
        clearLiveResult()
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            if(!frameGate.accepts(token) || !active || reviewing || paused || isDestroyed) return@addListener
            try {
                provider=future.get();provider!!.unbindAll()
                val rotation=preview.display?.rotation ?: 0
                boundRotation=rotation
                val viewport=preview.viewPort ?: error("미리보기 영역이 준비되지 않았습니다.")
                val cameraPreview=Preview.Builder().setTargetRotation(rotation).build()
                cameraPreview.setSurfaceProvider(preview.surfaceProvider)
                val analyzer=ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280,960),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(rotation).build()
                analysis=analyzer
                val transformFactory=ImageProxyTransformFactory().apply {
                    isUsingCropRect=true;isUsingRotationDegrees=true
                }
                analyzer.setAnalyzer(worker) { image ->
                    var imageClosed=false
                    try {
                        if(!active || paused || reviewing || !frameGate.acquire(token)) return@setAnalyzer
                        val started=SystemClock.elapsedRealtime()
                        var bitmap: Bitmap?=null
                        try {
                            val transform=transformFactory.getOutputTransform(image)
                            val ready=CameraFrame.bitmap(image);bitmap=ready
                            image.close();imageClosed=true
                            val model=detector;val usedThreshold=threshold
                            val boxes=model?.detect(ready,usedThreshold) ?: emptyList()
                            val modelJson=model?.manifestJson
                            val elapsed=SystemClock.elapsedRealtime()-started
                            runOnUiThread {
                                try {
                                    if(!isDestroyed && active && !paused && !reviewing && frameGate.accepts(token)) {
                                        latestFrame?.bitmap?.recycle()
                                        val result=AnalyzedFrame(ready,boxes,modelJson,usedThreshold,started)
                                        latestFrame=result
                                        expireOverlay?.let {overlay.removeCallbacks(it)}
                                        overlay.clear();visibleBoxes=emptyList()
                                        val now=SystemClock.elapsedRealtime()
                                        val hz=if(lastShown==0L) 0f else 1000f/(now-lastShown).coerceAtLeast(1)
                                        lastShown=now
                                        val target=preview.outputTransform
                                        if(target!=null && FrameGate.fresh(now,started)) {
                                            val mapping=CoordinateTransform(transform,target)
                                            overlay.show(boxes.map { box ->
                                                val rect=RectF(box.x1,box.y1,box.x2,box.y2);mapping.mapRect(rect);box to rect
                                            })
                                            visibleBoxes=boxes
                                            expireOverlay=Runnable {
                                                if(latestFrame===result && !paused && !reviewing) {
                                                    overlay.clear();visibleBoxes=emptyList()
                                                    status.text="박스 갱신 대기 · 오래된 결과를 숨겼습니다. 분석 장면 고정으로 확인하세요."
                                                }
                                            }.also {overlay.postDelayed(it,(FrameGate.MAX_OVERLAY_AGE_MS-(now-started)).coerceAtLeast(1))}
                                        }
                                        status.text=if(model==null) "미리보기 실행 중 · 학습된 모델 ZIP을 가져오세요." else
                                            "${boxes.size}개 탐지 · 분석 ${elapsed}ms · 갱신 %.1f회/초".format(hz)+
                                                if(!FrameGate.fresh(now,started)) " · 지연된 박스 숨김 / 장면 고정 권장" else if(target==null) " · 좌표 준비 중" else ""
                                    } else ready.recycle()
                                } catch(e: Exception) {
                                    overlay.clear();visibleBoxes=emptyList()
                                    status.text="박스 표시 실패: ${e.message} · 미리보기는 계속됩니다."
                                } finally {frameGate.release()}
                            }
                        } catch(e: Exception) {
                            bitmap?.recycle();frameGate.release()
                            runOnUiThread {
                                if(frameGate.accepts(token)) {
                                    frameGate.stop();overlay.clear();visibleBoxes=emptyList()
                                    status.text="분석 중지: ${e.message} · 미리보기 유지 / 실시간 카메라로 재시도"
                                }
                            }
                        }
                    } finally {if(!imageClosed) image.close()}
                }
                provider!!.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,
                    UseCaseGroup.Builder().setViewPort(viewport).addUseCase(cameraPreview).addUseCase(analyzer).build())
            } catch(e: Exception) {
                frameGate.stop();status.text="카메라 시작 실패: ${e.message}"
            }
        },ContextCompat.getMainExecutor(this))
    }
    private fun captureInspection() {
        if(reviewing || capturing) { toast("실시간 카메라 장면에서 사용하세요.");return }
        if(!paused && !freezeAnalyzedFrame()) return
        val frame=latestFrame ?: run {toast("분석 프레임이 없습니다.");return}
        val bitmap=view.snapshot() ?: run { toast("카메라 화면이 준비되지 않았습니다.");return }
        capturing=true
        val boxes=frame.boxes
        val metadata=org.json.JSONObject().put("width",bitmap.width).put("height",bitmap.height)
            .put("frame_age_ms",SystemClock.elapsedRealtime()-frame.started).put("threshold",frame.threshold).put("model",frame.model?.let { org.json.JSONObject(it) } ?: org.json.JSONObject.NULL)
        val rows=org.json.JSONArray()
        boxes.forEach { b -> rows.put(org.json.JSONObject().put("class",Parts.ids[b.classId]).put("score",b.score.toDouble())
            .put("box",org.json.JSONArray(listOf(b.x1.toDouble(),b.y1.toDouble(),b.x2.toDouble(),b.y2.toDouble())))) }
        metadata.put("detections",rows)
        worker.execute {
            try {
                val folder=File(cacheDir,"inspection-capture").apply { mkdirs() }
                File(folder,"current.jpg").outputStream().use { require(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it)) }
                File(folder,"current.json").writeText(metadata.toString())
                runOnUiThread { capturing=false;if(!isDestroyed) startActivity(android.content.Intent(this,WebInspectionActivity::class.java).putExtra("capture",true)) }
            } catch(e: Exception) {runOnUiThread { capturing=false;status.text="장면 저장 실패: ${e.message}" }}
            finally {bitmap.recycle()}
        }
    }
    override fun onStart() {
        super.onStart();active=true;lastShown=0L
        displayManager.registerDisplayListener(displayListener,Handler(Looper.getMainLooper()))
        if(::preview.isInitialized && !reviewing && !paused) preview.post {startCamera()}
    }
    override fun onStop() {
        active=false;displayManager.unregisterDisplayListener(displayListener);stopCamera()
        if(::overlay.isInitialized) {
            expireOverlay?.let {overlay.removeCallbacks(it)};expireOverlay=null
            overlay.clear();if(!paused && !reviewing) clearLiveResult()
        }
        super.onStop()
    }
    override fun onDestroy() {
        active=false;stopCamera();clearLiveResult()
        worker.execute {detector?.close();detector=null};worker.shutdown();view.clear()
        super.onDestroy()
    }
}

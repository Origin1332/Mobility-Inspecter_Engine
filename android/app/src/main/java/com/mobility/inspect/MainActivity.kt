package com.mobility.engine

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val pending=AtomicBoolean(false)
    private var detector: Detector?=null // Worker only.
    private var provider: ProcessCameraProvider?=null
    private var analysis: ImageAnalysis?=null
    private lateinit var view: InspectionView
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
    private var lastShown=0L

    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted && !reviewing) startCamera()
        else if(!granted) status.text="카메라 권한이 필요합니다. 데이터셋 검수는 권한 없이 가능합니다."
    }
    private val modelPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
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
                    runOnUiThread { status.text="모델 준비 완료 · 실시간 버튼으로 시작";modelButton.isEnabled=true }
                } catch(e: Exception) {
                    runOnUiThread { status.text="모델 가져오기 실패: ${e.message}";modelButton.isEnabled=true }
                }
            }
        }
    }
    private val bundlePicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            reviewing=true;provider?.unbindAll();importing=true
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
        row(button("일시정지/재개") { if(!reviewing) paused=!paused },button("화면 부품 목록") {
            if(visibleBoxes.isEmpty()) toast("표시된 부품이 없습니다.") else {
                val boxes=visibleBoxes.toList()
                android.app.AlertDialog.Builder(this).setTitle("부품을 선택하세요")
                    .setItems(boxes.mapIndexed { i,b->"${i+1}. ${Parts.names[b.classId]}" }.toTypedArray()) { _,i->select(boxes[i]) }.show()
            }
        })
        view=InspectionView(this);view.onSelection={ select(it) }
        root.addView(view,LinearLayout.LayoutParams(-1,0,1f))
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
        reviewing=true;provider?.unbindAll();view.clear();visibleBoxes=emptyList()
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
    private fun cameraMode() {
        reviewing=false;paused=false;lastShown=0L;visibleBoxes=emptyList();view.clear()
        imageLabel.text="실시간 카메라 · 학습된 YOLO 모델 필요"
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }
    private fun startCamera() {
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            if(isDestroyed || reviewing) return@addListener
            try {
                provider=future.get();provider!!.unbindAll()
                analysis=ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280,960),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(view.display?.rotation ?: 0).build()
                analysis!!.setAnalyzer(worker) { image ->
                    try {
                        if(!active || paused || reviewing || !pending.compareAndSet(false,true)) return@setAnalyzer
                        val started=SystemClock.elapsedRealtime()
                        var frame: android.graphics.Bitmap?=null
                        try {
                            val ready=CameraFrame.bitmap(image);frame=ready
                            val model=detector;val boxes=model?.detect(ready,threshold) ?: emptyList()
                            val elapsed=SystemClock.elapsedRealtime()-started
                            runOnUiThread {
                                try {
                                    if(!isDestroyed && active && !paused && !reviewing) {
                                        visibleBoxes=boxes;view.show(ready,boxes,false)
                                        val now=SystemClock.elapsedRealtime()
                                        val fps=if(lastShown==0L) 0f else 1000f/(now-lastShown).coerceAtLeast(1)
                                        lastShown=now
                                        status.text=if(model==null) "카메라 준비 · 학습된 모델 ZIP을 가져오세요." else
                                            "${boxes.size}개 부품 · ${elapsed}ms · %.1f FPS".format(fps)
                                    } else ready.recycle()
                                } finally { pending.set(false) }
                            }
                        } catch(e: Exception) {
                            frame?.recycle();pending.set(false);paused=true
                            runOnUiThread {status.text="분석 중지: ${e.message}"}
                        }
                    } finally {image.close()}
                }
                provider!!.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,analysis!!)
            } catch(e: Exception) {status.text="카메라 시작 실패: ${e.message}"}
        },ContextCompat.getMainExecutor(this))
    }
    override fun onStart() {super.onStart();active=true;lastShown=0L}
    override fun onStop() {active=false;super.onStop()}
    override fun onDestroy() {
        active=false;analysis?.clearAnalyzer();provider?.unbindAll()
        worker.execute {detector?.close();detector=null};worker.shutdown();view.clear()
        super.onDestroy()
    }
}

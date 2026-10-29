package com.mobility.engine

import android.app.AlertDialog
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.*

object CatalogDialog {
    fun show(context: Context,bundle: InspectionBundle,classId: Int?=null) {
        val cls=classId?.let { Parts.ids[it] }
        val root=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(20,10,20,10) }
        val search=EditText(context).apply { hint="품번·부품명·옵션 검색";isSingleLine=true }
        root.addView(search)
        root.addView(TextView(context).apply { text=bundle.notice;setPadding(0,8,0,8) })
        val count=TextView(context);root.addView(count)
        val list=ListView(context)
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        val check=CheckBox(context).apply { text="이 부품과 같은 도면의 관련 부속 포함";isChecked=true }
        root.addView(check)
        var rows=emptyList<CatalogEntry>()
        fun refresh() {
            rows=CatalogSearch.find(bundle.entries,cls,search.text.toString()).filter {
                cls==null || check.isChecked || it.relations[cls]=="primary"
            }
            count.text="${rows.size}개 후보 · 항목을 누르면 적용 조건과 원문 보기"
            list.adapter=ArrayAdapter(context,android.R.layout.simple_list_item_1,rows.map { r ->
                val relation=if(cls==null) r.groupCode else if(r.relations[cls]=="primary") "본체/주요 구성" else "같은 도면 관련 부속"
                "${r.nameKo}\n${r.partNumber} · ${relation}\n옵션: ${r.options.ifBlank { "원문 확인" }}"
            })
        }
        search.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) {}
        })
        check.setOnCheckedChangeListener { _,_->refresh() }
        list.setOnItemClickListener { _,_,position,_->details(context,bundle,rows[position]) }
        val dialog=AlertDialog.Builder(context).setTitle(classId?.let { Parts.names[it]+" · 카탈로그" } ?: "엔진룸 카탈로그 검색")
            .setView(root).setNegativeButton("닫기",null).create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,(context.resources.displayMetrics.heightPixels*.9).toInt())
        }
        refresh();dialog.show()
    }

    private fun details(context: Context,bundle: InspectionBundle,r: CatalogEntry) {
        fun shown(s: String)=s.takeIf { it.isNotBlank() && it!="'" } ?: "표기 없음 · 원문 확인"
        val body=TextView(context).apply {
            setPadding(24,16,24,16);setTextIsSelectable(true)
            text="""품번: ${r.partNumber}
품명: ${r.nameKo}
${r.nameEn}
도면 그룹: ${r.groupCode} · 지시번호 [${r.callout}]
옵션: ${shown(r.options)}
적용 시작: ${shown(r.fromDate)}
적용 종료: ${shown(r.toDate)}
변속기: ${shown(r.transmission)} · 좌/우: ${shown(r.side)}
모델별 수량 원문: ${shown(r.modelQuantities)}
비고: ${shown(r.remarks)}
출처: ${bundle.title}
PDF ${r.page}쪽 / 인쇄 ${r.printedPage}쪽

${bundle.notice}
사진에서 탐지한 것은 부품 종류입니다. 표시된 품번이 장착 차량의 확정 품번이라는 뜻은 아닙니다."""
        }
        val root=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        root.addView(ScrollView(context).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        root.addView(Button(context).apply { text="품번 복사";setOnClickListener {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("품번",r.partNumber))
            Toast.makeText(context,"품번 복사 완료",Toast.LENGTH_SHORT).show()
        } })
        val dialog=AlertDialog.Builder(context).setTitle(r.nameKo).setView(root)
            .setPositiveButton("품번표 원문") { _,_->showPdf(context,bundle,r.page,r) }
            .setNeutralButton("분해도 보기") { _,_->showPdf(context,bundle,r.diagramPages.firstOrNull() ?: r.page,null) }
            .setNegativeButton("닫기",null).create()
        dialog.setOnShowListener { dialog.window?.setLayout(-1,(context.resources.displayMetrics.heightPixels*.85).toInt()) }
        dialog.show()
    }

    private fun showPdf(context: Context,bundle: InspectionBundle,start: Int,highlight: CatalogEntry?) {
        val fd=ParcelFileDescriptor.open(bundle.pdf,ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer=try { PdfRenderer(fd) } catch(e: Exception) { fd.close();throw e }
        val root=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(12,8,12,8) }
        val controls=LinearLayout(context)
        var page=(start-1).coerceIn(0,renderer.pageCount-1)
        var zoom=1f
        var bitmap: Bitmap?=null
        val label=TextView(context)
        val image=ImageView(context)
        val vertical=ScrollView(context)
        val horizontal=HorizontalScrollView(context)
        horizontal.addView(image)
        vertical.addView(horizontal)
        fun render() {
            renderer.openPage(page).use { p ->
                val width=(context.resources.displayMetrics.widthPixels*zoom).toInt().coerceIn(400,1800)
                val height=(width.toFloat()*p.height/p.width).toInt()
                val next=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                next.eraseColor(Color.WHITE)
                p.render(next,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                if(highlight!=null && page+1==highlight.page) {
                    val sx=width/highlight.pageWidth;val sy=height/highlight.pageHeight
                    val b=highlight.rowRect
                    val paint=Paint().apply { color=Color.RED;style=Paint.Style.STROKE;strokeWidth=3f }
                    Canvas(next).drawRect(b[0]*sx,b[1]*sy,b[2]*sx,b[3]*sy,paint)
                }
                image.setImageBitmap(next);bitmap?.recycle();bitmap=next
                image.layoutParams=android.widget.FrameLayout.LayoutParams(width,height)
                label.text="PDF ${page+1} / ${renderer.pageCount}쪽"
            }
        }
        fun button(text: String,action: ()->Unit)=Button(context).apply { this.text=text;setOnClickListener { action() } }
        controls.addView(button("이전") { if(page>0) {page--;render()} })
        controls.addView(button("다음") { if(page+1<renderer.pageCount) {page++;render()} })
        controls.addView(button("−") { zoom=(zoom-.25f).coerceAtLeast(.5f);render() })
        controls.addView(button("+") { zoom=(zoom+.25f).coerceAtMost(2f);render() })
        root.addView(controls);root.addView(label)
        root.addView(vertical,LinearLayout.LayoutParams(-1,0,1f))
        val dialog=AlertDialog.Builder(context).setTitle("카탈로그 원문").setView(root).setNegativeButton("닫기",null).create()
        dialog.setOnDismissListener { image.setImageDrawable(null);bitmap?.recycle();renderer.close();fd.close() }
        dialog.setOnShowListener {
            dialog.window?.setLayout(-1,(context.resources.displayMetrics.heightPixels*.95).toInt())
            try { render() } catch(e: Exception) { label.text="PDF 표시 실패: ${e.message}" }
        }
        dialog.show()
    }
}

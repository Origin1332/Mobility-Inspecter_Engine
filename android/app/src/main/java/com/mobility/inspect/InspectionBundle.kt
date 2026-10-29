package com.mobility.engine

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class ReviewImage(val fileName: String,val width: Int,val height: Int,
                       val boxes: List<Detection>,val source: String)
data class InspectionBundle(val folder: File,val images: List<ReviewImage>,
                            val entries: List<CatalogEntry>,val pageCount: Int,
                            val title: String,val notice: String) {
    val pdf get()=File(folder,"catalog.pdf")
    companion object {
        private fun JSONArray.strings()=(0 until length()).map { getString(it) }
        private fun digest(file: File): String {
            val md=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer=ByteArray(65536)
                while(true) { val n=input.read(buffer); if(n<0) break; md.update(buffer,0,n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        fun active(context: Context): InspectionBundle? {
            val name=context.getSharedPreferences("inspection",0).getString("active",null) ?: return null
            require(name.matches(Regex("inspection-[a-f0-9-]+")))
            return load(File(context.filesDir,name))
        }
        fun import(context: Context,uri: Uri): InspectionBundle {
            val folder=File(context.filesDir,"inspection-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val seen=mutableSetOf<String>()
                context.contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source)
                    ZipInputStream(source).use { zip ->
                        var total=0L
                        while(true) {
                            val e=zip.nextEntry ?: break
                            require(!e.isDirectory && seen.add(e.name) && seen.size<=6003) { "중복/잘못된 ZIP 항목" }
                            require(e.name in setOf("inspection.json","catalog.json","catalog.pdf") ||
                                e.name.matches(Regex("images/[A-Za-z0-9_-]+\\.jpg"))) { "허용되지 않은 ZIP 경로" }
                            val target=File(folder,e.name)
                            target.parentFile?.mkdirs()
                            val limit=when {
                                e.name.endsWith(".json")->8L*1024*1024
                                e.name.endsWith(".pdf")->32L*1024*1024
                                else->20L*1024*1024
                            }
                            target.outputStream().use { output ->
                                var bytes=0L; val block=ByteArray(65536)
                                while(true) {
                                    val n=zip.read(block);if(n<0) break
                                    bytes+=n;total+=n
                                    require(bytes<=limit && total<=128L*1024*1024) { "패키지 크기 제한 초과" }
                                    output.write(block,0,n)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                val result=load(folder)
                val expected=setOf("inspection.json","catalog.json","catalog.pdf")+
                    result.images.map { "images/"+it.fileName }
                require(seen==expected) { "이미지 목록과 ZIP 내용이 다릅니다" }
                val prefs=context.getSharedPreferences("inspection",0)
                val old=prefs.getString("active",null)
                require(prefs.edit().putString("active",folder.name).commit()) { "검수 팩 저장 실패" }
                if(old!=null && old.matches(Regex("inspection-[a-f0-9-]+"))) File(context.filesDir,old).deleteRecursively()
                return result
            } catch(e: Exception) { folder.deleteRecursively();throw e }
        }
        fun load(folder: File): InspectionBundle {
            val doc=JSONObject(File(folder,"inspection.json").readText())
            val catalog=JSONObject(File(folder,"catalog.json").readText())
            require(doc.getInt("schema_version")==1 && catalog.getInt("schema_version")==1)
            require(doc.getString("class_schema")=="engine-seed-v2")
            require(doc.getJSONArray("classes").strings()==Parts.ids) { "12종 클래스 순서 불일치" }
            require(doc.getString("annotation_kind")=="reviewed_boxes")
            val hash=digest(File(folder,"catalog.pdf"))
            require(hash==doc.getString("catalog_sha256") && hash==catalog.getString("source_sha256")) { "카탈로그 체크섬 불일치" }
            val pageCount=ParcelFileDescriptor.open(File(folder,"catalog.pdf"),ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            }
            require(pageCount==catalog.getInt("page_count"))
            val jsonRows=catalog.getJSONArray("rows")
            require(jsonRows.length() in 1..15000)
            val seenRows=mutableSetOf<String>()
            val entries=(0 until jsonRows.length()).map { i ->
                val r=jsonRows.getJSONObject(i)
                val id=r.getString("id"); require(seenRows.add(id))
                val page=r.getInt("pdf_page"); require(page in 1..pageCount)
                val diagram=r.getJSONArray("diagram_pages")
                val diagrams=(0 until diagram.length()).map { diagram.getInt(it).also { n->require(n in 1..pageCount) } }
                val rect=r.getJSONArray("row_rect");require(rect.length()==4)
                val bounds=(0..3).map { rect.getDouble(it).toFloat() }
                val pw=r.getDouble("page_width").toFloat();val ph=r.getDouble("page_height").toFloat()
                require((bounds+listOf(pw,ph)).all { it.isFinite() } && pw>0 && ph>0)
                require(bounds[0]>=0 && bounds[1]>=0 && bounds[0]<bounds[2] && bounds[1]<bounds[3] &&
                    bounds[2]<=pw && bounds[3]<=ph)
                val relation=r.getJSONObject("relations")
                val relations=relation.keys().asSequence().associateWith { key ->
                    require(key in Parts.ids)
                    relation.getString(key).also { value->require(value in setOf("primary","related")) }
                }
                require(relations.isNotEmpty())
                CatalogEntry(id,r.getString("part_number"),r.getString("name_ko"),r.getString("name_en"),
                    r.getString("group_code"),r.getString("group_title"),r.getString("callout"),
                    r.getString("options"),r.getString("from_date"),r.getString("to_date"),r.getString("remarks"),
                    r.getString("side"),r.getString("transmission"),r.getString("model_quantities"),
                    page,r.getString("printed_page"),diagrams,bounds,pw,ph,relations)
            }
            val jsonImages=doc.getJSONArray("images")
            require(jsonImages.length() in 1..5000)
            val seenNames=mutableSetOf<String>()
            val images=(0 until jsonImages.length()).map { i ->
                val r=jsonImages.getJSONObject(i)
                val name=r.getString("file_name")
                require(name.matches(Regex("[A-Za-z0-9_-]+\\.jpg")) && seenNames.add(name.lowercase()))
                val width=r.getInt("width");val height=r.getInt("height")
                require(width in 1..1280 && height in 1..1280)
                val image=File(folder,"images/"+name)
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(image.absolutePath,bounds)
                require(bounds.outWidth==width && bounds.outHeight==height && bounds.outMimeType=="image/jpeg")
                require(digest(image)==r.getString("file_sha256")) { "이미지 체크섬 불일치" }
                val objects=r.getJSONArray("objects");require(objects.length()<=200)
                val boxes=(0 until objects.length()).map { j ->
                    val obj=objects.getJSONObject(j)
                    val cls=Parts.ids.indexOf(obj.getString("class"));require(cls>=0)
                    require(entries.any { it.relations.containsKey(Parts.ids[cls]) })
                    val bbox=obj.getJSONArray("bbox");require(bbox.length()==4)
                    val b=(0..3).map { bbox.getDouble(it).toFloat() }
                    require(b.all { it.isFinite() } && b[0]>=0 && b[1]>=0 &&
                        b[0]<b[2] && b[1]<b[3] && b[2]<=width && b[3]<=height)
                    Detection(cls,1f,b[0],b[1],b[2],b[3])
                }
                ReviewImage(name,width,height,boxes,r.optJSONObject("source")?.optString("source_page") ?: "")
            }
            return InspectionBundle(folder,images,entries,pageCount,catalog.getString("title"),catalog.getString("notice"))
        }
    }
}

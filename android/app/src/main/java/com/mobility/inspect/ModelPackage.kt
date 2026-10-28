package com.mobility.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

object ModelPackage {
    fun active(context: Context): File? {
        val name=context.getSharedPreferences("model",0).getString("active",null) ?: return null
        if (!name.matches(Regex("model-[a-f0-9-]+"))) return null
        return File(context.filesDir,name).takeIf { it.isDirectory }
    }
    // Unique directories + atomic preference switch preserve the last valid package on failure.
    fun importPackage(context: Context, uri: Uri): File {
        val dir=File(context.filesDir,"model-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val seen=mutableSetOf<String>()
            context.contentResolver.openInputStream(uri).use { source ->
                requireNotNull(source) { "파일을 열 수 없습니다" }
                ZipInputStream(source).use { zip ->
                    var total=0L
                    while (true) {
                        val entry=zip.nextEntry ?: break
                        require(entry.name in setOf("model.onnx","manifest.json") && seen.add(entry.name)) { "모델 ZIP 파일 구성이 올바르지 않습니다" }
                        File(dir,entry.name).outputStream().use { output ->
                            val block=ByteArray(65536)
                            var fileBytes=0L
                            while (true) {
                                val n=zip.read(block); if (n < 0) break
                                total+=n; fileBytes+=n
                                require(total <= 100L*1024*1024 && (entry.name != "manifest.json" || fileBytes <= 65536)) { "모델 패키지가 너무 큽니다" }
                                output.write(block,0,n)
                            }
                        }
                        zip.closeEntry()
                    }
            }
            }
            require(seen == setOf("model.onnx","manifest.json")) { "모델과 manifest가 필요합니다" }
            Detector(dir).use { /* Validate before activation. */ }
            return dir
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }
}

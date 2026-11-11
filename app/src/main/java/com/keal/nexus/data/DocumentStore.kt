package com.keal.nexus.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

data class NexusDocument(
    val id: String,
    val name: String,
    val mimeType: String,
    val text: String,
    val importedAt: Long
)

class DocumentStore(private val context: Context) {
    private val root = File(context.filesDir, "documents").apply { mkdirs() }
    private val index = File(root, "index.json")

    fun list(): List<NexusDocument> {
        return try {
            val json = org.json.JSONArray(index.takeIf { it.exists() }?.readText() ?: "[]")
            (0 until json.length()).mapNotNull { i ->
                val item = json.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optString("id")
                val textFile = File(root, "$id.txt")
                if (id.isBlank() || !textFile.isFile) {
                    null
                } else {
                    NexusDocument(
                        id = id,
                        name = item.optString("name"),
                        mimeType = item.optString("mime"),
                        text = textFile.readText(Charsets.UTF_8),
                        importedAt = item.optLong("importedAt")
                    )
                }
            }.sortedByDescending { it.importedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun import(uri: Uri): NexusDocument {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "document"
        val mime = resolver.getType(uri).orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        val allowed = setOf(
            "pdf", "docx", "txt", "md", "csv", "html", "htm", "json",
            "xml", "log", "kt", "java", "py", "js", "ts", "css", "sql", "yaml", "yml"
        )
        require(extension in allowed) {
            "Supported formats: PDF, DOCX, TXT, MD, CSV, HTML, JSON, XML and common source-code text files."
        }

        val input = resolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected document.")
        val extracted = input.use { stream ->
            when (extension) {
                "pdf" -> {
                    PDFBoxResourceLoader.init(context)
                    PDDocument.load(stream).use { pdf ->
                        require(!pdf.isEncrypted) { "Password-protected PDFs are not supported yet." }
                        PDFTextStripper().getText(pdf)
                    }
                }
                "docx" -> extractDocx(stream)
                "html", "htm" -> Html.fromHtml(
                    stream.bufferedReader(Charsets.UTF_8).readText(),
                    Html.FROM_HTML_MODE_COMPACT
                ).toString()
                else -> stream.bufferedReader(Charsets.UTF_8).readText()
            }
        }.replace("\u0000", "").trim()

        require(extracted.isNotBlank()) {
            "No selectable text was found. Scanned/image-only PDFs need OCR, which is not included yet."
        }
        require(extracted.length <= MAX_DOCUMENT_CHARS) {
            "This document is too large to import (maximum 1 MB of extracted text)."
        }

        val id = UUID.randomUUID().toString()
        File(root, "$id.txt").writeText(extracted, Charsets.UTF_8)
        val items = org.json.JSONArray(index.takeIf { it.exists() }?.readText() ?: "[]")
        items.put(
            org.json.JSONObject()
                .put("id", id)
                .put("name", name)
                .put("mime", mime)
                .put("importedAt", System.currentTimeMillis())
        )
        index.writeText(items.toString(), Charsets.UTF_8)
        return NexusDocument(id, name, mime, extracted, System.currentTimeMillis())
    }

    fun delete(id: String) {
        require(id.matches(Regex("[A-Za-z0-9-]{10,50}"))) { "Invalid document ID." }
        val existing = org.json.JSONArray(index.takeIf { it.exists() }?.readText() ?: "[]")
        val updated = org.json.JSONArray()
        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i) ?: continue
            if (item.optString("id") != id) updated.put(item)
        }
        File(root, "$id.txt").delete()
        index.writeText(updated.toString(), Charsets.UTF_8)
    }

    private fun extractDocx(input: java.io.InputStream): String {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val parser = Xml.newPullParser()
                    parser.setInput(zip, "UTF-8")
                    val out = StringBuilder()
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG && parser.name == "t") {
                            out.append(parser.nextText())
                        } else if (event == XmlPullParser.END_TAG && parser.name == "p") {
                            out.append('\n')
                        }
                        event = parser.eventType
                        if (event != XmlPullParser.END_DOCUMENT) event = parser.next()
                    }
                    return out.toString()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        throw IOException("Could not find readable text in this DOCX document.")
    }

    companion object {
        const val MAX_DOCUMENT_CHARS = 1_000_000
    }
}

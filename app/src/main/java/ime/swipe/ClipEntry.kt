package ime.swipe

import org.json.JSONObject

data class ClipEntry(
    val text: String? = null,
    val html: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val label: String = "",
    val digest: String? = null
) {
    val preview: String
        get() = when {
            !text.isNullOrEmpty() -> text
            mimeType?.startsWith("image/") == true -> "[图片] ${label.ifBlank { mimeType }}"
            fileName != null -> "[文件] ${label.ifBlank { mimeType ?: "未知类型" }}"
            else -> ""
        }

    fun sameContent(other: ClipEntry): Boolean {
        if (digest != null && other.digest != null) return digest == other.digest
        return text == other.text && html == other.html && fileName == other.fileName
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("html", html)
        put("fileName", fileName)
        put("mimeType", mimeType)
        put("label", label)
        put("digest", digest)
    }

    companion object {
        fun fromJson(o: JSONObject) = ClipEntry(
            text = o.optString("text").takeUnless { it == "null" || it.isEmpty() },
            html = o.optString("html").takeUnless { it == "null" || it.isEmpty() },
            fileName = o.optString("fileName").takeUnless { it == "null" || it.isEmpty() },
            mimeType = o.optString("mimeType").takeUnless { it == "null" || it.isEmpty() },
            label = o.optString("label"),
            digest = o.optString("digest").takeUnless { it == "null" || it.isEmpty() }
        )
    }
}

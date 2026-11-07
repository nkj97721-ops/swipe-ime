package ime.swipe

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class ClipboardProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): File {
        val root = File(requireNotNull(context).filesDir, "clipboard").canonicalFile
        val name = uri.lastPathSegment ?: throw FileNotFoundException("Missing file name")
        val file = File(root, name).canonicalFile
        if (file.parentFile != root || !file.isFile) throw FileNotFoundException("Clipboard item not found")
        return file
    }

    override fun getType(uri: Uri): String =
        uri.getQueryParameter("mime") ?: "application/octet-stream"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> uri.getQueryParameter("name") ?: file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")
}

package com.spinel.melkit.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import com.spinel.melkit.R

/** Opens the public folder, with the system document browser as a fallback. */
internal fun openSavedFolder(context: Context, savedPath: String) {
    val folder = savedPath.substringBeforeLast('/', "Download/MelKit")
    val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$folder")
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
    if (runCatching { context.startActivity(view) }.isSuccess) return
    val browse = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
    if (Build.VERSION.SDK_INT >= 26) browse.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
    if (runCatching { context.startActivity(browse) }.isFailure) {
        Toast.makeText(context, context.getString(R.string.saved_location, savedPath), Toast.LENGTH_LONG).show()
    }
}

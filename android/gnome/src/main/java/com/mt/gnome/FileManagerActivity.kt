package com.mt.gnome

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** SAF-based file manager. It only accesses folders the user explicitly grants. */
class FileManagerActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("mt_gnome_files", MODE_PRIVATE) }
    private var treeUri: Uri? = null
    private var currentUri: Uri? = null
    private lateinit var listing: LinearLayout
    private lateinit var pathLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        treeUri = prefs.getString("tree_uri", null)?.let(Uri::parse)
        currentUri = treeUri
        buildUi()
        if (treeUri == null) chooseFolder() else loadDirectory()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(18, 21, 30))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "MT Files"; textSize = 19f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(action("Folder") { chooseFolder() })
        header.addView(action("New") { createFolder() })
        root.addView(header)
        pathLabel = TextView(this).apply {
            textSize = 11f; setTextColor(Color.LTGRAY); setPadding(0, dp(5), 0, dp(8))
        }
        root.addView(pathLabel)
        val controls = LinearLayout(this)
        controls.addView(action("Up") { goUp() })
        controls.addView(action("Refresh") { loadDirectory() })
        controls.addView(action("Open granted folder") { currentUri = treeUri; loadDirectory() })
        root.addView(controls)
        val scroll = ScrollView(this)
        listing = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listing)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Storage access is limited to folders you choose. Android may block protected system and app-private directories."
            textSize = 10f; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, 0)
        })
        setContentView(root)
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try { startActivityForResult(intent, REQUEST_TREE) }
        catch (_: Exception) { toast("No Android document provider is available") }
    }

    @Deprecated("Android activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try { contentResolver.takePersistableUriPermission(uri, flags) } catch (_: Exception) {}
        treeUri = uri
        currentUri = uri
        prefs.edit().putString("tree_uri", uri.toString()).apply()
        loadDirectory()
    }

    private data class Entry(val uri: Uri, val name: String, val mime: String, val directory: Boolean)

    private fun loadDirectory() {
        listing.removeAllViews()
        val parent = currentUri
        if (parent == null) { pathLabel.text = "Choose a folder to begin"; return }
        pathLabel.text = DocumentsContract.getTreeDocumentId(treeUri ?: parent)
            .let { "Granted folder · " + it.substringAfterLast(':') + " / " +
                DocumentsContract.getDocumentId(parent).substringAfterLast(':') }
        val childrenUri = try { DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri ?: parent, DocumentsContract.getDocumentId(parent)
        ) } catch (_: Exception) { null }
        if (childrenUri == null) { toast("Cannot read this folder"); return }
        val entries = mutableListOf<Entry>()
        try {
            contentResolver.query(childrenUri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, DocumentsContract.Document.COLUMN_DISPLAY_NAME + " COLLATE NOCASE")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idCol)
                    val name = cursor.getString(nameCol) ?: "Unnamed"
                    val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                    entries += Entry(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri ?: parent, id),
                        name, mime, mime == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                }
            }
        } catch (e: Exception) { toast("Folder access failed: ${e.message ?: "permission denied"}") }
        if (entries.isEmpty()) {
            listing.addView(TextView(this).apply {
                text = "This folder is empty"; textSize = 14f; setTextColor(Color.LTGRAY)
                setPadding(dp(12), dp(20), dp(12), dp(20))
            })
        }
        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(7), dp(5), dp(7))
                setBackgroundColor(Color.rgb(31, 36, 49))
            }
            row.addView(TextView(this).apply {
                text = (if (entry.directory) "▰  " else "▤  ") + entry.name
                textSize = 13f; setTextColor(Color.WHITE); maxLines = 2
                setOnClickListener {
                    if (entry.directory) { currentUri = entry.uri; loadDirectory() }
                    else openFile(entry)
                }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            row.addView(action("⋮") { showEntryActions(entry) })
            listing.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
    }

    private fun showEntryActions(entry: Entry) {
        val options = mutableListOf("Open", "Rename", "Delete", "Share")
        AlertDialog.Builder(this).setTitle(entry.name).setItems(options.toTypedArray()) { _, which ->
            when (which) {
                0 -> if (entry.directory) { currentUri = entry.uri; loadDirectory() } else openFile(entry)
                1 -> rename(entry)
                2 -> confirmDelete(entry)
                3 -> share(entry)
            }
        }.show()
    }

    private fun openFile(entry: Entry) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(entry.uri, entry.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Open ${entry.name}"))
        } catch (_: Exception) { toast("No application can open this file") }
    }

    private fun createFolder() {
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this).setTitle("Create folder").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) try {
                    DocumentsContract.createDocument(contentResolver, currentUri!!,
                        DocumentsContract.Document.MIME_TYPE_DIR, name)
                    loadDirectory()
                } catch (_: Exception) { toast("Could not create folder") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun rename(entry: Entry) {
        val input = EditText(this).apply { setText(entry.name) }
        AlertDialog.Builder(this).setTitle("Rename").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) try {
                    DocumentsContract.renameDocument(contentResolver, entry.uri, name)
                    loadDirectory()
                } catch (_: Exception) { toast("Rename is not supported by this provider") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(entry: Entry) {
        AlertDialog.Builder(this).setTitle("Delete ${entry.name}?")
            .setMessage("This permanently deletes the selected item if the document provider supports deletion.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                try {
                    if (DocumentsContract.deleteDocument(contentResolver, entry.uri)) loadDirectory()
                    else toast("Provider did not delete the item")
                } catch (_: Exception) { toast("Delete is not supported or permission was denied") }
            }.show()
    }

    private fun share(entry: Entry) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = entry.mime
                putExtra(Intent.EXTRA_STREAM, entry.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${entry.name}"))
        } catch (_: Exception) { toast("Could not share this item") }
    }

    private fun goUp() {
        val root = treeUri ?: return
        val current = currentUri ?: root
        if (current == root) return
        val id = DocumentsContract.getDocumentId(current)
        val parentId = id.substringBeforeLast('/', "")
        currentUri = if (parentId.isBlank()) root else
            DocumentsContract.buildDocumentUriUsingTree(root, parentId)
        loadDirectory()
    }

    private fun action(title: String, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 10f; setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 58, 82))
        setOnClickListener { click() }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { private const val REQUEST_TREE = 7104 }
}

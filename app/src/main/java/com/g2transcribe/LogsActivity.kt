package com.g2transcribe

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Lists transcript log files written by [TranscribeService] when the "save log"
 * preference is enabled. Tap a file to view; long-press to delete a single file;
 * use the "全部削除" button to clear all at once.
 */
class LogsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var deleteAllButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.logs_title)
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 16)
        })

        deleteAllButton = Button(this).apply {
            text = getString(R.string.logs_delete_all)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF6E1F1F.toInt())
            setOnClickListener { confirmDeleteAll() }
        }
        root.addView(deleteAllButton)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
        }
        root.addView(listContainer)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF121212.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(root)
        }
        setContentView(scroll)

        rebuildList()
    }

    private fun logFiles(): List<File> {
        val dir = File(getExternalFilesDir(null), "logs")
        return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun rebuildList() {
        listContainer.removeAllViews()
        val files = logFiles()
        deleteAllButton.visibility = if (files.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        if (files.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = getString(R.string.logs_empty)
                setTextColor(0xFF888888.toInt())
                textSize = 14f
                setPadding(0, 24, 0, 0)
            })
            return
        }
        files.forEach { file -> listContainer.addView(buildFileRow(file)) }
    }

    private fun buildFileRow(file: File): TextView {
        val relativeTime = DateUtils.getRelativeTimeSpanString(
            file.lastModified(),
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )
        return TextView(this).apply {
            text = "${file.nameWithoutExtension}\n$relativeTime · ${file.length()}B"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setLineSpacing(8f, 1f)
            setPadding(20, 28, 20, 28)
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val ta = context.obtainStyledAttributes(attrs)
            setBackgroundResource(ta.getResourceId(0, 0))
            ta.recycle()
            isClickable = true
            isFocusable = true
            setOnClickListener { showFileContent(file) }
            setOnLongClickListener {
                confirmDeleteOne(file)
                true
            }
        }
    }

    private fun showFileContent(file: File) {
        val body = try { file.readText() } catch (e: Exception) { "(読み込み失敗: ${e.message})" }
        val tv = TextView(this).apply {
            text = if (body.isBlank()) "(空のログ)" else body
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 32, 32, 32)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(file.nameWithoutExtension)
            .setView(sv)
            .setPositiveButton(getString(R.string.dialog_close), null)
            .setNeutralButton(getString(R.string.logs_delete_one)) { _, _ -> confirmDeleteOne(file) }
            .show()
    }

    private fun confirmDeleteOne(file: File) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.logs_delete_one_title))
            .setMessage(file.nameWithoutExtension)
            .setPositiveButton(getString(R.string.logs_delete_one)) { _, _ ->
                file.delete()
                rebuildList()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun confirmDeleteAll() {
        val count = logFiles().size
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.logs_delete_all_title))
            .setMessage(getString(R.string.logs_delete_all_message, count))
            .setPositiveButton(getString(R.string.logs_delete_all)) { _, _ ->
                logFiles().forEach { it.delete() }
                rebuildList()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }
}

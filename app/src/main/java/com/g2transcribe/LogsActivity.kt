package com.g2transcribe

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Lists transcript log files written by [TranscribeService] when the "save log"
 * preference is enabled. Tapping a file shows its content in a dialog.
 */
class LogsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.logs_title)
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 32)
        })

        val dir = File(getExternalFilesDir(null), "logs")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            content.addView(TextView(this).apply {
                text = getString(R.string.logs_empty)
                setTextColor(0xFF888888.toInt())
                textSize = 14f
                setPadding(0, 24, 0, 0)
            })
        } else {
            files.forEach { file ->
                content.addView(buildFileRow(file))
            }
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF121212.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(content)
        }
        setContentView(scroll)
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
            .setPositiveButton("閉じる", null)
            .show()
    }
}

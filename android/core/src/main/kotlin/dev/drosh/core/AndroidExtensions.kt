package dev.drosh.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.getSystemService

val Context.isDebuggable: Boolean
    get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

fun Context.copyToClipboard(label: CharSequence, text: CharSequence) {
    getSystemService<ClipboardManager>()?.setPrimaryClip(
        ClipData.newPlainText(label, text)
    )
}

fun Context.toast(@StringRes res: Int) {
    Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
}

fun Context.toast(text: CharSequence) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

fun Context.shareText(text: CharSequence, subject: String? = null) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    startActivity(Intent.createChooser(intent, null))
}

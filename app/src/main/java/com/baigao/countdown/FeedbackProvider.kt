package com.baigao.countdown

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 意见反馈的附件共享器（纯 framework 实现，工程刻意零第三方依赖）。
 *
 * 想法反馈要能带上图片 / 视频 / 日志文件，而 Android 7.0 起禁止把 file:// 直接丢给别人
 * （会抛 FileUriExposedException），必须走 content://；没有 androidx 的 FileProvider，
 * 于是照它的方式自己用原生 ContentProvider 做一层：
 * 只对外开放「cache/feedback」目录下的文件（路径形如 `entry/xxx.jpg`），
 * 配合 FLAG_GRANT_READ_URI_PERMISSION 授权给邮箱客户端读取。
 *
 * 只认一层路径、文件名必须本身合法（不含 '/'、'..'），够用也很安全。
 */
class FeedbackProvider : ContentProvider() {

    companion object {
        /** 附件存放目录，与 FeedbackActivity 保持一致。 */
        fun attachDir(ctx: android.content.Context): File = File(ctx.cacheDir, "feedback")
    }

    override fun onCreate(): Boolean = true

    /** 把 content://…/entry/<文件名> 解析成本地文件；非法或不存在返回 null。 */
    private fun resolve(uri: Uri): File? {
        val segs = uri.pathSegments ?: return null
        if (segs.size != 2) return null
        val name = segs[1]
        if (name.isBlank() || name.contains("/") || name.contains("..")) return null
        val f = File(attachDir(context!!), name)
        return if (f.exists()) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = resolve(uri) ?: throw FileNotFoundException(uri.path)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openFile(uri: Uri, mode: String, opts: android.os.CancellationSignal?): ParcelFileDescriptor? =
        openFile(uri, mode)

    /** 邮件客户端会先 query 取文件名与大小，缺了附件名可能显示不出来。 */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val f = resolve(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = ArrayList<Any?>(cols.size)
        for (col in cols) {
            row.add(
                when (col) {
                    OpenableColumns.DISPLAY_NAME -> f.name
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            )
        }
        cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri): String {
        val f = resolve(uri) ?: return "*/*"
        val name = f.name.lowercase()
        // 认不出的后缀按常见的一一对应兜住，别让它掉进「什么都不是」→ 邮箱不认 → 让你自己再挑一次
        return when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".jfif") -> "image/jpeg"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".gif") -> "image/gif"
            name.endsWith(".bmp") -> "image/bmp"
            name.endsWith(".heic") -> "image/heic"
            name.endsWith(".heif") -> "image/heif"
            name.endsWith(".avif") -> "image/avif"
            name.endsWith(".mp4") || name.endsWith(".m4v") -> "video/mp4"
            name.endsWith(".mov") -> "video/quicktime"
            name.endsWith(".3gp") -> "video/3gpp"
            name.endsWith(".mkv") -> "video/x-matroska"
            name.endsWith(".webm") -> "video/webm"
            name.endsWith(".avi") -> "video/x-msvideo"
            name.endsWith(".jpe") -> "image/jpeg"
            name.endsWith(".m4v") || name.endsWith(".mp4v") -> "video/mp4"
            name.endsWith(".m4u8v") -> "video/mp2t"
            name.endsWith(".mpeg") || name.endsWith(".mpg") -> "video/mpeg"
            name.endsWith(".wmv") -> "video/x-ms-wmv"
            name.endsWith(".tiff") || name.endsWith(".tif") -> "image/tiff"
            name.endsWith(".ico") -> "image/x-icon"
            // 运行日志就是 txt，这一档最要紧：认不出来的话邮箱把它当成「什么都不是的通用件」，
            // 直接不挂进撰写页 —— 症状就是「还要你自己再挑一次图片或视频」。
            name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".json") ||
                name.endsWith(".csv") || name.endsWith(".xml") || name.endsWith(".md") ||
                name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".html") ||
                name.endsWith(".htm") -> "text/plain"
            name.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

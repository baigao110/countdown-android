package com.baigao.countdown

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * 应用内更新用的 APK 共享器（纯框架实现，不依赖任何第三方库）。
 *
 * Android 7.0 起禁止把 file:// 直接暴露给系统安装器，必须走 content://。
 * 工程刻意零第三方依赖（没有 androidx 的 FileProvider），所以这里用
 * framework 原生 ContentProvider 自己做一层：只对外提供「已下载的那个 APK」
 * （固定为 filesDir/update/app.apk），配合 FLAG_GRANT_READ_URI_PERMISSION
 * 授权给系统安装器读取，完成安装。
 */
class ApkProvider : ContentProvider() {

    companion object {
        /** 下载后 APK 的存放位置，与 AboutActivity 保持一致。 */
        fun apkFile(ctx: android.content.Context): File =
            File(File(ctx.filesDir, "update"), "app.apk")
    }

    override fun onCreate(): Boolean = true

    private fun file(): File = File(File(context!!.filesDir, "update"), "app.apk")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = file()
        if (!f.exists()) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** 部分系统安装器会先 query 取文件名与大小，缺了可能安装失败。 */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val f = file()
        if (!f.exists()) return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = ArrayList<Any?>(cols.size)
        for (col in cols) {
            row.add(
                when (col) {
                    OpenableColumns.DISPLAY_NAME -> "countdown-android-update.apk"
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            )
        }
        cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

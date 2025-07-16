package net.hearnsoft.cdtracksplitter.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

object TypeChecker {
    const val TAG = "TypeChecker"

    fun isValidAudioFile(context: Context, uri: Uri): Boolean {
        try {
            // 支持的音频MIME类型
            val supportedAudioTypes = setOf(
                "audio/mpeg",           // MP3
                "audio/mp3",            // MP3 (alternative)
                "audio/x-mpeg",         // MP3 (alternative)
                "audio/flac",           // FLAC
                "audio/x-flac",         // FLAC (alternative)
                "audio/wav",            // WAV
                "audio/x-wav",          // WAV (alternative)
                "audio/wave",           // WAV (alternative)
                "audio/vnd.wave",       // WAV (alternative)
                "audio/aac",            // AAC
                "audio/mp4",            // M4A/AAC
                "audio/x-m4a",          // M4A
                "audio/ogg",            // OGG
                "audio/vorbis",         // OGG Vorbis
                "audio/x-ms-wma",       // WMA
                "audio/wma",            // WMA
                "audio/aiff",           // AIFF
                "audio/x-aiff"          // AIFF (alternative)
            )

            // 通过ContentResolver获取MIME类型
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType != null && supportedAudioTypes.contains(mimeType.lowercase())) {
                return true
            }

            // 备用验证：通过文件扩展名验证
            val fileName = getFileName(context, uri)?.lowercase()
            if (fileName != null) {
                val supportedExtensions = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "wma", "aiff", "ape", "dsd", "dsf", "dff")
                val extension = fileName.substringAfterLast(".", "")
                return supportedExtensions.contains(extension)
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "验证音频文件时出错: ${e.message}")
            return false
        }
    }

    fun isValidCueFile(context: Context, uri: Uri): Boolean {
        try {
            // 首先检查MIME类型
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType != null) {
                val validCueMimeTypes = setOf(
                    "text/plain",
                    "text/x-cue",
                    "application/x-cue",
                    "audio/x-cue"
                )
                if (validCueMimeTypes.contains(mimeType.lowercase())) {
                    // 进一步验证文件扩展名
                    return hasValidCueExtension(context, uri)
                }
            }

            // 备用验证：仅通过文件扩展名
            return hasValidCueExtension(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "验证CUE文件时出错: ${e.message}")
            return false
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        }
    }

    private fun hasValidCueExtension(context: Context, uri: Uri): Boolean {
        val fileName = getFileName(context, uri)?.lowercase()
        return fileName != null && fileName.endsWith(".cue")
    }
}
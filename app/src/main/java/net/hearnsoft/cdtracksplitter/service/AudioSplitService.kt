package net.hearnsoft.cdtracksplitter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import com.arthenica.ffmpegkit.StatisticsCallback
import net.hearnsoft.cdtracksplitter.MainActivity
import net.hearnsoft.cdtracksplitter.R
import net.hearnsoft.cdtracksplitter.model.CueFile
import net.hearnsoft.cdtracksplitter.model.Track
import net.hearnsoft.cdtracksplitter.parser.CueParser
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class AudioProcessingService : Service() {

    companion object {
        private const val TAG = "AudioProcessingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_processing_channel"

        const val ACTION_START_PROCESSING = "START_PROCESSING"
        const val ACTION_STOP_PROCESSING = "STOP_PROCESSING"

        const val EXTRA_TRACK_URI = "track_uri"
        const val EXTRA_CUE_URI = "cue_uri"
        const val EXTRA_COVER_URI = "cover_uri"
        const val EXTRA_OUTPUT_URI = "output_uri"
    }

    inner class ProcessingBinder : Binder() {
        fun getService(): AudioProcessingService = this@AudioProcessingService
    }

    private val binder = ProcessingBinder()
    private var isProcessing = false
    private var currentSession: FFmpegSession? = null

    // 处理进度回调
    var onProgressUpdate: ((Int, String) -> Unit)? = null
    var onProcessingComplete: ((Boolean, String) -> Unit)? = null
    var onLogUpdate: ((String) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_INFO)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROCESSING -> {
                val trackUri = intent.getParcelableExtra<Uri>(EXTRA_TRACK_URI)
                val cueUri = intent.getParcelableExtra<Uri>(EXTRA_CUE_URI)
                val coverUri = intent.getParcelableExtra<Uri>(EXTRA_COVER_URI)
                val outputUri = intent.getParcelableExtra<Uri>(EXTRA_OUTPUT_URI)

                if (trackUri != null && cueUri != null && outputUri != null) {
                    startProcessing(trackUri, cueUri, coverUri, outputUri)
                }
            }
            ACTION_STOP_PROCESSING -> {
                stopProcessing()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "CD Track Splitting Progress"
                setSound(null, null)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String, progress: Int = -1): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_process_24px)
            .setContentIntent(pendingIntent)
            .setOngoing(isProcessing)
            .setSilent(true)

        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    private fun startProcessing(
        trackUri: Uri,
        cueUri: Uri,
        coverUri: Uri?,
        outputUri: Uri
    ) {
        if (isProcessing) {
            Log.w(TAG, "Processing already in progress")
            return
        }

        isProcessing = true
        val notification = createNotification("Starting audio processing...", "Preparing files", 0)
        startForeground(NOTIFICATION_ID, notification)

        Thread {
            try {
                processAudio(trackUri, cueUri, coverUri, outputUri)
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                onProcessingComplete?.invoke(false, "Processing failed: ${e.message}")
                stopProcessing()
            }
        }.start()
    }

    private fun processAudio(
        trackUri: Uri,
        cueUri: Uri,
        coverUri: Uri?,
        outputUri: Uri
    ) {
        try {
            // 解析CUE文件
            onLogUpdate?.invoke("Parsing CUE file...")
            updateNotification("Parsing CUE file...", 5)

            val cueParser = CueParser(this)
            val cueFile = cueParser.parse(cueUri)
                ?: throw Exception("Failed to parse CUE file")

            onLogUpdate?.invoke("CUE file parsed successfully. Found ${cueFile.tracks.size} tracks")

            // 复制输入文件到临时目录以便FFmpeg访问
            val tempDir = File(cacheDir, "audio_processing")
            if (!tempDir.exists()) tempDir.mkdirs()

            val inputFile = copyUriToTempFile(trackUri, tempDir, "input_audio")
            val coverFile = coverUri?.let { copyUriToTempFile(it, tempDir, "cover_image") }

            onLogUpdate?.invoke("Files prepared, starting track splitting...")

            // 处理每个轨道
            val totalTracks = cueFile.tracks.size
            cueFile.tracks.forEachIndexed { index, track ->
                if (!isProcessing) return // 检查是否被取消

                val progress = ((index + 1) * 90 / totalTracks) + 5 // 5-95%
                val trackFileName = sanitizeFileName("${String.format("%02d", track.number)}. ${track.title ?: "Track ${track.number}"}")

                onLogUpdate?.invoke("Processing track ${track.number}: ${track.title}")
                updateNotification("Processing track ${track.number}/${totalTracks}", progress)

                processTrack(
                    inputFile = inputFile,
                    coverFile = coverFile,
                    track = track,
                    nextTrack = if (index < cueFile.tracks.size - 1) cueFile.tracks[index + 1] else null,
                    cueFile = cueFile,
                    outputUri = outputUri,
                    fileName = trackFileName
                )

                onProgressUpdate?.invoke(progress, "Completed track ${track.number}")
            }

            // 清理临时文件
            tempDir.deleteRecursively()

            updateNotification("Processing completed", 100)
            onLogUpdate?.invoke("All tracks processed successfully!")
            onProcessingComplete?.invoke(true, "Successfully processed ${totalTracks} tracks")

        } catch (e: Exception) {
            Log.e(TAG, "Processing error", e)
            onLogUpdate?.invoke("Error: ${e.message}")
            onProcessingComplete?.invoke(false, e.message ?: "Unknown error occurred")
        } finally {
            stopProcessing()
        }
    }

    private fun processTrack(
        inputFile: File,
        coverFile: File?,
        track: Track,
        nextTrack: Track?,
        cueFile: CueFile,
        outputUri: Uri,
        fileName: String
    ) {
        val startTime = track.mainIndex?.time?.toSeconds() ?: 0.0
        val endTime = nextTrack?.mainIndex?.time?.toSeconds()

        // 创建输出文件
        val outputDocumentFile = DocumentFile.fromTreeUri(this, outputUri)
        val outputFile = outputDocumentFile?.createFile("audio/flac", "$fileName.flac")
            ?: throw Exception("Failed to create output file")

        // 创建临时输出文件（FFmpeg需要直接文件路径）
        val tempOutputFile = File(cacheDir, "$fileName.flac")

        // 构建FFmpeg命令
        val command = buildFFmpegCommand(
            inputFile = inputFile,
            coverFile = coverFile,
            outputFile = tempOutputFile,
            startTime = startTime,
            endTime = endTime,
            track = track,
            cueFile = cueFile
        )

        onLogUpdate?.invoke("FFmpeg command: ${command.joinToString(" ")}")

        // 执行FFmpeg命令
        var lastProgress = 0
        currentSession = FFmpegKit.executeWithArgumentsAsync(
            command.toTypedArray(),
            { session ->
                val returnCode = session.returnCode
                if (ReturnCode.isSuccess(returnCode)) {
                    onLogUpdate?.invoke("Track ${track.number} completed successfully")
                    // 复制文件到最终输出位置
                    copyTempFileToOutput(tempOutputFile, outputFile.uri)
                } else {
                    val error = "Track ${track.number} failed with return code: $returnCode"
                    onLogUpdate?.invoke(error)
                    Log.e(TAG, error)
                }
                // 清理临时输出文件
                tempOutputFile.delete()
            },
            LogCallback { log ->
                onLogUpdate?.invoke("FFmpeg: ${log.message}")
            },
            StatisticsCallback { statistics ->
                // 计算进度（FFmpeg时间 / 轨道总时长）
                val trackDuration = (endTime ?: Double.MAX_VALUE) - startTime
                if (trackDuration > 0 && statistics.time > 0) {
                    val progress = ((statistics.time / 1000.0) / trackDuration * 100).roundToInt()
                    if (progress > lastProgress) {
                        lastProgress = progress
                        onLogUpdate?.invoke("Track ${track.number} progress: $progress%")
                    }
                }
            }
        )

        // 等待命令完成
        currentSession?.let { session ->
            while (session.state == SessionState.RUNNING) {
                Thread.sleep(100)
                if (!isProcessing) {
                    FFmpegKit.cancel(session.sessionId)
                    break
                }
            }
        }
    }

    private fun buildFFmpegCommand(
        inputFile: File,
        coverFile: File?,
        outputFile: File,
        startTime: Double,
        endTime: Double?,
        track: Track,
        cueFile: CueFile
    ): List<String> {
        val command = mutableListOf<String>()

        // 输入文件
        command.addAll(listOf("-i", inputFile.absolutePath))

        // 封面图片（如果有）
        coverFile?.let {
            command.addAll(listOf("-i", it.absolutePath))
        }

        // 时间范围
        command.addAll(listOf("-ss", formatTime(startTime)))
        endTime?.let {
            val duration = it - startTime
            command.addAll(listOf("-t", formatTime(duration)))
        }

        // 音频编码设置
        command.addAll(listOf(
            "-c:a", "flac",
            "-compression_level", "8"
        ))

        // 封面图片映射（如果有）
        if (coverFile != null) {
            command.addAll(listOf(
                "-map", "0:a",
                "-map", "1:v",
                "-c:v", "copy",
                "-disposition:v", "attached_pic"
            ))
        }

        // Metadata
        track.title?.let { command.addAll(listOf("-metadata", "title=$it")) }
        track.performer?.let { command.addAll(listOf("-metadata", "artist=$it")) }
        cueFile.title?.let { command.addAll(listOf("-metadata", "album=$it")) }
        cueFile.performer?.let { command.addAll(listOf("-metadata", "albumartist=$it")) }
        cueFile.date?.let { command.addAll(listOf("-metadata", "date=$it")) }
        cueFile.genre?.let { command.addAll(listOf("-metadata", "genre=$it")) }
        command.addAll(listOf("-metadata", "track=${track.number}"))
        command.addAll(listOf("-metadata", "tracktotal=${cueFile.tracks.size}"))

        // 输出设置
        command.addAll(listOf(
            "-y", // 覆盖输出文件
            outputFile.absolutePath
        ))

        return command
    }

    private fun copyUriToTempFile(uri: Uri, tempDir: File, prefix: String): File {
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open input stream for URI: $uri")

        val extension = getFileExtension(uri)
        val tempFile = File(tempDir, "$prefix.$extension")

        inputStream.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }

        return tempFile
    }

    private fun copyTempFileToOutput(tempFile: File, outputUri: Uri) {
        try {
            val outputStream = contentResolver.openOutputStream(outputUri)
                ?: throw Exception("Cannot open output stream for URI: $outputUri")

            tempFile.inputStream().use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy temp file to output", e)
            throw e
        }
    }

    private fun getFileExtension(uri: Uri): String {
        val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        }
        return fileName?.substringAfterLast('.') ?: "tmp"
    }

    private fun formatTime(seconds: Double): String {
        val hours = (seconds / 3600).toInt()
        val minutes = ((seconds % 3600) / 60).toInt()
        val secs = (seconds % 60).toInt()
        val millis = ((seconds % 1) * 1000).toInt()

        return if (hours > 0) {
            String.format("%02d:%02d:%02d.%03d", hours, minutes, secs, millis)
        } else {
            String.format("%02d:%02d.%03d", minutes, secs, millis)
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun updateNotification(content: String, progress: Int) {
        val notification = createNotification("Processing CD Tracks", content, progress)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        onProgressUpdate?.invoke(progress, content)
    }

    private fun stopProcessing() {
        isProcessing = false
        currentSession?.let { FFmpegKit.cancel(it.sessionId) }
        currentSession = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProcessing()
    }
}
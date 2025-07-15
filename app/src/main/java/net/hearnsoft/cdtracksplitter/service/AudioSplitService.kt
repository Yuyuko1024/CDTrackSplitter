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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import net.hearnsoft.cdtracksplitter.metadata.MetadataUpdater
import net.hearnsoft.cdtracksplitter.model.CueFile
import net.hearnsoft.cdtracksplitter.model.Track
import net.hearnsoft.cdtracksplitter.parser.CueParser
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    // 线程管理
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AudioProcessing").apply {
            priority = Thread.NORM_PRIORITY - 1 // 稍低优先级避免阻塞UI
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    // 状态管理
    private val isProcessing = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    private var currentProcessingTask: Future<*>? = null
    private var currentSession: FFmpegSession? = null

    // 进度管理
    private val currentProgress = AtomicInteger(0)
    private val totalTracks = AtomicInteger(0)
    private val processedTracks = AtomicInteger(0)

    // 回调接口
    var onProgressUpdate: ((Int, String) -> Unit)? = null
    var onProcessingComplete: ((Boolean, String) -> Unit)? = null
    var onLogUpdate: ((String) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_INFO)
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROCESSING -> {
                val trackUri = intent.getParcelableExtra<Uri>(EXTRA_TRACK_URI)
                val cueUri = intent.getParcelableExtra<Uri>(EXTRA_CUE_URI)
                val coverUri = intent.getParcelableExtra<Uri>(EXTRA_COVER_URI)
                val outputUri = intent.getParcelableExtra<Uri>(EXTRA_OUTPUT_URI)

                if (trackUri != null && cueUri != null && outputUri != null) {
                    startProcessingAsync(trackUri, cueUri, coverUri, outputUri)
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
            .setOngoing(isProcessing.get())
            .setSilent(true)

        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    private fun startProcessingAsync(
        trackUri: Uri,
        cueUri: Uri,
        coverUri: Uri?,
        outputUri: Uri
    ) {
        if (isProcessing.get()) {
            postLog("Processing already in progress")
            return
        }

        // 重置状态
        isProcessing.set(true)
        isCancelled.set(false)
        currentProgress.set(0)
        processedTracks.set(0)

        // 启动前台服务
        val notification = createNotification("Starting audio processing...", "Preparing files", 0)
        startForeground(NOTIFICATION_ID, notification)

        // 在后台线程执行处理
        currentProcessingTask = processingExecutor.submit {
            try {
                processAudioInBackground(trackUri, cueUri, coverUri, outputUri)
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                postLog("Processing failed: ${e.message}")
                postProcessingComplete(false, "Processing failed: ${e.message}")
            } finally {
                // 确保清理状态
                mainHandler.post {
                    stopProcessing()
                }
            }
        }
    }

    private fun processAudioInBackground(
        trackUri: Uri,
        cueUri: Uri,
        coverUri: Uri?,
        outputUri: Uri
    ) {
        try {
            // 检查是否取消
            if (isCancelled.get()) return

            // 解析CUE文件
            postLog("Parsing CUE file...")
            postProgressUpdate(5, "Parsing CUE file...")

            val cueParser = CueParser(this)
            val cueFile = cueParser.parse(cueUri)
                ?: throw Exception("Failed to parse CUE file")

            totalTracks.set(cueFile.tracks.size)
            postLog("CUE file parsed successfully. Found ${cueFile.tracks.size} tracks")

            if (isCancelled.get()) return

            // 准备临时目录和文件
            postLog("Preparing files...")
            postProgressUpdate(10, "Preparing files...")

            val tempDir = File(cacheDir, "audio_processing_${System.currentTimeMillis()}")
            if (!tempDir.exists()) tempDir.mkdirs()

            try {
                val inputFile = copyUriToTempFile(trackUri, tempDir, "input_audio")
                val coverFile = coverUri?.let { copyUriToTempFile(it, tempDir, "cover_image") }

                if (isCancelled.get()) return

                postLog("Files prepared, starting track splitting...")

                // 处理每个轨道
                cueFile.tracks.forEachIndexed { index, track ->
                    if (isCancelled.get()) return@forEachIndexed

                    val baseProgress = 15 // 前面的准备工作占15%
                    val trackProgress = (index * 80) / cueFile.tracks.size // 每个轨道占80%的进度空间
                    val currentOverallProgress = baseProgress + trackProgress

                    val trackFileName = sanitizeFileName(
                        "${String.format("%02d", track.number)}. ${track.title ?: "Track ${track.number}"}"
                    )

                    postLog("Processing track ${track.number}: ${track.title}")
                    postProgressUpdate(currentOverallProgress, "Processing track ${track.number}/${cueFile.tracks.size}")

                    processTrackInBackground(
                        inputFile = inputFile,
                        coverFile = coverFile,
                        track = track,
                        nextTrack = if (index < cueFile.tracks.size - 1) cueFile.tracks[index + 1] else null,
                        cueFile = cueFile,
                        outputUri = outputUri,
                        fileName = trackFileName
                    )

                    if (!isCancelled.get()) {
                        processedTracks.incrementAndGet()
                        val completedProgress = baseProgress + ((index + 1) * 80) / cueFile.tracks.size
                        postProgressUpdate(completedProgress, "Completed track ${track.number}")
                    }
                }

                if (!isCancelled.get()) {
                    postProgressUpdate(100, "Processing completed")
                    postLog("All tracks processed successfully!")
                    postProcessingComplete(true, "Successfully processed ${cueFile.tracks.size} tracks")
                }

            } finally {
                // 清理临时目录
                try {
                    tempDir.deleteRecursively()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean temp directory", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Processing error", e)
            postLog("Error: ${e.message}")
            postProcessingComplete(false, e.message ?: "Unknown error occurred")
        }
    }

    private fun processTrackInBackground(
        inputFile: File,
        coverFile: File?,
        track: Track,
        nextTrack: Track?,
        cueFile: CueFile,
        outputUri: Uri,
        fileName: String
    ) {
        if (isCancelled.get()) return

        val startTime = track.mainIndex?.time?.toSeconds() ?: 0.0
        val endTime = nextTrack?.mainIndex?.time?.toSeconds()

        // 创建输出文件
        val outputDocumentFile = DocumentFile.fromTreeUri(this, outputUri)
        val outputFile = outputDocumentFile?.createFile("audio/flac", "$fileName.flac")
            ?: throw Exception("Failed to create output file")

        // 创建临时输出文件
        val tempOutputFile = File(cacheDir, "${fileName}_${System.currentTimeMillis()}.flac")

        try {
            // 构建FFmpeg命令
            val command = buildFFmpegCommand(
                inputFile = inputFile,
                outputFile = tempOutputFile,
                startTime = startTime,
                endTime = endTime
            )

            postLog("FFmpeg command: ${command.joinToString(" ")}")

            // 执行FFmpeg命令（异步）
            val sessionCompleted = AtomicBoolean(false)
            val sessionSuccess = AtomicBoolean(false)

            currentSession = FFmpegKit.executeWithArgumentsAsync(
                command.toTypedArray(),
                { session ->
                    val returnCode = session.returnCode
                    sessionSuccess.set(ReturnCode.isSuccess(returnCode))

                    if (ReturnCode.isSuccess(returnCode)) {
                        postLog("Track ${track.number} completed successfully")
                    } else {
                        val error = "Track ${track.number} failed with return code: $returnCode"
                        postLog(error)
                        Log.e(TAG, error)
                    }
                    sessionCompleted.set(true)
                },
                LogCallback { log ->
                    if (!isCancelled.get()) {
                        postLog("FFmpeg: ${log.message}")
                    }
                },
                StatisticsCallback { statistics ->
                    if (!isCancelled.get()) {
                        // 这里可以添加细粒度的进度更新，但要注意不要过于频繁
                        val trackDuration = (endTime ?: Double.MAX_VALUE) - startTime
                        if (trackDuration > 0 && statistics.time > 0) {
                            val progress = ((statistics.time / 1000.0) / trackDuration * 100).roundToInt()
                            if (progress % 10 == 0) { // 每10%更新一次，减少UI更新频率
                                postLog("Track ${track.number} progress: $progress%")
                            }
                        }
                    }
                }
            )

            // 等待命令完成（在后台线程中）
            while (!sessionCompleted.get() && !isCancelled.get()) {
                Thread.sleep(100)
            }

            // 如果被取消，停止FFmpeg会话
            if (isCancelled.get()) {
                currentSession?.let { FFmpegKit.cancel(it.sessionId) }
                return
            }

            // 如果成功，复制文件到最终输出位置
            if (sessionSuccess.get()) {
                copyTempFileToOutput(tempOutputFile, outputFile.uri)

                // 更新metadata和封面
                val metadataUpdater = MetadataUpdater(this)
                val metadataUpdateSuccess = metadataUpdater.updateFlacMetadata(
                    audioFileUri = outputFile.uri,
                    track = track,
                    cueFile = cueFile,
                    coverImageUri = coverFile?.let { Uri.fromFile(it) }
                )

                if (metadataUpdateSuccess) {
                    postLog("Metadata and cover updated for track ${track.number}")
                } else {
                    postLog("Warning: Failed to update metadata for track ${track.number}")
                }
            }

        } finally {
            // 清理临时输出文件
            try {
                if (tempOutputFile.exists()) {
                    tempOutputFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete temp output file", e)
            }
        }
    }

    private fun buildFFmpegCommand(
        inputFile: File,
        outputFile: File,
        startTime: Double,
        endTime: Double?,
    ): List<String> {
        val command = mutableListOf<String>()

        // 输入文件
        command.addAll(listOf("-i", inputFile.absolutePath))

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

        // 只复制音频流，不处理封面和metadata（这些由MetadataUpdater处理）
        command.addAll(listOf("-map", "0:a"))

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
        val tempFile = File(tempDir, "${prefix}_${System.currentTimeMillis()}.$extension")

        inputStream.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }

        return tempFile
    }

    private fun copyTempFileToOutput(tempFile: File, outputUri: Uri) {
        val outputStream = contentResolver.openOutputStream(outputUri)
            ?: throw Exception("Cannot open output stream for URI: $outputUri")

        tempFile.inputStream().use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
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

    // 线程安全的UI更新方法
    private fun postProgressUpdate(progress: Int, message: String) {
        currentProgress.set(progress)
        mainHandler.post {
            updateNotification(message, progress)
            onProgressUpdate?.invoke(progress, message)
        }
    }

    private fun postLog(message: String) {
        mainHandler.post {
            onLogUpdate?.invoke(message)
        }
    }

    private fun postProcessingComplete(success: Boolean, message: String) {
        mainHandler.post {
            onProcessingComplete?.invoke(success, message)
        }
    }

    private fun updateNotification(content: String, progress: Int) {
        val notification = createNotification("Processing CD Tracks", content, progress)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun stopProcessing() {
        isCancelled.set(true)
        isProcessing.set(false)

        // 取消当前FFmpeg会话
        currentSession?.let { FFmpegKit.cancel(it.sessionId) }
        currentSession = null

        // 取消当前处理任务
        currentProcessingTask?.cancel(true)
        currentProcessingTask = null

        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProcessing()
        processingExecutor.shutdown()
    }
}
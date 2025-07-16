package net.hearnsoft.cdtracksplitter

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import com.bumptech.glide.Glide
import net.hearnsoft.cdtracksplitter.databinding.ActivityMainBinding
import net.hearnsoft.cdtracksplitter.service.AudioProcessingService

class MainActivity : AppCompatActivity() {

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    // Track file selection
    private var selectedTrackFile: Uri? = null
    private val trackFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            handleTrackFileSelected(it)
        }
    }

    // CUE file selection
    private var selectedCueFile: Uri? = null
    private val cueFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            handleCueFileSelected(it)
        }
    }

    // Cover image selection
    private var selectedCoverImage: Uri? = null
    private val coverImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            handleCoverImageSelected(it)
        }
    }

    // Output directory selection
    private var selectedOutputDirectory: Uri? = null
    private val outputDirectoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            handleOutputDirectorySelected(it)
        }
    }

    // MediaPlayer
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var isPaused = false

    // Progress update handler
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (isPlaying) {
                progressHandler.postDelayed(this, 100)
            }
        }
    }

    // Audio Processing Service
    private var audioProcessingService: AudioProcessingService? = null
    private var isServiceBound = false
    private var isProcessing = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioProcessingService.ProcessingBinder
            audioProcessingService = binder.getService()
            isServiceBound = true

            // 设置回调 - 这些回调已经在主线程中执行
            audioProcessingService?.onProgressUpdate = { progress, message ->
                // 直接更新UI，因为已经在主线程
                binding.outputCardView.outputProgressBar.progress = progress
                appendLog("Progress: $progress% - $message")
            }

            audioProcessingService?.onProcessingComplete = { success, message ->
                // 直接更新UI，因为已经在主线程
                isProcessing = false
                updateProcessingUI()

                if (success) {
                    appendLog(getString(R.string.log_process_success))
                    binding.outputCardView.outputProgressBar.progress = 100
                    Toast.makeText(this@MainActivity, getString(R.string.process_success_toast), Toast.LENGTH_LONG).show()
                } else {
                    appendLog(getString(R.string.log_process_failed, message))
                    Toast.makeText(this@MainActivity, getString(R.string.process_failed_toast ,message), Toast.LENGTH_LONG).show()
                }
            }

            audioProcessingService?.onLogUpdate = { message ->
                // 直接更新UI，因为已经在主线程
                appendLog(message)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioProcessingService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        initMiniPlayer()
        setupLogTextView()
    }

    private fun initViews() {
        // 打开音轨选择
        binding.trackCardView.trackSelectButton.setOnClickListener {
            openTrackFilePicker()
        }

        // 播放器控制按钮
        binding.trackCardView.miniPlayer.playPauseButton.setOnClickListener {
            togglePlayPause()
        }

        // 打开CUE文件选择
        binding.cueFileCardView.cueSelectButton.setOnClickListener {
            openCueFilePicker()
        }

        // 打开封面图片选择
        binding.coverCardView.coverSelectButton.setOnClickListener {
            openCoverImagePicker()
        }

        // 设置选择输出目录
        binding.outputCardView.selectOutputButton.setOnClickListener {
            selectOutputDirectory()
        }

        // 处理按钮
        binding.outputCardView.processButton.setOnClickListener {
            if (isProcessing) {
                stopProcessing()
            } else {
                startProcessing()
            }
        }

        // 设置输出日志文本视图
        binding.outputCardView.outputLogTextView.movementMethod = ScrollingMovementMethod()

        binding.trackCardView.miniPlayer.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (fromUser && mediaPlayer != null) {
                    val newPosition = (progress * (mediaPlayer?.duration ?: 0)) / 100
                    mediaPlayer?.seekTo(newPosition)
                    updateTimeDisplay(newPosition)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupLogTextView() {
        binding.outputCardView.outputLogTextView.movementMethod = ScrollingMovementMethod()
        binding.outputCardView.outputLogTextView.text = getString(R.string.ready_to_process) + "\n"
    }

    private fun appendLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logMessage = "[$timestamp] $message\n"
        binding.outputCardView.outputLogTextView.append(logMessage)

        // 自动滚动到底部
        val scrollAmount = binding.outputCardView.outputLogTextView.layout?.getLineTop(binding.outputCardView.outputLogTextView.lineCount) ?: 0
        if (scrollAmount > binding.outputCardView.outputLogTextView.height) {
            binding.outputCardView.outputLogTextView.scrollTo(0, scrollAmount - binding.outputCardView.outputLogTextView.height)
        }
    }

    private fun initMiniPlayer() {
        // Initialize mini player state
        binding.trackCardView.miniPlayer.seekBar.isEnabled = false
        binding.trackCardView.miniPlayer.playPauseButton.isEnabled = false
        binding.trackCardView.miniPlayer.playbackPosition.text = "00:00"
        binding.trackCardView.miniPlayer.playPauseButton.setImageResource(R.drawable.ic_play_24px)
    }

    private fun openTrackFilePicker() {
        trackFilePickerLauncher.launch("audio/*")
    }

    private fun openCueFilePicker() {
        cueFilePickerLauncher.launch("*/*")
    }

    private fun openCoverImagePicker() {
        coverImagePickerLauncher.launch("image/*")
    }

    private fun selectOutputDirectory() {
        outputDirectoryPickerLauncher.launch(null)
    }

    private fun handleTrackFileSelected(uri: Uri) {
        selectedTrackFile = uri

        // Update UI
        val fileName = getFileName(uri)
        binding.trackCardView.trackTitle.text = fileName ?: getString(R.string.selected_track_file)

        // Initialize media player with selected file
        initializeMediaPlayer(uri)

        // 更新处理按钮状态
        updateProcessButtonState()

        appendLog(getString(R.string.selected_track_log, fileName))
    }

    private fun handleCueFileSelected(uri: Uri) {
        selectedCueFile = uri

        // Update UI
        val fileName = getFileName(uri)
        binding.cueFileCardView.cueFileTitle.text = fileName ?: getString(R.string.selected_cue_file)

        // 更新处理按钮状态
        updateProcessButtonState()

        appendLog(getString(R.string.selected_cue_log, fileName))
    }

    private fun handleCoverImageSelected(uri: Uri) {
        selectedCoverImage = uri

        // Update UI
        val fileName = getFileName(uri)
        binding.coverCardView.coverTitle.text = fileName ?: getString(R.string.selected_cover_image)

        // Show and load image
        binding.coverCardView.coverImage.visibility = View.VISIBLE

        Glide.with(this)
            .load(uri)
            .centerCrop()
            .into(binding.coverCardView.coverImage)

        appendLog(getString(R.string.selected_cover_log, fileName))
    }

    private fun handleOutputDirectorySelected(uri: Uri) {
        selectedOutputDirectory = uri

        // 获取持久化URI权限
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            // 更新UI显示
            val directoryName = getDirectoryName(uri)
            binding.outputCardView.selectOutputButton.text = directoryName ?: getString(R.string.selected_output_directory)

            // 启用处理按钮（如果其他必要文件也已选择）
            updateProcessButtonState()

            Toast.makeText(this, getString(R.string.output_dir_success), Toast.LENGTH_SHORT).show()
            appendLog(getString(R.string.selected_output_log, directoryName))

        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.dir_permission_error, e.message), Toast.LENGTH_LONG).show()
            selectedOutputDirectory = null
            appendLog("Error: Failed to get directory permissions")
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.dir_error, e.message), Toast.LENGTH_LONG).show()
            selectedOutputDirectory = null
            appendLog("Error: ${e.message}")
        }
    }

    private fun updateProcessButtonState() {
        // 检查是否所有必要文件都已选择
        val allFilesSelected = selectedTrackFile != null &&
                selectedCueFile != null &&
                selectedOutputDirectory != null

        binding.outputCardView.processButton.isEnabled = allFilesSelected

        when {
            isProcessing -> {
                binding.outputCardView.processButton.text = getString(R.string.stop_processing)
            }
            allFilesSelected -> {
                binding.outputCardView.processButton.text = getString(R.string.start_processing)
            }
            else -> {
                binding.outputCardView.processButton.text = getString(R.string.select_files_first)
            }
        }
    }

    private fun updateProcessingUI() {
        updateProcessButtonState()

        if (isProcessing) {
            binding.outputCardView.outputProgressBar.visibility = View.VISIBLE
            binding.outputCardView.outputProgressBar.progress = 0
        } else {
            // 保持进度条可见，显示最终结果
            // binding.outputCardView.outputProgressBar.visibility = View.GONE
        }
    }

    private fun startProcessing() {
        if (selectedTrackFile == null || selectedCueFile == null || selectedOutputDirectory == null) {
            Toast.makeText(this, getString(R.string.select_files_toast), Toast.LENGTH_SHORT).show()
            return
        }

        isProcessing = true
        updateProcessingUI()

        appendLog(getString(R.string.starting_process))

        // 绑定服务
        val intent = Intent(this, AudioProcessingService::class.java).apply {
            action = AudioProcessingService.ACTION_START_PROCESSING
            putExtra(AudioProcessingService.EXTRA_TRACK_URI, selectedTrackFile)
            putExtra(AudioProcessingService.EXTRA_CUE_URI, selectedCueFile)
            putExtra(AudioProcessingService.EXTRA_COVER_URI, selectedCoverImage)
            putExtra(AudioProcessingService.EXTRA_OUTPUT_URI, selectedOutputDirectory)
        }

        if (!isServiceBound) {
            bindService(Intent(this, AudioProcessingService::class.java), serviceConnection, BIND_AUTO_CREATE)
        }

        startService(intent)
    }

    private fun stopProcessing() {
        isProcessing = false
        updateProcessingUI()

        val intent = Intent(this, AudioProcessingService::class.java).apply {
            action = AudioProcessingService.ACTION_STOP_PROCESSING
        }
        startService(intent)

        appendLog(getString(R.string.stopping_process))
    }

    // ------------ 播放器功能 ---------------

    private fun initializeMediaPlayer(uri: Uri) {
        try {
            // Release previous player
            mediaPlayer?.release()

            // Create new media player
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                prepareAsync()

                setOnPreparedListener { player ->
                    // Enable controls
                    binding.trackCardView.miniPlayer.seekBar.isEnabled = true
                    binding.trackCardView.miniPlayer.playPauseButton.isEnabled = true

                    // Setup seekbar max value
                    binding.trackCardView.miniPlayer.seekBar.max = 100
                    binding.trackCardView.miniPlayer.seekBar.progress = 0

                    // Update time display
                    updateTimeDisplay(0)
                }

                setOnCompletionListener {
                    stopPlayback()
                }

                setOnErrorListener { _, what, extra ->
                    Toast.makeText(this@MainActivity, getString(R.string.media_error, what), Toast.LENGTH_SHORT).show()
                    true
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.audio_load_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (isPlaying) {
                pausePlayback()
            } else {
                startPlayback()
            }
        }
    }

    private fun startPlayback() {
        mediaPlayer?.let { player ->
            if (isPaused) {
                player.start()
            } else {
                player.start()
            }

            isPlaying = true
            isPaused = false
            binding.trackCardView.miniPlayer.playPauseButton.setImageResource(R.drawable.ic_pause_24px)

            // Start progress updates
            progressHandler.post(progressRunnable)
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.let { player ->
            player.pause()
            isPlaying = false
            isPaused = true
            binding.trackCardView.miniPlayer.playPauseButton.setImageResource(R.drawable.ic_play_24px)

            // Stop progress updates
            progressHandler.removeCallbacks(progressRunnable)
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.let { player ->
            player.pause()
            player.seekTo(0)
        }

        isPlaying = false
        isPaused = false
        binding.trackCardView.miniPlayer.playPauseButton.setImageResource(R.drawable.ic_play_24px)
        binding.trackCardView.miniPlayer.seekBar.progress = 0
        updateTimeDisplay(0)

        // Stop progress updates
        progressHandler.removeCallbacks(progressRunnable)
    }

    private fun updateProgress() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                val currentPosition = player.currentPosition
                val duration = player.duration

                if (duration > 0) {
                    val progress = (currentPosition * 100) / duration
                    binding.trackCardView.miniPlayer.seekBar.progress = progress
                    updateTimeDisplay(currentPosition)
                }
            }
        }
    }

    private fun updateTimeDisplay(position: Int) {
        val minutes = (position / 1000) / 60
        val seconds = (position / 1000) % 60
        binding.trackCardView.miniPlayer.playbackPosition.text = String.format("%02d:%02d", minutes, seconds)
    }

    private fun getFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        }
    }

    private fun getDirectoryName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else {
                    // 备用方案：从URI路径中提取目录名
                    uri.lastPathSegment?.substringAfterLast(":")
                }
            }
        } catch (e: Exception) {
            // 如果查询失败，尝试从URI中提取
            uri.lastPathSegment?.substringAfterLast(":") ?: "Selected Directory"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        progressHandler.removeCallbacks(progressRunnable)

        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
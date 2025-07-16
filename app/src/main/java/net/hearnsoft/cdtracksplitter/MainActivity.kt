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
import android.text.method.LinkMovementMethod
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import net.hearnsoft.cdtracksplitter.adapter.TrackAdapter
import net.hearnsoft.cdtracksplitter.databinding.ActivityMainBinding
import net.hearnsoft.cdtracksplitter.ktx.toHtml
import net.hearnsoft.cdtracksplitter.model.TimePosition
import net.hearnsoft.cdtracksplitter.model.TrackItem
import net.hearnsoft.cdtracksplitter.parser.CueParser
import net.hearnsoft.cdtracksplitter.service.AudioProcessingService
import net.hearnsoft.cdtracksplitter.utils.TypeChecker.isValidAudioFile
import net.hearnsoft.cdtracksplitter.utils.TypeChecker.isValidCueFile

class MainActivity : AppCompatActivity() {

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    // TrackList
    private var trackAdapter: TrackAdapter? = null
    private var cueTrackList: List<TrackItem> = emptyList()

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
                binding.outputCardView.progressText.text = message
                appendLog("Progress: $progress% - $message")
            }

            audioProcessingService?.onProcessingComplete = { success, message ->
                // 直接更新UI，因为已经在主线程
                isProcessing = false
                updateProcessingUI()

                binding.outputCardView.progressText.text = message

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

        initPermissions()
        initViews()
        initMiniPlayer()
        setupLogTextView()
    }

    private fun initPermissions() {
        // 检查并请求必要的音频权限
        val audioPermission = PermissionLists.getReadMediaAudioPermission()
        val otherPermissions = mutableListOf<IPermission?>().apply {
            addAll(
                listOf(
                    PermissionLists.getReadMediaImagesPermission(),
                    PermissionLists.getReadMediaVisualUserSelectedPermission(),
                    PermissionLists.getPostNotificationsPermission()
                ))
        }

        // 首先检查是否已经有音频权限
        if (XXPermissions.isGrantedPermission(this, audioPermission)) {
            // 音频权限已授予，请求其他非必要权限
            requestOptionalPermissions(otherPermissions)
        } else {
            // 请求必要的音频权限
            XXPermissions.with(this)
                .permission(audioPermission)
                .request(object : OnPermissionCallback {
                    override fun onGranted(permissions: List<IPermission?>, allGranted: Boolean) {
                        if (allGranted) {
                            // 音频权限已获取，继续请求其他非必要权限
                            requestOptionalPermissions(otherPermissions)
                        } else {
                            // 音频权限部分获取（通常不会发生，因为我们只请求了一个权限集）
                            showAudioPermissionRequiredDialog()
                        }
                    }

                    override fun onDenied(permissions: List<IPermission?>, doNotAskAgain: Boolean) {
                        super.onDenied(permissions, doNotAskAgain)
                        if (doNotAskAgain) {
                            // 用户永久拒绝了权限，提示用户需要手动授予
                            showAudioPermissionSettingsDialog()
                        } else {
                            // 用户拒绝了权限但没有选择"不再询问"，告知用户该权限的重要性
                            showAudioPermissionRequiredDialog()
                        }
                    }
                })
        }
    }

    /**
     * 请求非必要的可选权限
     */
    private fun requestOptionalPermissions(permissions: List<IPermission?>) {
        XXPermissions.with(this)
            .permissions(permissions)
            .request(object : OnPermissionCallback {
                override fun onGranted(permissions: List<IPermission?>, allGranted: Boolean) {
                    if (!allGranted) {
                        // 部分可选权限未授予，但不影响核心功能
                        Toast.makeText(this@MainActivity, R.string.optional_permissions_denied, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onDenied(permissions: List<IPermission?>, doNotAskAgain: Boolean) {
                    super.onDenied(permissions, doNotAskAgain)
                    // 可选权限被拒绝，仅显示提示但不影响应用继续使用
                    Toast.makeText(this@MainActivity, R.string.optional_permissions_denied, Toast.LENGTH_SHORT).show()
                }
            })
    }

    /**
     * 显示音频权限必要提示对话框
     */
    private fun showAudioPermissionRequiredDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.permission_required_title)
            .setMessage(R.string.audio_permission_required_message)
            .setCancelable(false)
            .setPositiveButton(R.string.retry) { _, _ ->
                initPermissions() // 重新请求权限
            }
            .setNegativeButton(R.string.exit_app) { _, _ ->
                finish() // 退出应用
            }
        builder.create().show()
    }

    /**
     * 显示前往设置授予权限的对话框
     */
    private fun showAudioPermissionSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.permission_required_title)
            .setMessage(R.string.audio_permission_settings_message)
            .setCancelable(false)
            .setPositiveButton(R.string.settings) { _, _ ->
                XXPermissions.startPermissionActivity(this, PermissionLists.getReadMediaAudioPermission())
            }
            .setNegativeButton(R.string.exit_app) { _, _ ->
                finish() // 退出应用
            }
        builder.create().show()
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

        // 关于对话框
        binding.aboutCardView.sourceCode.movementMethod = LinkMovementMethod.getInstance()
        binding.aboutCardView.sourceCode.text = getString(
            R.string.about_view_source_code,
            "<b><a href=\"https://github.com/Yuyuko1024/CDTrackSplitter\">GitHub</a></b>"
        ).toHtml()
        val info = packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
        binding.aboutCardView.icon.setImageDrawable(
            info.loadIcon(packageManager)
        )
        binding.aboutCardView.versionName.text = packageManager.getPackageInfo(packageName, 0).versionName
    }

    private fun setupLogTextView() {
        binding.outputCardView.outputLogTextView.apply {
            movementMethod = ScrollingMovementMethod()
            text = getString(R.string.ready_to_process)
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        }
    }

    private fun appendLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logMessage = "[$timestamp] $message\n"
        binding.outputCardView.outputLogTextView.apply {
            append(logMessage)

            // 自动滚动到底部
            post {
                val scrollAmount = layout?.getLineTop(lineCount) ?: 0
                val maxScroll = scrollAmount - height
                if (maxScroll > 0) {
                    scrollTo(0, maxScroll)
                }
            }
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
        // 验证音频文件格式
        if (!isValidAudioFile(this@MainActivity, uri)) {
            Toast.makeText(this, getString(R.string.invalid_audio_format), Toast.LENGTH_LONG).show()
            appendLog("Error：${getString(R.string.invalid_audio_format)}")
            return
        }

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
        // 验证CUE文件格式
        if (!isValidCueFile(this@MainActivity, uri)) {
            Toast.makeText(this, getString(R.string.invalid_cue_format), Toast.LENGTH_LONG).show()
            appendLog("Error：${getString(R.string.invalid_cue_format)}")
            return
        }

        selectedCueFile = uri

        // Update UI
        val fileName = getFileName(uri)
        binding.cueFileCardView.cueFileTitle.text = fileName ?: getString(R.string.selected_cue_file)

        // 解析CUE文件并显示轨道列表
        parseCueFileAndShowTracks(uri)

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
            binding.outputCardView.progressText.visibility = View.VISIBLE
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

    private fun parseCueFileAndShowTracks(cueUri: Uri) {
        try {
            val cueParser = CueParser(this)
            val cueFile = cueParser.parse(cueUri)

            if (cueFile != null && cueFile.tracks.isNotEmpty()) {
                // 转换Track对象为TrackItem
                cueTrackList = cueFile.tracks.map { track ->
                    val startTime = track.mainIndex?.time
                    val startTimeMs = startTime?.toMilliseconds() ?: 0L
                    val indexTime = startTime?.let { formatTimeFromTimePosition(it) } ?: "00:00"

                    TrackItem(
                        number = track.number,
                        title = track.title ?: "Track ${track.number}",
                        artist = track.performer,
                        startTimeMs = startTimeMs,
                        indexTime = indexTime
                    )
                }

                setupTrackRecyclerView()
                binding.cueFileCardView.trackListRecyclerView.visibility = View.VISIBLE

                appendLog("CUE解析成功，找到 ${cueTrackList.size} 个轨道")
            } else {
                binding.cueFileCardView.trackListRecyclerView.visibility = View.GONE
                appendLog("CUE文件解析失败或没有找到轨道")
            }
        } catch (e: Exception) {
            binding.cueFileCardView.trackListRecyclerView.visibility = View.GONE
            appendLog("CUE文件解析错误: ${e.message}")
        }
    }

    private fun setupTrackRecyclerView() {
        trackAdapter = TrackAdapter(cueTrackList) { trackItem ->
            onTrackItemClick(trackItem)
        }

        binding.cueFileCardView.trackListRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = trackAdapter
            itemAnimator = DefaultItemAnimator()
        }
    }

    private fun onTrackItemClick(trackItem: TrackItem) {
        mediaPlayer?.let { player ->
            // Seek到指定位置
            player.seekTo(trackItem.startTimeMs.toInt())
            updateTimeDisplay(trackItem.startTimeMs.toInt())

            // 如果当前是暂停状态，只seek不播放
            // 如果正在播放，继续播放
            if (isPlaying) {
                // 播放状态下，seek后继续播放
            } else {
                // 暂停状态下，只更新进度条
                val duration = player.duration
                if (duration > 0) {
                    val progress = (trackItem.startTimeMs * 100) / duration
                    binding.trackCardView.miniPlayer.seekBar.progress = progress.toInt()
                }
            }

            appendLog("跳转到轨道 ${trackItem.number}: ${trackItem.title} (${trackItem.indexTime})")
        } ?: run {
            Toast.makeText(this, "请先选择音频文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTimeFromTimePosition(timePosition: TimePosition): String {
        val totalSeconds = timePosition.minutes * 60 + timePosition.seconds
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // ------------- 文件方法 --------------

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
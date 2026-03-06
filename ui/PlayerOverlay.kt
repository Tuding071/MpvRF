package app.marlboroadvance.mpvrf

import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `is`.xyz.mpv.MPVLib

// ============================================
// PLAYER VIEWMODEL (UPDATED WITH DEBUG)
// ============================================
class PlayerViewModel : ViewModel() {
    private val TAG = "PlayerViewModel"
    
    private val _currentVolume = MutableStateFlow(50)
    val currentVolume: StateFlow<Int> = _currentVolume
    val maxVolume = 100

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    private val _isPlaying = MutableStateFlow(true)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentTime = MutableStateFlow("00:00")
    val currentTime: StateFlow<String> = _currentTime

    private val _totalTime = MutableStateFlow("00:00")
    val totalTime: StateFlow<String> = _totalTime

    private val _position = MutableStateFlow(0f)
    val position: StateFlow<Float> = _position

    private val _duration = MutableStateFlow(1f)
    val duration: StateFlow<Float> = _duration

    private val _fileName = MutableStateFlow("Video")
    val fileName: StateFlow<String> = _fileName

    // Debug state
    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs: StateFlow<List<String>> = _debugLogs
    
    private val _mpvStatus = MutableStateFlow("Initializing...")
    val mpvStatus: StateFlow<String> = _mpvStatus

    init {
        Log.d(TAG, "Initializing PlayerViewModel")
        
        addDebugLog("ViewModel initialized")
        _mpvStatus.value = "Loading MPV..."
        
        try {
            // Initialize MPV with safe options
            MPVLib.setPropertyString("hwdec", "auto")
            MPVLib.setPropertyString("vo", "gpu-next")
            MPVLib.setPropertyString("profile", "fast")
            MPVLib.setPropertyString("cache", "yes")
            MPVLib.setPropertyString("cache-secs", "10")
            
            addDebugLog("MPV properties set")
            _mpvStatus.value = "MPV ready"
            
            // Add listener for MPV events
            MPVLib.addListener(object : MPVLib.Listener {
                override fun onEvent(event: String) {
                    Log.d(TAG, "MPV Event: $event")
                    addDebugLog("Event: $event")
                    
                    when (event) {
                        "file-loaded" -> {
                            _mpvStatus.value = "Video loaded"
                            addDebugLog("Video loaded successfully")
                            updateFileName()
                        }
                        "playback-restart" -> {
                            _mpvStatus.value = "Playing"
                        }
                        "pause" -> {
                            _mpvStatus.value = "Paused"
                        }
                        "unpause" -> {
                            _mpvStatus.value = "Playing"
                        }
                        "shutdown" -> {
                            _mpvStatus.value = "Shutdown"
                        }
                        "file-error" -> {
                            _mpvStatus.value = "Error loading file"
                            addDebugLog("ERROR: File error")
                        }
                    }
                }
                
                override fun onPropertyChange(property: String, value: String) {
                    Log.d(TAG, "MPV Property: $property = $value")
                    addDebugLog("Prop: $property = $value")
                }
            })
            
            addDebugLog("MPV listener added")
            
            // Start position updates
            viewModelScope.launch {
                var errorCount = 0
                while (isActive) {
                    try {
                        val pos = MPVLib.getPropertyDouble("time-pos")
                        val dur = MPVLib.getPropertyDouble("duration")
                        
                        if (pos != null && dur != null && dur > 0) {
                            _position.value = pos.toFloat()
                            _duration.value = dur.toFloat()
                            _currentTime.value = formatTimeSimple(pos)
                            _totalTime.value = formatTimeSimple(dur)
                            
                            val paused = MPVLib.getPropertyBoolean("pause")
                            _isPlaying.value = paused == false
                            
                            errorCount = 0 // Reset error count on success
                        } else {
                            errorCount++
                            if (errorCount > 50) { // About 5 seconds of failures
                                addDebugLog("WARNING: No position/duration data")
                                errorCount = 0
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating position", e)
                        addDebugLog("Error: ${e.message}")
                    }
                    delay(100)
                }
            }
            
            addDebugLog("Position updates started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing PlayerViewModel", e)
            _mpvStatus.value = "Error: ${e.message}"
            addDebugLog("INIT ERROR: ${e.message}")
        }
    }

    fun addDebugLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        viewModelScope.launch {
            val currentLogs = _debugLogs.value.toMutableList()
            currentLogs.add("[$timestamp] $message")
            if (currentLogs.size > 10) {
                currentLogs.removeAt(0)
            }
            _debugLogs.value = currentLogs
        }
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up PlayerViewModel")
        addDebugLog("Cleaning up")
        try {
            MPVLib.destroy()
            addDebugLog("MPV destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
            addDebugLog("Cleanup error: ${e.message}")
        }
    }

    private fun updateFileName() {
        try {
            val mediaTitle = MPVLib.getPropertyString("media-title")
            val path = MPVLib.getPropertyString("path")
            _fileName.value = when {
                !mediaTitle.isNullOrBlank() -> {
                    addDebugLog("Title: $mediaTitle")
                    mediaTitle.substringBeforeLast(".")
                }
                !path.isNullOrBlank() -> {
                    addDebugLog("Path: $path")
                    path.substringAfterLast("/").substringBeforeLast(".")
                }
                else -> {
                    addDebugLog("No title/path found")
                    "Video"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating file name", e)
            addDebugLog("Filename error: ${e.message}")
        }
    }

    fun setVolume(volume: Int) {
        val newVolume = volume.coerceIn(0, maxVolume)
        _currentVolume.value = newVolume
        try {
            MPVLib.setPropertyInt("volume", newVolume)
            addDebugLog("Volume: $newVolume")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
            addDebugLog("Volume error: ${e.message}")
        }
    }

    fun adjustVolume(delta: Int) {
        setVolume(_currentVolume.value + delta)
    }

    fun setSpeed(speed: Float) {
        _playbackSpeed.value = speed
        try {
            MPVLib.setPropertyDouble("speed", speed.toDouble())
            addDebugLog("Speed: ${speed}x")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting speed", e)
            addDebugLog("Speed error: ${e.message}")
        }
    }

    fun togglePause() {
        try {
            val isPaused = MPVLib.getPropertyBoolean("pause") ?: false
            MPVLib.setPropertyBoolean("pause", !isPaused)
            _isPlaying.value = !isPaused
            addDebugLog(if (!isPaused) "Paused" else "Playing")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling pause", e)
            addDebugLog("Pause error: ${e.message}")
        }
    }

    fun seekTo(position: Double) {
        try {
            MPVLib.command("seek", position.toString(), "absolute", "exact")
            addDebugLog("Seek to: ${formatTimeSimple(position)}")
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking", e)
            addDebugLog("Seek error: ${e.message}")
        }
    }

    fun seekRelative(seconds: Int) {
        try {
            MPVLib.command("seek", seconds.toString(), "relative", "exact")
            addDebugLog("Seek: $seconds seconds")
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking relative", e)
            addDebugLog("Seek error: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}

// ============================================
// FORMAT TIME FUNCTION
// ============================================
private fun formatTimeSimple(seconds: Double): String {
    if (seconds <= 0) return "00:00"
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, secs) 
           else String.format("%02d:%02d", minutes, secs)
}

// ============================================
// PROGRESS BAR
// ============================================
@Composable
fun SimpleDraggableProgressBar(
    position: Float,
    duration: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    getFreshPosition: () -> Float,
    modifier: Modifier = Modifier
) {
    var sliderPosition by remember { mutableStateOf(position) }
    var isDragging by remember { mutableStateOf(false) }
    
    LaunchedEffect(position, isDragging) {
        if (!isDragging) {
            sliderPosition = position
        }
    }

    Box(modifier = modifier.height(48.dp)) {
        Slider(
            value = sliderPosition,
            onValueChange = { newValue ->
                isDragging = true
                sliderPosition = newValue
                onValueChange(newValue)
            },
            onValueChangeFinished = {
                isDragging = false
                onValueChangeFinished()
            },
            valueRange = 0f..duration,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.Gray.copy(alpha = 0.6f)
            )
        )
    }
}

// ============================================
// DEBUG OVERLAY COMPOSABLE
// ============================================
@Composable
fun DebugOverlay(
    logs: List<String>,
    status: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(8.dp)
    ) {
        Text(
            text = "📊 MPV Status: $status",
            color = Color.Cyan,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        logs.reversed().forEach { log ->
            Text(
                text = log,
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
    }
}

// ============================================
// PLAYER OVERLAY (UPDATED WITH DEBUG)
// ============================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Use ViewModel state
    val currentTime by viewModel.currentTime.collectAsState()
    val totalTime by viewModel.totalTime.collectAsState()
    val currentPosition by viewModel.position.collectAsState()
    val videoDuration by viewModel.duration.collectAsState()
    val fileName by viewModel.fileName.collectAsState()
    val debugLogs by viewModel.debugLogs.collectAsState()
    val mpvStatus by viewModel.mpvStatus.collectAsState()
    
    // Local UI state
    var seekTargetTime by remember { mutableStateOf("00:00") }
    var showSeekTime by remember { mutableStateOf(false) }
    var isSpeedingUp by remember { mutableStateOf(false) }
    var isPausing by remember { mutableStateOf(false) }
    var showSeekbar by remember { mutableStateOf(true) }
    var showDebug by remember { mutableStateOf(true) } // Start with debug visible
    
    var seekbarPosition by remember { mutableStateOf(0f) }
    var seekbarDuration by remember { mutableStateOf(1f) }
    
    var isDragging by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableStateOf("") }
    var isSeekInProgress by remember { mutableStateOf(false) }
    val seekThrottleMs = 50L
    
    // Gesture states
    var touchStartTime by remember { mutableStateOf(0L) }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) }
    var longTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // UI feedback states
    var showVideoInfo by remember { mutableStateOf(true) }
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    var showVolumeFeedbackState by remember { mutableStateOf(false) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // Thresholds
    val longTapThreshold = 300L
    val horizontalSwipeThreshold = 30f
    val verticalSwipeThreshold = 40f
    val maxVerticalMovement = 50f
    val maxHorizontalMovement = 50f
    val quickSeekAmount = 5

    // ========== UI HELPER FUNCTIONS ==========
    fun scheduleSeekbarHide() {
        coroutineScope.launch {
            delay(4000)
            showSeekbar = false
            showVideoInfo = false
        }
    }
    
    fun showSeekbarWithTimeout() {
        showSeekbar = true
        showVideoInfo = true
        scheduleSeekbarHide()
    }
    
    fun showPlaybackFeedback(text: String) {
        playbackFeedbackText = text
        showPlaybackFeedback = true
        coroutineScope.launch {
            delay(1000)
            showPlaybackFeedback = false
        }
    }
    
    // Update seekbar position when not dragging
    LaunchedEffect(currentPosition, isDragging) {
        if (!isDragging) {
            seekbarPosition = currentPosition
            seekbarDuration = videoDuration
        }
    }

    // ========== GESTURE HANDLING FUNCTIONS ==========
    
    fun performRealTimeSeek(targetPosition: Double) {
        if (isSeekInProgress) return
        isSeekInProgress = true
        viewModel.seekTo(targetPosition)
        coroutineScope.launch {
            delay(seekThrottleMs)
            isSeekInProgress = false
        }
    }
    
    fun performQuickSeek(seconds: Int) {
        quickSeekFeedbackText = if (seconds > 0) "+$seconds" else "$seconds"
        showQuickSeekFeedback = true
        coroutineScope.launch {
            viewModel.seekRelative(seconds)
            delay(1000)
            showQuickSeekFeedback = false
        }
    }
    
    fun handleTap() {
        viewModel.togglePause()
        showPlaybackFeedback(if (viewModel.isPlaying.value) "Resume" else "Pause")
        showSeekbarWithTimeout()
    }
    
    fun startLongTapDetection() {
        isTouching = true
        touchStartTime = System.currentTimeMillis()
        longTapJob?.cancel()
        longTapJob = coroutineScope.launch {
            delay(longTapThreshold)
            if (isTouching && !isHorizontalSwipe && !isVerticalSwipe) {
                isLongTap = true
                isSpeedingUp = true
                viewModel.setSpeed(2.0f)
            }
        }
    }
    
    fun checkForSwipeDirection(currentX: Float, currentY: Float): String {
        if (isHorizontalSwipe || isVerticalSwipe || isLongTap) return ""
        
        val deltaX = abs(currentX - touchStartX)
        val deltaY = abs(currentY - touchStartY)
        
        if (deltaX > horizontalSwipeThreshold && deltaX > deltaY && deltaY < maxVerticalMovement) {
            return "horizontal"
        }
        if (deltaY > verticalSwipeThreshold && deltaY > deltaX && deltaX < maxHorizontalMovement) {
            return "vertical"
        }
        return ""
    }
    
    fun startHorizontalSeeking(startX: Float) {
        isHorizontalSwipe = true
        seekStartX = startX
        seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
        isSeeking = true
        showSeekTime = true
        showSeekbar = true
        showVideoInfo = true
        
        if (wasPlayingBeforeSeek) {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }
    
    fun startVerticalSwipe(startY: Float) {
        isVerticalSwipe = true
        val deltaY = startY - touchStartY
        if (deltaY < 0) {
            seekDirection = "+"
            performQuickSeek(quickSeekAmount)
        } else {
            seekDirection = "-"
            performQuickSeek(-quickSeekAmount)
        }
    }
    
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isSeeking) return
        
        val deltaX = currentX - seekStartX
        val pixelsPerSecond = 2f / 0.032f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        seekDirection = if (deltaX > 0) "+" else "-"
        seekTargetTime = formatTimeSimple(clampedPosition)
        
        performRealTimeSeek(clampedPosition)
    }
    
    fun endHorizontalSeeking() {
        if (isSeeking) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: seekStartPosition
            performRealTimeSeek(currentPos)
            
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(100)
                    MPVLib.setPropertyBoolean("pause", false)
                }
            }
            
            isSeeking = false
            showSeekTime = false
            seekDirection = ""
            scheduleSeekbarHide()
        }
    }
    
    fun endTouch() {
        val touchDuration = System.currentTimeMillis() - touchStartTime
        isTouching = false
        longTapJob?.cancel()
        
        if (isLongTap) {
            isLongTap = false
            isSpeedingUp = false
            viewModel.setSpeed(1.0f)
        } else if (isHorizontalSwipe) {
            endHorizontalSeeking()
        } else if (isVerticalSwipe) {
            isVerticalSwipe = false
            scheduleSeekbarHide()
        } else if (touchDuration < 150) {
            handleTap()
        }
        
        isHorizontalSwipe = false
        isVerticalSwipe = false
        isLongTap = false
    }
    
    // ========== PROGRESS BAR HANDLERS ==========
    
    fun handleProgressBarDrag(newPosition: Float) {
        if (!isSeeking) {
            isSeeking = true
            wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
            showSeekTime = true
            showSeekbar = true
            showVideoInfo = true
            
            if (wasPlayingBeforeSeek) {
                MPVLib.setPropertyBoolean("pause", true)
            }
        }
        isDragging = true
        val oldPosition = seekbarPosition
        seekbarPosition = newPosition
        seekDirection = if (newPosition > oldPosition) "+" else "-"
        
        val targetPosition = newPosition.toDouble()
        seekTargetTime = formatTimeSimple(targetPosition)
        performRealTimeSeek(targetPosition)
    }
    
    fun handleDragFinished() {
        isDragging = false
        if (wasPlayingBeforeSeek) {
            coroutineScope.launch {
                delay(100)
                MPVLib.setPropertyBoolean("pause", false)
            }
        }
        isSeeking = false
        showSeekTime = false
        wasPlayingBeforeSeek = false
        seekDirection = ""
        scheduleSeekbarHide()
    }
    
    fun getFreshPosition(): Float {
        return (MPVLib.getPropertyDouble("time-pos") ?: 0.0).toFloat()
    }
    
    // ========== UI RENDERING ==========
    Box(modifier = modifier.fillMaxSize()) {
        // MAIN GESTURE AREA
        Box(modifier = Modifier.fillMaxSize()) {
            // TOP 5% - Ignore
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.05f)
                    .align(Alignment.TopStart)
            )
            
            // CENTER AREA
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.95f)
                    .align(Alignment.BottomStart)
            ) {
                // LEFT 5% - Ignore
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                )
                
                // CENTER 90% - All gestures
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                        .pointerInteropFilter { event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    touchStartX = event.x
                                    touchStartY = event.y
                                    startLongTapDetection()
                                    true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    if (!isHorizontalSwipe && !isVerticalSwipe && !isLongTap) {
                                        when (checkForSwipeDirection(event.x, event.y)) {
                                            "horizontal" -> startHorizontalSeeking(event.x)
                                            "vertical" -> startVerticalSwipe(event.y)
                                        }
                                    } else if (isHorizontalSwipe) {
                                        handleHorizontalSeeking(event.x)
                                    }
                                    true
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    endTouch()
                                    true
                                }
                                else -> false
                            }
                        }
                )
                
                // RIGHT 5% - Ignore
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                )
            }
        }
        
        // BOTTOM SEEK BAR
        if (showSeekbar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 60.dp)
                    .offset(y = 3.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    ) {
                        Row(
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Text(
                                text = if (isSeeking || isDragging) "$seekTargetTime / $totalTime" 
                                       else "$currentTime / $totalTime",
                                style = TextStyle(
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier
                                    .background(Color.DarkGray.copy(alpha = 0.8f))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        SimpleDraggableProgressBar(
                            position = seekbarPosition,
                            duration = seekbarDuration,
                            onValueChange = { handleProgressBarDrag(it) },
                            onValueChangeFinished = { handleDragFinished() },
                            getFreshPosition = { getFreshPosition() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        
        // VIDEO INFO - Top Left
        if (showVideoInfo) {
            Text(
                text = fileName,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 60.dp, y = 20.dp)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        
        // DEBUG OVERLAY - Top Right (only when showDebug is true)
        if (showDebug) {
            DebugOverlay(
                logs = debugLogs,
                status = mpvStatus,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .width(200.dp)
            )
        }
        
        // FEEDBACK AREA
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 80.dp)
        ) {
            when {
                showVolumeFeedbackState -> Text(
                    text = "Volume: ${(viewModel.currentVolume.value.toFloat() / viewModel.maxVolume * 100).toInt()}%",
                    style = TextStyle(color = Color.White, fontSize = 14.sp),
                    modifier = Modifier
                        .background(Color.DarkGray)
                        .padding(8.dp)
                )
                isSpeedingUp -> Text(
                    text = "2X",
                    style = TextStyle(color = Color.White, fontSize = 14.sp),
                    modifier = Modifier
                        .background(Color.DarkGray)
                        .padding(8.dp)
                )
                showQuickSeekFeedback -> Text(
                    text = quickSeekFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 14.sp),
                    modifier = Modifier
                        .background(Color.DarkGray)
                        .padding(8.dp)
                )
                showSeekTime -> Text(
                    text = if (seekDirection.isNotEmpty()) "$seekTargetTime $seekDirection" else seekTargetTime,
                    style = TextStyle(color = Color.White, fontSize = 14.sp),
                    modifier = Modifier
                        .background(Color.DarkGray)
                        .padding(8.dp)
                )
                showPlaybackFeedback -> Text(
                    text = playbackFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 14.sp),
                    modifier = Modifier
                        .background(Color.DarkGray)
                        .padding(8.dp)
                )
            }
        }
        
        // DEBUG TOGGLE BUTTON - Bottom Right
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .clickable { showDebug = !showDebug }
                .background(Color.DarkGray.copy(alpha = 0.8f))
                .padding(8.dp)
        ) {
            Text(
                text = if (showDebug) "🔍 Hide Debug" else "🔍 Show Debug",
                color = Color.White,
                fontSize = 12.sp
            )
        }
    }
}

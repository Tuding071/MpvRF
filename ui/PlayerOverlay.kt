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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.vivvvek.seeker.Seeker
import dev.vivvvek.seeker.SeekerDefaults
import dev.vivvvek.seeker.Segment
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.content.Intent
import android.net.Uri
import kotlin.math.abs

// ============================================
// MPV CONNECTION - Add these imports
// ============================================
import is.xyz.mpv.MPVLib
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

// ============================================
// PLAYER VIEWMODEL - Add this class before PlayerOverlay
// ============================================
class PlayerViewModel : ViewModel() {
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

    init {
        // Initialize MPV
        MPVLib.setPropertyString("hwdec", "auto")
        MPVLib.setPropertyString("vo", "gpu-next")
        MPVLib.setPropertyString("profile", "fast")
        MPVLib.setPropertyString("cache", "no")
        
        // Get file name from MPV
        updateFileName()
        
        // Start position updates
        viewModelScope.launch {
            while (isActive) {
                val pos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                val dur = MPVLib.getPropertyDouble("duration") ?: 1.0
                
                _position.value = pos.toFloat()
                _duration.value = dur.toFloat()
                _currentTime.value = formatTimeSimple(pos)
                _totalTime.value = formatTimeSimple(dur)
                _isPlaying.value = MPVLib.getPropertyBoolean("pause") == false
                
                delay(100) // Update every 100ms
            }
        }
    }

    private fun updateFileName() {
        val mediaTitle = MPVLib.getPropertyString("media-title")
        val path = MPVLib.getPropertyString("path")
        _fileName.value = when {
            !mediaTitle.isNullOrBlank() -> mediaTitle.substringBeforeLast(".")
            !path.isNullOrBlank() -> path.substringAfterLast("/").substringBeforeLast(".")
            else -> "Video"
        }
    }

    fun setVolume(volume: Int) {
        val newVolume = volume.coerceIn(0, maxVolume)
        _currentVolume.value = newVolume
        MPVLib.setPropertyInt("volume", newVolume)
    }

    fun adjustVolume(delta: Int) {
        setVolume(_currentVolume.value + delta)
    }

    fun setSpeed(speed: Float) {
        _playbackSpeed.value = speed
        MPVLib.setPropertyDouble("speed", speed.toDouble())
    }

    fun togglePause() {
        val isPaused = MPVLib.getPropertyBoolean("pause") ?: false
        MPVLib.setPropertyBoolean("pause", !isPaused)
        _isPlaying.value = !isPaused
    }

    fun seekTo(position: Double) {
        MPVLib.command("seek", position.toString(), "absolute", "exact")
    }

    fun seekRelative(seconds: Int) {
        MPVLib.command("seek", seconds.toString(), "relative", "exact")
    }

    override fun onCleared() {
        super.onCleared()
        MPVLib.destroy()
    }
}

// ============================================
// FORMAT TIME FUNCTION - Add this helper
// ============================================
private fun formatTimeSimple(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, secs) 
           else String.format("%02d:%02d", minutes, secs)
}

// ============================================
// YOUR ORIGINAL PLAYER OVERLAY - UPDATED TO USE VIEWMODEL
// ============================================
@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Use ViewModel state instead of local state
    val currentTime by viewModel.currentTime.collectAsState()
    val totalTime by viewModel.totalTime.collectAsState()
    val currentPosition by viewModel.position.collectAsState()
    val videoDuration by viewModel.duration.collectAsState()
    val fileName by viewModel.fileName.collectAsState()
    
    // Local UI state
    var seekTargetTime by remember { mutableStateOf("00:00") }
    var showSeekTime by remember { mutableStateOf(false) }
    var isSpeedingUp by remember { mutableStateOf(false) }
    var isPausing by remember { mutableStateOf(false) }
    var showSeekbar by remember { mutableStateOf(true) }
    
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
    
    // ========== UI HELPER FUNCTIONS ==========
    
    fun showPlaybackFeedback(text: String) {
        playbackFeedbackText = text
        showPlaybackFeedback = true
        coroutineScope.launch {
            delay(1000)
            showPlaybackFeedback = false
        }
    }
    
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
    // (Your existing UI code - unchanged)
    Box(modifier = modifier.fillMaxSize()) {
        // MAIN GESTURE AREA
        Box(modifier = Modifier.fillMaxSize()) {
            // TOP 5% - Ignore
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.05f).align(Alignment.TopStart))
            
            // CENTER AREA
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.95f).align(Alignment.BottomStart)) {
                // LEFT 5% - Ignore
                Box(modifier = Modifier.fillMaxWidth(0.05f).fillMaxHeight().align(Alignment.CenterStart))
                
                // CENTER 90% - All gestures
                Box(modifier = Modifier
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
                Box(modifier = Modifier.fillMaxWidth(0.05f).fillMaxHeight().align(Alignment.CenterEnd))
            }
        }
        
        // BOTTOM SEEK BAR
        if (showSeekbar) {
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .align(Alignment.BottomStart)
                .padding(horizontal = 60.dp)
                .offset(y = 3.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                        Row(modifier = Modifier.align(Alignment.CenterStart)) {
                            Text(
                                text = if (isSeeking || isDragging) "$seekTargetTime / $totalTime" 
                                       else "$currentTime / $totalTime",
                                style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier
                                    .background(Color.DarkGray.copy(alpha = 0.8f))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
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
                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 60.dp, y = 20.dp)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        
        // FEEDBACK AREA
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(y = 80.dp)) {
            when {
                showVolumeFeedbackState -> Text(
                    text = "Volume: ${(viewModel.currentVolume.value.toFloat() / viewModel.maxVolume * 100).toInt()}%",
                    style = TextStyle(color = Color.White, fontSize = 14.sp),
                    modifier = Modifier.background(Color.DarkGray).padding(8.dp)
                )
                isSpeedingUp -> Text(
                    text = "2X",
                    style = TextStyle(color = Color.White, fontSize = 14.sp),
                    modifier = Modifier.background(Color.DarkGray).padding(8.dp)
                )
                showQuickSeekFeedback -> Text(
                    text = quickSeekFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 14.sp),
                    modifier = Modifier.background(Color.DarkGray).padding(8.dp)
                )
                showSeekTime -> Text(
                    text = if (seekDirection.isNotEmpty()) "$seekTargetTime $seekDirection" else seekTargetTime,
                    style = TextStyle(color = Color.White, fontSize = 14.sp),
                    modifier = Modifier.background(Color.DarkGray).padding(8.dp)
                )
                showPlaybackFeedback -> Text(
                    text = playbackFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 14.sp),
                    modifier = Modifier.background(Color.DarkGray).padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun SimpleDraggableProgressBar(
    position: Float,
    duration: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    getFreshPosition: () -> Float,
    modifier: Modifier = Modifier
) {
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartPosition by remember { mutableStateOf(0f) }
    var hasPassedThreshold by remember { mutableStateOf(false) }
    var thresholdStartX by remember { mutableStateOf(0f) }
    
    val movementThresholdPx = with(LocalDensity.current) { 25.dp.toPx() }
    
    Box(modifier = modifier.height(48.dp)) {
        // Background
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .align(Alignment.CenterStart)
            .background(Color.Gray.copy(alpha = 0.6f)))
        
        // Progress fill
        Box(modifier = Modifier
            .fillMaxWidth(fraction = if (duration > 0) (position / duration).coerceIn(0f, 1f) else 0f)
            .height(4.dp)
            .align(Alignment.CenterStart)
            .background(Color.White))
        
        // Draggable area
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .align(Alignment.CenterStart)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragStartX = offset.x
                        dragStartPosition = getFreshPosition()
                        hasPassedThreshold = false
                        thresholdStartX = 0f
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val currentX = change.position.x
                        val totalMovementX = abs(currentX - dragStartX)
                        
                        if (!hasPassedThreshold) {
                            if (totalMovementX > movementThresholdPx) {
                                hasPassedThreshold = true
                                thresholdStartX = currentX
                            } else {
                                return@detectDragGestures
                            }
                        }
                        
                        val effectiveStartX = if (hasPassedThreshold) thresholdStartX else dragStartX
                        val deltaX = currentX - effectiveStartX
                        val deltaPosition = (deltaX / size.width) * duration
                        val newPosition = (dragStartPosition + deltaPosition).coerceIn(0f, duration)
                        onValueChange(newPosition)
                    },
                    onDragEnd = {
                        hasPassedThreshold = false
                        thresholdStartX = 0f
                        onValueChangeFinished()
                    }
                )
            }
        )
    }
}

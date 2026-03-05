package com.yourapp

import android.os.Bundle
import android.view.SurfaceHolder  // <-- ADD THIS
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import `is`.xyz.mpv.MPVLib

class MainActivity : ComponentActivity() {
    private lateinit var playerViewModel: PlayerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MPVLib.create(applicationContext)
        MPVLib.init()

        playerViewModel = PlayerViewModel()

        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        SurfaceView(context).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    MPVLib.attachSurface(holder.surface)
                                }
                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {}
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    MPVLib.detachSurface()
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                PlayerOverlay(
                    viewModel = playerViewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onDestroy() {
        MPVLib.destroy()
        super.onDestroy()
    }
}

class PlayerViewModel : ViewModel() {
    private val _currentVolume = MutableStateFlow(50)
    val currentVolume: StateFlow<Int> = _currentVolume.asStateFlow()
    val maxVolume = 100
}
.

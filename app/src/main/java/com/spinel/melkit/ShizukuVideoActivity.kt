package com.spinel.melkit

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Plays only the bundled tutorial matching the app's current UI language. */
class ShizukuVideoActivity : AppCompatActivity() {
    private lateinit var video: VideoView
    private lateinit var controls: MediaController
    private var playbackPosition = 0
    private var resumePlayback = true
    private var prepared = false
    private var foreground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackPosition = savedInstanceState?.getInt(POSITION) ?: 0
        resumePlayback = savedInstanceState?.getBoolean(PLAYING) ?: true
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF101513.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF101513.toInt()),
        )
        setContentView(R.layout.activity_shizuku_video)
        val root = findViewById<View>(R.id.shizuku_video_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        findViewById<View>(R.id.shizuku_video_back).setOnClickListener { finish() }
        video = findViewById(R.id.shizuku_video)
        controls = MediaController(this).apply {
            setAnchorView(findViewById(R.id.shizuku_video_frame))
        }
        video.setMediaController(controls)
        video.setOnPreparedListener {
            prepared = true
            findViewById<View>(R.id.shizuku_video_progress).visibility = View.GONE
            video.seekTo(playbackPosition)
            if (foreground) {
                if (resumePlayback) video.start()
                controls.show(4_000)
            }
        }
        video.setOnCompletionListener {
            playbackPosition = 0
            resumePlayback = false
            video.seekTo(0)
            if (foreground) controls.show(0)
        }
        video.setOnErrorListener { _, _, _ ->
            prepared = false
            resumePlayback = false
            controls.hide()
            findViewById<View>(R.id.shizuku_video_progress).visibility = View.GONE
            findViewById<View>(R.id.shizuku_video_failure).visibility = View.VISIBLE
            true
        }
        findViewById<View>(R.id.shizuku_video_retry).setOnClickListener {
            resumePlayback = true
            loadVideo()
        }
        loadVideo()
    }

    private fun loadVideo() {
        prepared = false
        controls.hide()
        video.stopPlayback()
        findViewById<View>(R.id.shizuku_video_failure).visibility = View.GONE
        findViewById<View>(R.id.shizuku_video_progress).visibility = View.VISIBLE
        // Use explicit resource IDs so per-app language selection also applies
        // when the system language differs, and resource shrinking retains both.
        val resource = if (resources.configuration.locales[0]?.language == "ar") {
            R.raw.shizuku_setup_ar
        } else {
            R.raw.shizuku_setup_en
        }
        video.setVideoURI(Uri.parse("android.resource://$packageName/$resource"))
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (prepared && resumePlayback) video.start()
    }

    override fun onPause() {
        rememberPlayback()
        foreground = false
        video.pause()
        controls.hide()
        super.onPause()
    }

    private fun rememberPlayback() {
        if (foreground && prepared) {
            playbackPosition = video.currentPosition.coerceAtLeast(0)
            resumePlayback = video.isPlaying
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberPlayback()
        outState.putInt(POSITION, playbackPosition)
        outState.putBoolean(PLAYING, resumePlayback)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        controls.hide()
        video.setOnPreparedListener(null)
        video.setOnCompletionListener(null)
        video.setOnErrorListener(null)
        video.stopPlayback()
        super.onDestroy()
    }

    private companion object {
        const val POSITION = "shizuku_video_position"
        const val PLAYING = "shizuku_video_playing"
    }
}

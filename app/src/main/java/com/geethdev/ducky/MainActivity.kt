package com.geethdev.ducky

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity(), GameView.GameListener {

    companion object {
        private const val PREFERENCES_NAME = "ducky_game"
        private const val KEY_CURRENT_LEVEL = "current_level"
    }

    private lateinit var gameView: GameView
    private lateinit var levelText: TextView
    private lateinit var winSubtitle: TextView
    private lateinit var neckBar: ProgressBar
    private lateinit var winOverlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        gameView = findViewById(R.id.gameView)
        levelText = findViewById(R.id.levelText)
        winSubtitle = findViewById(R.id.winSubtitle)
        neckBar = findViewById(R.id.neckBar)
        winOverlay = findViewById(R.id.winOverlay)
        val btnReset: Button = findViewById(R.id.btnReset)
        val btnNext: Button = findViewById(R.id.btnNext)

        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        gameView.setStartingLevel(preferences.getInt(KEY_CURRENT_LEVEL, 1))
        gameView.setListener(this)

        btnReset.setOnClickListener {
            winOverlay.visibility = View.GONE
            gameView.resetLevel()
        }
        btnNext.setOnClickListener {
            winOverlay.visibility = View.GONE
            gameView.nextLevel()
        }
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    // ---------------------------------------------------- GameView.GameListener

    override fun onLevelChanged(level: Int) {
        levelText.text = getString(R.string.level_format, level)
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putInt(KEY_CURRENT_LEVEL, level)
            .apply()
    }

    override fun onNeckChanged(used: Int, max: Int) {
        neckBar.max = max
        neckBar.progress = used
        val frac = if (max == 0) 0f else minOf(1f, used / max.toFloat())
        val color = Color.rgb((255 * frac).toInt(), (220 * (1f - frac * 0.7f)).toInt(), 64)
        neckBar.progressTintList = ColorStateList.valueOf(color)
    }

    override fun onWin(level: Int) {
        winSubtitle.text = getString(R.string.next_level_format, level + 1)
        winOverlay.visibility = View.VISIBLE
    }
}
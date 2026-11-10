package com.geethdev.ducky

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity(), GameView.GameListener {

    companion object {
        private const val PREFERENCES_NAME = "ducky_game"
        private const val KEY_CURRENT_LEVEL = "current_level"
        private const val KEY_HIGH_SCORE = "high_score"
        private const val KEY_HIGHEST_COMPLETED_LEVEL = "highest_completed_level"
        private const val LOADING_DURATION_MS = 500L
    }

    private lateinit var gameView: GameView
    private lateinit var gameScreen: View
    private lateinit var homeScreen: View
    private lateinit var levelsScreen: View
    private lateinit var highScoreText: TextView
    private lateinit var levelsGrid: GridLayout
    private lateinit var levelsEmptyText: TextView
    private lateinit var levelText: TextView
    private lateinit var winSubtitle: TextView
    private lateinit var neckBar: ProgressBar
    private lateinit var winOverlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        gameScreen = findViewById(R.id.gameScreen)
        homeScreen = findViewById(R.id.homeScreen)
        levelsScreen = findViewById(R.id.levelsScreen)
        val loadingScreen: View = findViewById(R.id.loadingScreen)
        highScoreText = findViewById(R.id.highScoreText)
        levelsGrid = findViewById(R.id.levelsGrid)
        levelsEmptyText = findViewById(R.id.levelsEmptyText)
        gameView = findViewById(R.id.gameView)
        levelText = findViewById(R.id.levelText)
        winSubtitle = findViewById(R.id.winSubtitle)
        neckBar = findViewById(R.id.neckBar)
        winOverlay = findViewById(R.id.winOverlay)
        val btnReset: Button = findViewById(R.id.btnReset)
        val btnNext: Button = findViewById(R.id.btnNext)
        val btnStartGame: Button = findViewById(R.id.btnStartGame)
        val btnLevels: Button = findViewById(R.id.btnLevels)
        val btnLevelsBack: Button = findViewById(R.id.btnLevelsBack)

        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        if (!preferences.contains(KEY_HIGHEST_COMPLETED_LEVEL)) {
            preferences.edit()
                .putInt(KEY_HIGHEST_COMPLETED_LEVEL, maxOf(0, preferences.getInt(KEY_HIGH_SCORE, 1) - 1))
                .apply()
        }
        gameView.setStartingLevel(preferences.getInt(KEY_CURRENT_LEVEL, 1))
        gameView.setListener(this)
        updateHighScore()

        btnStartGame.setOnClickListener {
            val highestLevel = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getInt(KEY_HIGH_SCORE, 1)
            gameView.setStartingLevel(highestLevel)
            homeScreen.visibility = View.GONE
            gameScreen.visibility = View.VISIBLE
        }
        btnLevels.setOnClickListener {
            homeScreen.visibility = View.GONE
            levelsScreen.visibility = View.VISIBLE
            updateLevels()
        }
        btnLevelsBack.setOnClickListener { showHomeScreen() }
        btnReset.setOnClickListener {
            winOverlay.visibility = View.GONE
            gameView.resetLevel()
        }
        btnNext.setOnClickListener {
            winOverlay.visibility = View.GONE
            gameView.nextLevel()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                loadingScreen.visibility = View.GONE
                homeScreen.visibility = View.VISIBLE
            }
        }, LOADING_DURATION_MS)
    }

    private fun updateHighScore() {
        val highScore = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getInt(KEY_HIGH_SCORE, 1)
        highScoreText.text = getString(R.string.high_score_format, highScore)
    }

    private fun updateLevels() {
        val highestCompleted = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getInt(KEY_HIGHEST_COMPLETED_LEVEL, 0)
        levelsGrid.removeAllViews()
        levelsEmptyText.visibility = if (highestCompleted == 0) View.VISIBLE else View.GONE
        val density = resources.displayMetrics.density
        val tileSize = (
            (resources.displayMetrics.widthPixels - (40 * density).toInt() - 24) / 3
        ).coerceAtLeast((64 * density).toInt())

        for (level in 1..highestCompleted) {
            val button = Button(this).apply {
                text = getString(R.string.completed_level_format, level)
                setTextColor(getColor(R.color.outline_dark))
                textSize = 14f
                isAllCaps = false
                background = getDrawable(R.drawable.bg_button)
                setOnClickListener {
                    gameView.setStartingLevel(level)
                    winOverlay.visibility = View.GONE
                    levelsScreen.visibility = View.GONE
                    gameScreen.visibility = View.VISIBLE
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = tileSize
                height = tileSize
                setMargins(4, 4, 4, 4)
            }
            levelsGrid.addView(button, params)
        }
    }

    private fun showHomeScreen() {
        levelsScreen.visibility = View.GONE
        gameScreen.visibility = View.GONE
        homeScreen.visibility = View.VISIBLE
        updateHighScore()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (gameScreen.visibility == View.VISIBLE) {
            winOverlay.visibility = View.GONE
            showHomeScreen()
        } else if (levelsScreen.visibility == View.VISIBLE) {
            showHomeScreen()
        } else {
            super.onBackPressed()
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
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val highScore = maxOf(preferences.getInt(KEY_HIGH_SCORE, 1), level)
        preferences.edit()
            .putInt(KEY_CURRENT_LEVEL, level)
            .putInt(KEY_HIGH_SCORE, highScore)
            .apply()
        updateHighScore()
    }

    override fun onNeckChanged(used: Int, max: Int) {
        neckBar.max = max
        neckBar.progress = used
        val frac = if (max == 0) 0f else minOf(1f, used / max.toFloat())
        val color = Color.rgb((255 * frac).toInt(), (220 * (1f - frac * 0.7f)).toInt(), 64)
        neckBar.progressTintList = ColorStateList.valueOf(color)
    }

    override fun onWin(level: Int) {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        preferences.edit()
            .putInt(
                KEY_HIGHEST_COMPLETED_LEVEL,
                maxOf(preferences.getInt(KEY_HIGHEST_COMPLETED_LEVEL, 0), level)
            )
            .apply()
        winSubtitle.text = getString(R.string.next_level_format, level + 1)
        winOverlay.visibility = View.VISIBLE
    }
}
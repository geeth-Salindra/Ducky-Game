package com.geethdev.ducky

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

class MainActivity : Activity(), GameView.GameListener {

    companion object {
        private const val TAG = "DuckyAds"
        private const val BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
        private const val REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
        private const val PREFERENCES_NAME = "ducky_game"
        private const val KEY_CURRENT_LEVEL = "current_level"
        private const val KEY_HIGH_SCORE = "high_score"
        private const val KEY_HIGHEST_COMPLETED_LEVEL = "highest_completed_level"
        private const val LOADING_DURATION_MS = 500L
        private const val TIMER_UPDATE_INTERVAL_MS = 50L
        private const val KEY_LAST_CLEAR_TIME_PREFIX = "last_clear_time_"
    }

    private lateinit var gameView: GameView
    private lateinit var gameScreen: View
    private lateinit var homeScreen: View
    private lateinit var levelsScreen: View
    private lateinit var highScoreText: TextView
    private lateinit var timerText: TextView
    private lateinit var levelsGrid: GridLayout
    private lateinit var levelsEmptyText: TextView
    private lateinit var levelsProgressText: TextView
    private lateinit var levelText: TextView
    private lateinit var winSubtitle: TextView
    private lateinit var rewardedAdStatus: TextView
    private lateinit var btnNext: Button
    private lateinit var neckBar: ProgressBar
    private lateinit var winOverlay: View
    private lateinit var bannerAdContainer: FrameLayout
    private var bannerAd: AdView? = null
    private var rewardedAd: RewardedAd? = null
    private var isRewardedAdLoading = false
    private var rewardEarned = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerStartedAt = 0L
    private var timerLevel = 0
    private var timerRunning = false
    private val timerUpdate = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            updateTimerText(SystemClock.elapsedRealtime() - timerStartedAt)
            timerHandler.postDelayed(this, TIMER_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        gameScreen = findViewById(R.id.gameScreen)
        homeScreen = findViewById(R.id.homeScreen)
        levelsScreen = findViewById(R.id.levelsScreen)
        val loadingScreen: View = findViewById(R.id.loadingScreen)
        highScoreText = findViewById(R.id.highScoreText)
        timerText = findViewById(R.id.timerText)
        levelsGrid = findViewById(R.id.levelsGrid)
        levelsEmptyText = findViewById(R.id.levelsEmptyText)
        levelsProgressText = findViewById(R.id.levelsProgressText)
        gameView = findViewById(R.id.gameView)
        levelText = findViewById(R.id.levelText)
        winSubtitle = findViewById(R.id.winSubtitle)
        rewardedAdStatus = findViewById(R.id.rewardedAdStatus)
        neckBar = findViewById(R.id.neckBar)
        winOverlay = findViewById(R.id.winOverlay)
        bannerAdContainer = findViewById(R.id.bannerAdContainer)
        val btnReset: Button = findViewById(R.id.btnReset)
        btnNext = findViewById(R.id.btnNext)
        val btnWinHome: Button = findViewById(R.id.btnWinHome)
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
            hideMenuBanner()
            gameScreen.visibility = View.VISIBLE
            startLevelTimer(highestLevel)
        }
        btnLevels.setOnClickListener {
            homeScreen.visibility = View.GONE
            levelsScreen.visibility = View.VISIBLE
            updateLevels()
            showMenuBanner()
        }
        btnLevelsBack.setOnClickListener { showHomeScreen() }
        btnReset.setOnClickListener {
            winOverlay.visibility = View.GONE
            gameView.resetLevel()
            startLevelTimer(gameView.currentLevel)
        }
        btnNext.setOnClickListener {
            val ad = rewardedAd
            if (ad == null) {
                loadRewardedAd()
            } else {
                showRewardedAd(ad)
            }
        }
        btnWinHome.setOnClickListener { showHomeScreen() }
        initializeAds()

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                loadingScreen.visibility = View.GONE
                homeScreen.visibility = View.VISIBLE
            }
        }, LOADING_DURATION_MS)
    }

    private fun initializeAds() {
        MobileAds.initialize(this) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                createBannerAd()
                loadRewardedAd()
            }
        }
    }

    private fun createBannerAd() {
        val adView = AdView(this)
        adView.adUnitId = BANNER_AD_UNIT_ID
        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        adView.setAdSize(
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, widthDp)
        )
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                showMenuBanner()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.w(TAG, "Banner ad failed to load: ${error.message}")
                bannerAdContainer.visibility = View.GONE
            }
        }
        bannerAdContainer.removeAllViews()
        bannerAdContainer.addView(
            adView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        bannerAd = adView
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun showMenuBanner() {
        bannerAdContainer.visibility =
            if (levelsScreen.visibility == View.VISIBLE && bannerAd != null) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun hideMenuBanner() {
        bannerAdContainer.visibility = View.GONE
    }

    private fun loadRewardedAd() {
        if (isRewardedAdLoading || rewardedAd != null || isFinishing || isDestroyed) return

        isRewardedAdLoading = true
        updateRewardedAdPrompt()
        RewardedAd.load(
            this,
            REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isRewardedAdLoading = false
                    rewardedAd = ad
                    updateRewardedAdPrompt()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isRewardedAdLoading = false
                    rewardedAd = null
                    Log.w(TAG, "Rewarded ad failed to load: ${error.message}")
                    updateRewardedAdPrompt()
                }
            }
        )
    }

    private fun updateRewardedAdPrompt() {
        when {
            rewardedAd != null -> {
                rewardedAdStatus.setText(R.string.rewarded_ad_ready)
                btnNext.setText(R.string.watch_ad_to_continue)
                btnNext.isEnabled = true
            }
            isRewardedAdLoading -> {
                rewardedAdStatus.setText(R.string.rewarded_ad_loading)
                btnNext.setText(R.string.rewarded_ad_loading_button)
                btnNext.isEnabled = false
            }
            else -> {
                rewardedAdStatus.setText(R.string.rewarded_ad_unavailable)
                btnNext.setText(R.string.retry_ad)
                btnNext.isEnabled = true
            }
        }
    }

    private fun showRewardedAd(ad: RewardedAd) {
        rewardedAd = null
        rewardEarned = false
        btnNext.isEnabled = false
        rewardedAdStatus.setText(R.string.rewarded_ad_watch_to_continue)
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (rewardEarned) {
                    startNextLevel()
                    loadRewardedAd()
                } else {
                    rewardedAdStatus.setText(R.string.rewarded_ad_not_completed)
                    loadRewardedAd()
                }
            }

            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                Log.w(TAG, "Rewarded ad failed to show: ${error.message}")
                rewardedAdStatus.setText(R.string.rewarded_ad_unavailable)
                updateRewardedAdPrompt()
                loadRewardedAd()
            }
        }
        ad.show(this) {
            rewardEarned = true
            rewardedAdStatus.setText(R.string.rewarded_ad_reward_earned)
        }
    }

    private fun startNextLevel() {
        winOverlay.visibility = View.GONE
        btnNext.isEnabled = true
        gameView.nextLevel()
        startLevelTimer(gameView.currentLevel)
    }

    private fun updateHighScore() {
        val highScore = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getInt(KEY_HIGH_SCORE, 1)
        highScoreText.text = getString(R.string.high_score_format, highScore)
    }

    private fun updateLevels() {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val highestCompleted = preferences.getInt(KEY_HIGHEST_COMPLETED_LEVEL, 0)
        levelsGrid.removeAllViews()
        levelsEmptyText.visibility = if (highestCompleted == 0) View.VISIBLE else View.GONE
        val density = resources.displayMetrics.density
        val screenWidthDp = resources.displayMetrics.widthPixels / density
        val tileWidth = ((screenWidthDp - 40f - 30f) / 3f * density)
            .toInt()
            .coerceAtLeast((64 * density).toInt())
        val tileHeight = (116 * density).toInt()
        val tileMargin = (5 * density).toInt()
        levelsProgressText.text = resources.getQuantityString(
            R.plurals.levels_completed_count,
            highestCompleted,
            highestCompleted
        )

        for (level in 1..highestCompleted) {
            val lastClearTime = if (preferences.contains(lastClearTimeKey(level))) {
                formatElapsedTime(preferences.getLong(lastClearTimeKey(level), 0L))
            } else {
                getString(R.string.no_clear_time)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(
                    (6 * density).toInt(),
                    (10 * density).toInt(),
                    (6 * density).toInt(),
                    (10 * density).toInt()
                )
                background = getDrawable(R.drawable.bg_level_card)
                elevation = 3 * density
                isClickable = true
                isFocusable = true
                contentDescription = getString(
                    R.string.level_tile_content_description,
                    level,
                    lastClearTime
                )
                setOnClickListener {
                    gameView.setStartingLevel(level)
                    winOverlay.visibility = View.GONE
                    levelsScreen.visibility = View.GONE
                    hideMenuBanner()
                    gameScreen.visibility = View.VISIBLE
                    startLevelTimer(level)
                }
            }

            val levelNumber = TextView(this).apply {
                text = level.toString()
                setTextColor(getColor(R.color.outline_dark))
                textSize = 30f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val completedLabel = TextView(this).apply {
                text = getString(R.string.level_completed)
                setTextColor(getColor(R.color.hud_top))
                textSize = 9f
                letterSpacing = 0.06f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val timeLabel = TextView(this).apply {
                text = getString(R.string.level_last_time_format, lastClearTime)
                setTextColor(getColor(R.color.outline_dark))
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 1
            }
            card.addView(levelNumber)
            card.addView(completedLabel)
            card.addView(timeLabel)

            val params = GridLayout.LayoutParams().apply {
                width = tileWidth
                height = tileHeight
                setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
            }
            levelsGrid.addView(card, params)
        }
    }

    private fun showHomeScreen() {
        stopLevelTimer()
        winOverlay.visibility = View.GONE
        levelsScreen.visibility = View.GONE
        gameScreen.visibility = View.GONE
        homeScreen.visibility = View.VISIBLE
        showMenuBanner()
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
        if (gameScreen.visibility == View.VISIBLE && (!timerRunning || timerLevel != level)) {
            startLevelTimer(level)
        }
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
        val clearTime = stopLevelTimer()
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        preferences.edit()
            .putInt(
                KEY_HIGHEST_COMPLETED_LEVEL,
                maxOf(preferences.getInt(KEY_HIGHEST_COMPLETED_LEVEL, 0), level)
            )
            .putLong(lastClearTimeKey(level), clearTime)
            .apply()
        winSubtitle.text = getString(R.string.next_level_format, level + 1)
        winOverlay.visibility = View.VISIBLE
        updateRewardedAdPrompt()
        if (rewardedAd == null && !isRewardedAdLoading) {
            loadRewardedAd()
        }
    }

    private fun startLevelTimer(level: Int) {
        timerLevel = level
        timerStartedAt = SystemClock.elapsedRealtime()
        timerRunning = true
        timerHandler.removeCallbacks(timerUpdate)
        updateTimerText(0L)
        timerHandler.post(timerUpdate)
    }

    private fun stopLevelTimer(): Long {
        val elapsed = if (timerRunning) {
            SystemClock.elapsedRealtime() - timerStartedAt
        } else {
            0L
        }
        timerRunning = false
        timerHandler.removeCallbacks(timerUpdate)
        updateTimerText(elapsed)
        return elapsed
    }

    private fun updateTimerText(elapsedMs: Long) {
        timerText.text = formatElapsedTime(elapsedMs)
    }

    private fun formatElapsedTime(elapsedMs: Long): String {
        val totalCentiseconds = elapsedMs.coerceAtLeast(0L) / 10L
        val minutes = totalCentiseconds / 6_000L
        val seconds = (totalCentiseconds / 100L) % 60L
        val centiseconds = totalCentiseconds % 100L
        return getString(
            R.string.elapsed_time_format,
            minutes,
            seconds,
            centiseconds
        )
    }

    private fun lastClearTimeKey(level: Int): String = "$KEY_LAST_CLEAR_TIME_PREFIX$level"

    override fun onResume() {
        super.onResume()
        bannerAd?.resume()
    }

    override fun onPause() {
        bannerAd?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        bannerAd?.destroy()
        bannerAd = null
        super.onDestroy()
    }
}
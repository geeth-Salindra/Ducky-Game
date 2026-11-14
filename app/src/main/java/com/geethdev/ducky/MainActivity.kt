package com.geethdev.ducky

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlin.random.Random

class MainActivity : ComponentActivity(), GameView.GameListener {

    companion object {
        private const val TAG = "DuckyAds"
        private const val BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
        private const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
        private const val INTERSTITIAL_AD_CHANCE = 0.4f
        private const val PREFERENCES_NAME = "ducky_game"
        private const val KEY_CURRENT_LEVEL = "current_level"
        private const val KEY_HIGH_SCORE = "high_score"
        private const val KEY_HIGHEST_COMPLETED_LEVEL = "highest_completed_level"
        private const val LOADING_DURATION_MS = 500L
        private const val COUNTDOWN_INTERVAL_MS = 1_000L
        private const val COUNTDOWN_START = 3
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
    private lateinit var interstitialAdStatus: TextView
    private lateinit var pauseMessage: TextView
    private lateinit var btnNext: Button
    private lateinit var btnReset: Button
    private lateinit var btnResume: Button
    private lateinit var btnPause: ImageButton
    private lateinit var neckBar: ProgressBar
    private lateinit var winOverlay: View
    private lateinit var pauseOverlay: View
    private lateinit var winCard: View
    private lateinit var bannerAdContainer: FrameLayout
    private lateinit var privacyPolicyButton: Button
    private lateinit var privacyOptionsButton: Button
    private var bannerAd: AdView? = null
    private var interstitialAd: InterstitialAd? = null
    private var isInterstitialAdLoading = false
    private var consentInformation: ConsentInformation? = null
    private var isAdsSdkInitialized = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerStartedAt = 0L
    private var timerAccumulatedMs = 0L
    private var timerLevel = 0
    private var timerRunning = false
    private var countdownRemaining = 0
    private var countdownActive = false
    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownTick = object : Runnable {
        override fun run() {
            if (!countdownActive) return
            countdownRemaining--
            if (countdownRemaining > 0) {
                showCountdownNumber()
                countdownHandler.postDelayed(this, COUNTDOWN_INTERVAL_MS)
            } else {
                countdownActive = false
                pauseOverlay.visibility = View.GONE
                gameView.resumeGame()
                btnPause.isEnabled = true
                btnReset.isEnabled = true
                resumeLevelTimer()
            }
        }
    }
    private val timerUpdate = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            updateTimerText(timerAccumulatedMs + SystemClock.elapsedRealtime() - timerStartedAt)
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
        interstitialAdStatus = findViewById(R.id.interstitialAdStatus)
        pauseMessage = findViewById(R.id.pauseMessage)
        neckBar = findViewById(R.id.neckBar)
        winOverlay = findViewById(R.id.winOverlay)
        pauseOverlay = findViewById(R.id.pauseOverlay)
        winCard = findViewById(R.id.winCard)
        bannerAdContainer = findViewById(R.id.bannerAdContainer)
        privacyPolicyButton = findViewById(R.id.btnPrivacyPolicy)
        privacyOptionsButton = findViewById(R.id.btnPrivacyOptions)
        btnReset = findViewById(R.id.btnReset)
        btnNext = findViewById(R.id.btnNext)
        btnPause = findViewById(R.id.btnPause)
        btnResume = findViewById(R.id.btnResume)
        val btnWinHome: Button = findViewById(R.id.btnWinHome)
        val btnStartGame: Button = findViewById(R.id.btnStartGame)
        val btnLevels: Button = findViewById(R.id.btnLevels)
        val btnLevelsBack: Button = findViewById(R.id.btnLevelsBack)
        listOf(btnReset, btnNext, btnPause, btnResume, btnWinHome, btnStartGame, btnLevels, btnLevelsBack)
            .forEach { addPressFeedback(it) }
        addPressFeedback(privacyPolicyButton)
        addPressFeedback(privacyOptionsButton)

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
            showAnimated(gameScreen)
            btnPause.isEnabled = true
            beginCountdown()
        }
        btnPause.setOnClickListener { pauseGame() }
        btnResume.setOnClickListener {
            if (gameView.isPaused && !countdownActive) beginCountdown()
        }
        btnLevels.setOnClickListener {
            homeScreen.visibility = View.GONE
            showAnimated(levelsScreen)
            updateLevels()
            showMenuBanner()
        }
        btnLevelsBack.setOnClickListener { showHomeScreen() }
        btnReset.setOnClickListener {
            winOverlay.visibility = View.GONE
            gameView.resetLevel()
            btnPause.isEnabled = true
            startLevelTimer(gameView.currentLevel)
        }
        btnNext.setOnClickListener {
            if (hasInternetConnection() && Random.nextFloat() < INTERSTITIAL_AD_CHANCE) {
                val ad = interstitialAd
                if (ad != null) {
                    showInterstitialAd(ad)
                } else {
                    loadInterstitialAd()
                    startNextLevel()
                }
            } else {
                startNextLevel()
            }
        }
        btnWinHome.setOnClickListener { showHomeScreen() }
        val privacyPolicyUri = getString(R.string.privacy_policy_url)
            .takeIf(String::isNotBlank)
            ?.let(Uri::parse)
            ?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() }
        privacyPolicyButton.visibility =
            if (privacyPolicyUri == null) View.GONE else View.VISIBLE
        privacyPolicyButton.setOnClickListener {
            privacyPolicyUri?.let { uri ->
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }
        privacyOptionsButton.setOnClickListener {
            UserMessagingPlatform.showPrivacyOptionsForm(this) { formError ->
                if (formError != null) {
                    Log.w(TAG, "Privacy options form failed: ${formError.message}")
                }
                refreshAdsAfterConsent()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    gameScreen.visibility == View.VISIBLE -> {
                        winOverlay.visibility = View.GONE
                        showHomeScreen()
                    }
                    levelsScreen.visibility == View.VISIBLE -> showHomeScreen()
                    else -> finish()
                }
            }
        })
        initializeAds()

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                loadingScreen.visibility = View.GONE
                showAnimated(homeScreen)
            }
        }, LOADING_DURATION_MS)
    }

    private fun initializeAds() {
        val information = UserMessagingPlatform.getConsentInformation(this)
        consentInformation = information
        val params = ConsentRequestParameters.Builder().build()
        information.requestConsentInfoUpdate(
            this,
            params,
            {
                updatePrivacyOptionsButton()
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "Consent form failed: ${formError.message}")
                    }
                    refreshAdsAfterConsent()
                }
            },
            { error ->
                Log.w(TAG, "Consent information update failed: ${error.message}")
                updatePrivacyOptionsButton()
                refreshAdsAfterConsent()
            }
        )
    }

    private fun updatePrivacyOptionsButton() {
        privacyOptionsButton.visibility =
            if (consentInformation?.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
            ) View.VISIBLE else View.GONE
    }

    private fun refreshAdsAfterConsent() {
        updatePrivacyOptionsButton()
        val information = consentInformation ?: return
        if (!information.canRequestAds()) {
            interstitialAd = null
            bannerAd?.destroy()
            bannerAd = null
            bannerAdContainer.removeAllViews()
            bannerAdContainer.visibility = View.GONE
            return
        }
        if (isAdsSdkInitialized) {
            if (hasInternetConnection()) {
                if (bannerAd == null) createBannerAd()
                loadInterstitialAd()
            }
            return
        }

        isAdsSdkInitialized = true
        MobileAds.initialize(this) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (consentInformation?.canRequestAds() == true && hasInternetConnection()) {
                    createBannerAd()
                    loadInterstitialAd()
                }
            }
        }
    }

    private fun createBannerAd() {
        if (consentInformation?.canRequestAds() != true || !hasInternetConnection()) return

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
        if (levelsScreen.visibility != View.VISIBLE || !hasInternetConnection()) {
            bannerAdContainer.visibility = View.GONE
            return
        }
        if (bannerAd == null) {
            createBannerAd()
            return
        }
        bannerAdContainer.visibility = View.VISIBLE
    }

    private fun hideMenuBanner() {
        bannerAdContainer.visibility = View.GONE
    }

    private fun loadInterstitialAd() {
        if (consentInformation?.canRequestAds() != true || !hasInternetConnection() || isInterstitialAdLoading ||
            interstitialAd != null || isFinishing || isDestroyed
        ) return

        isInterstitialAdLoading = true
        InterstitialAd.load(
            this,
            INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isInterstitialAdLoading = false
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isInterstitialAdLoading = false
                    interstitialAd = null
                    Log.w(TAG, "Interstitial ad failed to load: ${error.message}")
                }
            }
        )
    }

    private fun showInterstitialAd(ad: InterstitialAd) {
        interstitialAd = null
        btnNext.isEnabled = false
        var didContinue = false
        fun continueAfterAd() {
            if (didContinue) return
            didContinue = true
            startNextLevel()
            loadInterstitialAd()
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                continueAfterAd()
            }

            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                Log.w(TAG, "Interstitial ad failed to show: ${error.message}")
                continueAfterAd()
            }
        }
        ad.show(this)
    }

    private fun hasInternetConnection(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun startNextLevel() {
        winOverlay.visibility = View.GONE
        btnNext.isEnabled = true
        btnPause.isEnabled = true
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
                    (14 * density).toInt()
                )
                background = getDrawable(R.drawable.bg_level_card)
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
                    showAnimated(gameScreen)
                    btnPause.isEnabled = true
                    beginCountdown()
                }
            }
            addPressFeedback(card)

            val levelNumber = TextView(this).apply {
                text = level.toString()
                setTextColor(getColor(R.color.wall_blue))
                textSize = 30f
                typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            val completedLabel = TextView(this).apply {
                text = getString(R.string.level_completed)
                setTextColor(getColor(R.color.success_text))
                textSize = 8.5f
                letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = getDrawable(R.drawable.bg_pill_success)
                setPadding(
                    (8 * density).toInt(),
                    (2 * density).toInt(),
                    (8 * density).toInt(),
                    (2 * density).toInt()
                )
            }
            val timeLabel = TextView(this).apply {
                text = getString(R.string.level_last_time_format, lastClearTime)
                setTextColor(getColor(R.color.outline_dark))
                alpha = 0.7f
                textSize = 10f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                maxLines = 1
            }
            card.addView(levelNumber)
            card.addView(
                completedLabel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            )
            card.addView(
                timeLabel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            )

            val params = GridLayout.LayoutParams().apply {
                width = tileWidth
                height = tileHeight
                setMargins(tileMargin, tileMargin, tileMargin, tileMargin)
            }
            levelsGrid.addView(card, params)
        }
    }

    // ------------------------------------------------------------- UI polish

    /** Fades a screen in instead of popping it on instantly. */
    private fun showAnimated(view: View) {
        if (view.visibility == View.VISIBLE) return
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(200L).start()
    }

    /** Fades the dim overlay in and pops the result card with a small overshoot. */
    private fun showWinOverlay() {
        winOverlay.animate().cancel()
        winOverlay.alpha = 0f
        winOverlay.visibility = View.VISIBLE
        winOverlay.animate().alpha(1f).setDuration(220L).start()

        winCard.animate().cancel()
        winCard.scaleX = 0.85f
        winCard.scaleY = 0.85f
        winCard.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(340L)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    /** Subtle "squish" while a button/card is held down. Clicks still go through. */
    @SuppressLint("ClickableViewAccessibility")
    private fun addPressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }
            false
        }
    }

    private fun showHomeScreen() {
        countdownHandler.removeCallbacks(countdownTick)
        countdownActive = false
        pauseOverlay.visibility = View.GONE
        gameView.pauseGame()
        stopLevelTimer()
        winOverlay.visibility = View.GONE
        levelsScreen.visibility = View.GONE
        gameScreen.visibility = View.GONE
        showAnimated(homeScreen)
        showMenuBanner()
        updateHighScore()
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
        if (gameScreen.visibility == View.VISIBLE && !countdownActive &&
            (!timerRunning || timerLevel != level)
        ) {
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
        btnPause.isEnabled = false
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        preferences.edit()
            .putInt(
                KEY_HIGHEST_COMPLETED_LEVEL,
                maxOf(preferences.getInt(KEY_HIGHEST_COMPLETED_LEVEL, 0), level)
            )
            .putLong(lastClearTimeKey(level), clearTime)
            .apply()
        winSubtitle.text = getString(R.string.next_level_format, level + 1)
        showWinOverlay()
        interstitialAdStatus.setText(R.string.interstitial_ad_optional)
        loadInterstitialAd()
    }

    private fun startLevelTimer(level: Int) {
        timerLevel = level
        timerAccumulatedMs = 0L
        timerStartedAt = SystemClock.elapsedRealtime()
        timerRunning = true
        timerHandler.removeCallbacks(timerUpdate)
        updateTimerText(0L)
        timerHandler.post(timerUpdate)
    }

    private fun resumeLevelTimer() {
        if (timerRunning) return
        timerLevel = gameView.currentLevel
        timerStartedAt = SystemClock.elapsedRealtime()
        timerRunning = true
        timerHandler.removeCallbacks(timerUpdate)
        updateTimerText(timerAccumulatedMs)
        timerHandler.post(timerUpdate)
    }

    private fun pauseLevelTimer() {
        if (!timerRunning) return
        timerAccumulatedMs += SystemClock.elapsedRealtime() - timerStartedAt
        timerRunning = false
        timerHandler.removeCallbacks(timerUpdate)
        updateTimerText(timerAccumulatedMs)
    }

    private fun stopLevelTimer(): Long {
        val elapsed = timerAccumulatedMs + if (timerRunning) {
            SystemClock.elapsedRealtime() - timerStartedAt
        } else {
            0L
        }
        timerRunning = false
        timerAccumulatedMs = 0L
        timerHandler.removeCallbacks(timerUpdate)
        updateTimerText(elapsed)
        return elapsed
    }

    private fun pauseGame() {
        if (gameView.isPaused || countdownActive || winOverlay.visibility == View.VISIBLE) return
        gameView.pauseGame()
        pauseLevelTimer()
        pauseMessage.text = getString(R.string.paused)
        pauseMessage.animate().cancel()
        pauseMessage.alpha = 1f
        pauseMessage.scaleX = 1f
        pauseMessage.scaleY = 1f
        btnResume.visibility = View.VISIBLE
        btnPause.isEnabled = false
        btnReset.isEnabled = false
        pauseOverlay.visibility = View.VISIBLE
    }

    private fun beginCountdown() {
        gameView.pauseGame()
        countdownHandler.removeCallbacks(countdownTick)
        countdownRemaining = COUNTDOWN_START
        countdownActive = true
        if (!timerRunning && timerAccumulatedMs == 0L) updateTimerText(0L)
        btnPause.isEnabled = false
        btnReset.isEnabled = false
        btnResume.visibility = View.GONE
        pauseOverlay.visibility = View.VISIBLE
        showCountdownNumber()
        countdownHandler.postDelayed(countdownTick, COUNTDOWN_INTERVAL_MS)
    }

    private fun showCountdownNumber() {
        pauseMessage.text = countdownRemaining.toString()
        pauseMessage.animate().cancel()
        pauseMessage.alpha = 0f
        pauseMessage.scaleX = 0.6f
        pauseMessage.scaleY = 0.6f
        pauseMessage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300L)
            .start()
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
        countdownHandler.removeCallbacks(countdownTick)
        timerHandler.removeCallbacks(timerUpdate)
        bannerAd?.destroy()
        bannerAd = null
        super.onDestroy()
    }
}
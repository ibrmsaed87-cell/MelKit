package com.spinel.melkit.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.spinel.melkit.BuildConfig
import com.spinel.melkit.R
import com.spinel.melkit.share.MelKitShareService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

enum class AdPlacement(vararg val routes: String) {
    BACKUP("library", "share"), RESTORE("backups", "bundles", "share"),
    BUNDLE("bundles", "share"), PROFILE("profiles"), SCAN("scanner")
}

/** Main-thread coordinator. Ads never own or retry a file transaction. */
class AdManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("ad_limits", Context.MODE_PRIVATE)
    private val policy = AdPolicy(preferences.getInt("normal_opens", 0),
        preferences.getLong("last_closed", 0), preferences.getLong("last_app_open", 0),
        preferences.getString("interstitials", "").orEmpty().split(',').mapNotNull(String::toLongOrNull))
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consent = UserMessagingPlatform.getConsentInformation(app)
    private val mutablePrivacyRequired = MutableStateFlow(false)
    val privacyRequired = mutablePrivacyRequired.asStateFlow()
    private var activity = WeakReference<Activity>(null)
    private var resumed = false
    private var foreground = false
    private var suppressNextOpen = false
    private var route = "home"
    private var appOpenAllowed = false
    private var breakAllowed = false
    private var privacyBusy = false
    private var consentRequested = false
    private var consentRequestId = 0L
    private var consentEpoch = 0
    private var initialized = false
    private var initializing = false
    private var interstitial: InterstitialAd? = null
    private var appOpen: AppOpenAd? = null
    private var interstitialLoadedAt = 0L
    private var appOpenLoadedAt = 0L
    private var loadingInterstitial = false
    private var loadingAppOpen = false
    private val interstitialLoadPolicy = AdLoadPolicy()
    private val appOpenLoadPolicy = AdLoadPolicy()
    private val preloadRetry = Runnable { preload() }
    private var showing = false
    private var shown = false
    private var showingOpen = false
    private var showId = 0L
    private var pendingResult: (() -> Unit)? = null

    fun onCreate(owner: Activity) {
        activity = WeakReference(owner)
        resumed = false
        consentRequested = false
    }

    fun updateHost(route: String, allowAppOpen: Boolean, allowBreak: Boolean) {
        val becameIdle = !breakAllowed && allowBreak
        this.route = route
        appOpenAllowed = allowAppOpen
        breakAllowed = allowBreak
        if (becameIdle) preload()
    }

    fun suppressExternalReturn() { suppressNextOpen = true }

    fun onUserAction(owner: Activity) {
        if (activity.get() === owner && resumed && !privacyBusy && !showing) {
            policy.userAction()
        }
    }

    fun onResume(owner: Activity, systemDialog: Boolean) {
        activity = WeakReference(owner)
        resumed = true
        val firstResume = !foreground
        foreground = true
        val suppressed = suppressNextOpen || systemDialog || privacyBusy || showing
        suppressNextOpen = false
        if (firstResume && !suppressed) {
            policy.normalForeground()
            persistLimits()
            // This is the only app-open presentation point. Load callbacks never show ads.
            if (appOpenAllowed && policy.appOpenEligible(now()) && canShow(owner)) {
                val ad = appOpen?.takeIf { fresh(appOpenLoadedAt, 4 * 60 * 60_000L) }
                if (ad != null) {
                    appOpen = null
                    present(owner, true, {}, { callback -> ad.fullScreenContentCallback = callback; ad.show(owner) })
                }
            }
        }
        if (!systemDialog && !showing) requestConsent(owner)
        preload()
    }

    fun onPause(owner: Activity) {
        if (activity.get() === owner) {
            resumed = false
            handler.removeCallbacks(preloadRetry)
        }
    }

    fun onStop(owner: Activity, changingConfiguration: Boolean) {
        if (activity.get() === owner && !changingConfiguration) foreground = false
    }

    fun onDestroy(owner: Activity) {
        if (activity.get() !== owner) return
        resumed = false
        handler.removeCallbacks(preloadRetry)
        activity.clear()
        // A destroyed host must never leave a verified result waiting on an SDK callback.
        if (showing) finishPresentation(showId)
        if (privacyBusy) { consentRequestId++; privacyBusy = false; consentRequested = false }
    }

    /** Called after verified IO, before publishing its result. No ad wait when unavailable. */
    fun afterTask(placement: AdPlacement, result: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        policy.completedTask()
        val owner = activity.get()
        val ad = interstitial?.takeIf { fresh(interstitialLoadedAt, 60 * 60_000L) }
        if (owner == null || ad == null || route !in placement.routes || !breakAllowed ||
            !policy.interstitialEligible(now()) || !canShow(owner)) {
            result()
            preload()
            return
        }
        interstitial = null
        present(owner, false, result, { callback -> ad.fullScreenContentCallback = callback; ad.show(owner) })
    }

    fun showPrivacyOptions(owner: Activity) {
        if (!mutablePrivacyRequired.value || privacyBusy || showing || !resumed ||
            activity.get() !== owner || owner.isDestroyed) return
        privacyBusy = true
        suppressNextOpen = true
        invalidateAds()
        val requestId = ++consentRequestId
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(owner) { error ->
                if (requestId == consentRequestId) {
                    privacyBusy = false
                    updatePrivacyState()
                    if (error != null) Toast.makeText(owner, R.string.ad_privacy_error, Toast.LENGTH_SHORT).show()
                    initializeIfAllowed()
                }
            }
        } catch (_: RuntimeException) {
            privacyBusy = false
            updatePrivacyState()
            Toast.makeText(owner, R.string.ad_privacy_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestConsent(owner: Activity) {
        if (consentRequested || privacyBusy || !resumed) return
        consentRequested = true
        privacyBusy = true
        val requestId = ++consentRequestId
        try {
            consent.requestConsentInfoUpdate(owner, ConsentRequestParameters.Builder().build(), {
                if (requestId == consentRequestId) {
                    if (activity.get() !== owner || owner.isDestroyed || !resumed) {
                        privacyBusy = false
                        consentRequested = false
                        updatePrivacyState()
                        activity.get()?.takeIf { resumed && !it.isDestroyed }?.let(::requestConsent)
                    } else {
                        try {
                            UserMessagingPlatform.loadAndShowConsentFormIfRequired(owner) { error ->
                                if (requestId == consentRequestId) {
                                    privacyBusy = false
                                    updatePrivacyState()
                                    if (error != null) Log.i(TAG, "Consent form unavailable; respecting UMP request state")
                                    initializeIfAllowed()
                                }
                            }
                        } catch (_: RuntimeException) {
                            privacyBusy = false
                            updatePrivacyState()
                            initializeIfAllowed()
                        }
                    }
                }
            }, {
                if (requestId == consentRequestId) {
                    privacyBusy = false
                    updatePrivacyState()
                    initializeIfAllowed()
                }
            })
        } catch (_: RuntimeException) {
            privacyBusy = false
            updatePrivacyState()
            initializeIfAllowed()
        }
    }

    private fun updatePrivacyState() {
        mutablePrivacyRequired.value = consent.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (!consent.canRequestAds()) invalidateAds()
    }

    private fun initializeIfAllowed() {
        if (privacyBusy || !consent.canRequestAds()) return
        if (initialized) { preload(); return }
        if (initializing) return
        initializing = true
        // Consent is resolved before SDK initialization or any ad request.
        MobileAds.setRequestConfiguration(RequestConfiguration.Builder()
            .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T).build())
        scope.launch(Dispatchers.IO) {
            try {
                MobileAds.initialize(app) {
                    handler.post { initializing = false; initialized = true; preload() }
                }
            } catch (_: RuntimeException) {
                handler.post { initializing = false; Log.i(TAG, "Ad SDK initialization unavailable") }
            }
        }
    }

    private fun canShow(owner: Activity): Boolean = initialized && consentRequested && consent.canRequestAds() &&
        resumed && !privacyBusy && !showing && !owner.isFinishing && !owner.isDestroyed &&
        !MelKitShareService.state.value.active

    private fun present(owner: Activity, isOpen: Boolean, result: () -> Unit,
        show: (FullScreenContentCallback) -> Unit) {
        if (!canShow(owner)) { result(); return }
        val id = ++showId
        showing = true
        shown = false
        showingOpen = isOpen
        pendingResult = result
        suppressNextOpen = true
        val callback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                if (id != showId || !showing || shown) return
                shown = true
                policy.shown(isOpen, now())
                persistLimits()
            }
            override fun onAdDismissedFullScreenContent() = finishPresentation(id)
            override fun onAdFailedToShowFullScreenContent(error: AdError) = finishPresentation(id)
        }
        try { show(callback) }
        catch (_: RuntimeException) { finishPresentation(id) }
    }

    private fun finishPresentation(id: Long) {
        if (id != showId || !showing) return
        if (shown) { policy.closed(now()); persistLimits() }
        showing = false
        shown = false
        if (showingOpen) appOpen = null else interstitial = null
        val result = pendingResult
        pendingResult = null
        result?.invoke()
        preload()
    }

    private fun invalidateAds() {
        handler.removeCallbacks(preloadRetry)
        consentEpoch++
        interstitial = null
        appOpen = null
        loadingInterstitial = false
        loadingAppOpen = false
    }

    private fun preload() {
        handler.removeCallbacks(preloadRetry)
        if (!initialized || !consent.canRequestAds() || privacyBusy || !resumed || showing ||
            MelKitShareService.state.value.active) return
        val epoch = consentEpoch
        if (!fresh(interstitialLoadedAt, 60 * 60_000L)) interstitial = null
        if (!fresh(appOpenLoadedAt, 4 * 60 * 60_000L)) appOpen = null
        if (interstitial == null && !loadingInterstitial && interstitialLoadPolicy.canLoad(loadClock())) {
            loadingInterstitial = true
            try {
                InterstitialAd.load(app, if (BuildConfig.TEST_ADS) TEST_INTERSTITIAL else INTERSTITIAL,
                    AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            if (epoch != consentEpoch) return
                            loadingInterstitial = false
                            interstitialLoadPolicy.loaded()
                            if (consent.canRequestAds()) { interstitial = ad; interstitialLoadedAt = now() }
                        }
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            if (epoch == consentEpoch) {
                                loadingInterstitial = false
                                interstitial = null
                                interstitialLoadPolicy.failed(loadClock())
                                schedulePreloadRetry()
                            }
                        }
                    })
            } catch (_: RuntimeException) {
                loadingInterstitial = false
                interstitialLoadPolicy.failed(loadClock())
            }
        }
        if (appOpen == null && !loadingAppOpen && appOpenLoadPolicy.canLoad(loadClock())) {
            loadingAppOpen = true
            try {
                AppOpenAd.load(app, if (BuildConfig.TEST_ADS) TEST_APP_OPEN else APP_OPEN,
                    AdRequest.Builder().build(), object : AppOpenAd.AppOpenAdLoadCallback() {
                        override fun onAdLoaded(ad: AppOpenAd) {
                            if (epoch != consentEpoch) return
                            loadingAppOpen = false
                            appOpenLoadPolicy.loaded()
                            if (consent.canRequestAds()) { appOpen = ad; appOpenLoadedAt = now() }
                        }
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            if (epoch == consentEpoch) {
                                loadingAppOpen = false
                                appOpen = null
                                appOpenLoadPolicy.failed(loadClock())
                                schedulePreloadRetry()
                            }
                        }
                    })
            } catch (_: RuntimeException) {
                loadingAppOpen = false
                appOpenLoadPolicy.failed(loadClock())
            }
        }
        schedulePreloadRetry()
    }

    /** A retry may only fill a cache; showing still requires a new natural break/resume. */
    private fun schedulePreloadRetry() {
        handler.removeCallbacks(preloadRetry)
        if (!initialized || !consent.canRequestAds() || privacyBusy || !resumed || showing ||
            MelKitShareService.state.value.active) return
        val delay = listOfNotNull(
            if (interstitial == null && !loadingInterstitial) interstitialLoadPolicy.retryDelay(loadClock()) else null,
            if (appOpen == null && !loadingAppOpen) appOpenLoadPolicy.retryDelay(loadClock()) else null
        ).minOrNull() ?: return
        handler.postDelayed(preloadRetry, delay.coerceAtLeast(1L))
    }

    private fun loadClock() = SystemClock.elapsedRealtime()

    private fun fresh(loadedAt: Long, lifetime: Long): Boolean =
        loadedAt > 0 && now() >= loadedAt && now() - loadedAt < lifetime

    private fun now() = System.currentTimeMillis()
    private fun persistLimits() {
        preferences.edit().putInt("normal_opens", policy.normalOpens)
            .putLong("last_closed", policy.lastClosed).putLong("last_app_open", policy.lastAppOpen)
            .putString("interstitials", policy.interstitials.joinToString(",")).apply()
    }

    companion object {
        private const val TAG = "MelKitAds"
        private const val INTERSTITIAL = "ca-app-pub-9118481973136364/5379133301"
        private const val APP_OPEN = "ca-app-pub-9118481973136364/5512216436"
        private const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
        private const val TEST_APP_OPEN = "ca-app-pub-3940256099942544/9257395921"
        @Volatile private var instance: AdManager? = null
        fun get(context: Context): AdManager = instance ?: synchronized(this) {
            instance ?: AdManager(context).also { instance = it }
        }
    }
}

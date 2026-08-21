package com.example.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.startapp.sdk.ads.banner.Banner
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
import com.startapp.sdk.adsbase.adlisteners.VideoListener

object AdManager {
    private const val TAG = "AdManager"
    const val STARTAPP_APP_ID = "207395789"

    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            StartAppSDK.init(context.applicationContext, STARTAPP_APP_ID, false)
            StartAppAd.disableSplash()
            // Enable return ads if desired or keep minimal
            StartAppSDK.enableReturnAds(false)
            isInitialized = true
            Log.d(TAG, "StartApp SDK initialized successfully with App ID: $STARTAPP_APP_ID")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing StartApp SDK: ${e.message}", e)
        }
    }

    /**
     * Shows an interstitial ad, e.g. required to lock in daily limits.
     * Guaranteed callback execution so UI is never stuck.
     */
    fun showInterstitialAd(activity: Activity, onAdDismissed: (Boolean) -> Unit) {
        try {
            val startAppAd = StartAppAd(activity)
            var hasCallbackFired = false

            fun fireCallbackOnce(success: Boolean) {
                if (!hasCallbackFired) {
                    hasCallbackFired = true
                    activity.runOnUiThread {
                        onAdDismissed(success)
                    }
                }
            }

            startAppAd.loadAd(StartAppAd.AdMode.AUTOMATIC, object : AdEventListener {
                override fun onReceiveAd(ad: Ad) {
                    try {
                        startAppAd.showAd(object : AdDisplayListener {
                            override fun adHidden(ad: Ad?) {
                                Log.d(TAG, "Interstitial ad hidden")
                                fireCallbackOnce(true)
                            }

                            override fun adDisplayed(ad: Ad?) {
                                Log.d(TAG, "Interstitial ad displayed")
                            }

                            override fun adClicked(ad: Ad?) {
                                Log.d(TAG, "Interstitial ad clicked")
                            }

                            override fun adNotDisplayed(ad: Ad?) {
                                Log.w(TAG, "Interstitial ad not displayed")
                                fireCallbackOnce(true)
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error displaying interstitial: ${e.message}", e)
                        fireCallbackOnce(true)
                    }
                }

                override fun onFailedToReceiveAd(ad: Ad?) {
                    Log.w(TAG, "Failed to receive interstitial ad: ${ad?.errorMessage}")
                    fireCallbackOnce(true)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception showing interstitial ad: ${e.message}", e)
            onAdDismissed(true)
        }
    }

    /**
     * Shows a rewarded / interstitial video ad to unlock exactly 10 extra scrolls.
     */
    fun showRewardedAdForExtraScrolls(
        activity: Activity,
        onRewardGranted: (Boolean) -> Unit
    ) {
        try {
            val startAppAd = StartAppAd(activity)
            var rewardEarned = false
            var hasCallbackFired = false

            fun fireCallbackOnce(granted: Boolean) {
                if (!hasCallbackFired) {
                    hasCallbackFired = true
                    activity.runOnUiThread {
                        onRewardGranted(granted)
                    }
                }
            }

            startAppAd.setVideoListener(object : VideoListener {
                override fun onVideoCompleted() {
                    Log.d(TAG, "Rewarded video completed - granting 10 extra scrolls!")
                    rewardEarned = true
                }
            })

            startAppAd.loadAd(StartAppAd.AdMode.REWARDED_VIDEO, object : AdEventListener {
                override fun onReceiveAd(ad: Ad) {
                    try {
                        startAppAd.showAd(object : AdDisplayListener {
                            override fun adHidden(ad: Ad?) {
                                Log.d(TAG, "Rewarded ad closed, earned: $rewardEarned")
                                fireCallbackOnce(true) // Grant reward on completing or viewing
                            }

                            override fun adDisplayed(ad: Ad?) {
                                Log.d(TAG, "Rewarded ad displayed")
                            }

                            override fun adClicked(ad: Ad?) {
                                Log.d(TAG, "Rewarded ad clicked")
                            }

                            override fun adNotDisplayed(ad: Ad?) {
                                Log.w(TAG, "Rewarded ad not displayed, granting fallback")
                                fireCallbackOnce(true)
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error displaying rewarded ad: ${e.message}", e)
                        fireCallbackOnce(true)
                    }
                }

                override fun onFailedToReceiveAd(ad: Ad?) {
                    Log.w(TAG, "Failed to load rewarded video, attempting standard ad fallback: ${ad?.errorMessage}")
                    // Fallback to standard interstitial
                    showInterstitialAd(activity) { success ->
                        fireCallbackOnce(success)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception in showRewardedAdForExtraScrolls: ${e.message}", e)
            onRewardGranted(true)
        }
    }
}

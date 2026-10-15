package com.kaze.winkaze

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density

        val image = ImageView(this).apply {
            setImageResource(R.drawable.presplash)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxWidth = (260 * density).toInt()
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                image,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        setContentView(root)
        image.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).start()

        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 3000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

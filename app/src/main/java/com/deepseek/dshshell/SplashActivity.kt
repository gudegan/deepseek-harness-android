package com.deepseek.dshshell

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.deepseek.dshshell.util.Prefs
import com.deepseek.dshshell.util.ThemeUtil

/**
 * 启动动画页：DeepSeek 品牌配色 + 鲸鱼图标（蓝）+ 扩散光环 + 文字淡入。
 * 动画结束后进入主界面。
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeUtil.themeResource(Prefs.accentKey))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val whale = findViewById<ImageView>(R.id.whale)
        val ring = findViewById<View>(R.id.ring)
        val iconWrap = findViewById<View>(R.id.icon_wrap)
        val title = findViewById<TextView>(R.id.title)
        val subtitle = findViewById<TextView>(R.id.subtitle)

        // 图标弹入（Overshoot 回弹）
        iconWrap.alpha = 0f
        iconWrap.scaleX = 0.6f
        iconWrap.scaleY = 0.6f
        iconWrap.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(650)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()

        // 扩散光环：放大 + 淡出，重复 3 次
        val pulse = ObjectAnimator.ofPropertyValuesHolder(
            ring,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.7f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.7f),
            PropertyValuesHolder.ofFloat("alpha", 1f, 0f),
        ).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            repeatCount = 2
            repeatMode = ObjectAnimator.RESTART
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(a: Animator) { ring.alpha = 1f }
                override fun onAnimationEnd(a: Animator) { ring.alpha = 0f }
                override fun onAnimationCancel(a: Animator) {}
                override fun onAnimationRepeat(a: Animator) { ring.alpha = 1f }
            })
        }
        pulse.start()

        // 标题 / 副标题依次淡入
        title.animate().alpha(1f).setStartDelay(350).setDuration(450).start()
        subtitle.animate().alpha(1f).setStartDelay(600).setDuration(450).start()

        // 动画结束进入主界面
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 1600)
    }
}

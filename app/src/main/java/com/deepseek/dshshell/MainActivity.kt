package com.deepseek.dshshell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.deepseek.dshshell.databinding.ActivityMainBinding
import com.deepseek.dshshell.service.HarnessService
import com.deepseek.dshshell.util.Changelog
import com.deepseek.dshshell.util.Prefs
import com.deepseek.dshshell.util.ThemeUtil

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * 预览 WebView 的归属者 = Activity。
     * 底部导航切走时会 pop 掉 PreviewFragment（onDestroy），若 WebView 归 Fragment 所有
     * 就会随之销毁、页面重载；提升到 Activity 后，WebView 对象跨 Fragment 生命周期存活。
     */
    private var previewWebView: WebView? = null

    /** 预览 WebView 已加载内容对应的 dsh 启动代号（跨 Fragment 存活）。
     *  用于在 dsh 重启后判断是否需要重载页面以匹配新进程（修复重启时预览画面重叠）。 */
    var previewLoadedEpoch = -1

    /** 刷新预览 WebView。预览页「刷新」快捷键与「移动端适配」开启时自动刷新共用。
     *  首次加载（url 为空）交由 PreviewFragment 处理，这里只 reload 已加载的页面，
     *  reload 触发的 onPageFinished 会重新注入移动适配 CSS。 */
    fun refreshPreview() {
        val wv = previewWebView() ?: return
        try {
            if (wv.url != null) wv.reload()
        } catch (e: Exception) {
            // 忽略：无页面可刷新时不报错
            android.util.Log.d("DshShell", "refreshPreview: ${e.message}")
        }
    }

    /** 惰性创建并返回预览 WebView（首次进入预览页时创建，Activity 销毁时回收）。 */
    fun previewWebView(): WebView? {
        if (previewWebView == null) {
            previewWebView = WebView(this).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
            }
        }
        return previewWebView
    }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也能跑，仅通知不显示 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用已保存的强调色主题（设置页切换后 recreate 生效）
        setTheme(ThemeUtil.themeResource(Prefs.accentKey))
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        binding.bottomNav.setupWithNavController(navHost.navController)

        // 切到「预览」tab 时显示常驻 WebView 容器（其它 tab 隐藏）。
        // 容器始终留在视图树中，仅切换可见性，WebView 不脱离窗口 → 页面不重载
        navHost.navController.addOnDestinationChangedListener { _, dest, _ ->
            val isPreview = dest.id == R.id.dest_preview
            binding.previewHolder.isVisible = isPreview
            val wv = previewWebView
            if (isPreview) wv?.onResume() else wv?.onPause()
        }

        requestNotificationPermission()

        // 拉起前台服务：按"自动启动"设置执行 解压 → 启动 dsh
        HarnessService.start(this, HarnessService.ACTION_BOOT)

        // 预览页快捷键（纯图标按钮，可拖动移动、位置持久化）：
        //   - 刷新：默认右下角，点击旋转动效并 reload 当前 dsh 页面
        //   - 返回：默认左下角，点击 WebView 历史返回（无历史则回上一 tab）
        setupDraggableButton(
            btn = binding.btnPreviewRefresh,
            savedLeft = { Prefs.refreshBtnLeft }, savedTop = { Prefs.refreshBtnTop },
            saveLeft = { Prefs.refreshBtnLeft = it }, saveTop = { Prefs.refreshBtnTop = it },
            defaultBottomStart = false,
            onClick = {
                try {
                    binding.btnPreviewRefresh.animate().rotationBy(360f).setDuration(500)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                } catch (_: Exception) {
                }
                refreshPreview()
            },
        )
        setupDraggableButton(
            btn = binding.btnPreviewBack,
            savedLeft = { Prefs.backBtnLeft }, savedTop = { Prefs.backBtnTop },
            saveLeft = { Prefs.backBtnLeft = it }, saveTop = { Prefs.backBtnTop = it },
            defaultBottomStart = true,
            onClick = { goPreviewBack() },
        )

        // 新版本安装后首次打开，弹出优化公告
        Changelog.maybeShowUpdateDialog(this)
    }

    override fun onDestroy() {
        // 预览 WebView 由 Activity 持有，随 Activity 一起释放 Chromium 原生内存
        previewWebView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.clearCache(true)
            wv.destroy()
        }
        previewWebView = null
        super.onDestroy()
    }

    /**
     * 预览页悬浮按钮：支持单指拖动移动（位置持久化）。
     *
     * - 启动时先尝试恢复上次保存的位置（savedLeft/savedTop 回调读取）；未保存过则在父容器
     *   布局完成后定位到默认角：defaultBottomStart=true 用左下角，false 用右下角。
     * - 触摸拖动实时更新按钮在父容器内的 leftMargin/topMargin（钳制在父范围内），
     *   松手时保存新位置（saveLeft/saveTop 回调）；位移超过触控阈值视为拖动（不触发点击），否则视为点击。
     * - 点击时调用 onClick。
     */
    private fun setupDraggableButton(
        btn: android.widget.ImageButton,
        savedLeft: () -> Int,
        savedTop: () -> Int,
        saveLeft: (Int) -> Unit,
        saveTop: (Int) -> Unit,
        defaultBottomStart: Boolean,
        onClick: () -> Unit,
    ) {
        val parent = binding.previewHolder
        val lp = (btn.layoutParams as? FrameLayout.LayoutParams)
            ?: (FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { btn.layoutParams = it })
        val dp: (Int) -> Int = { v ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
            ).toInt()
        }
        val edge = dp(12)
        val btnSize = dp(48)
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        fun placeTo(left: Int, top: Int) {
            lp.leftMargin = left.coerceIn(0, (parent.width - btnSize).coerceAtLeast(0))
            lp.topMargin = top.coerceIn(0, (parent.height - btnSize).coerceAtLeast(0))
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            btn.layoutParams = lp
        }

        // 等待父容器完成布局，才能拿到宽高定位默认角；已保存过则直接恢复。
        // 用 btn.post 在布局完成后的下一帧执行，避免在布局分发过程中直接改 layoutParams
        // （addOnGlobalLayoutListener 回调里改布局会触发 requestLayout，可能引发崩溃）。
        // XML 里按钮已按默认角设好初始位置，因此即便 post 时父容器宽高尚未就绪，
        // 按钮也保持 XML 默认角、不重叠；post 只负责按保存位置/默认角精确摆放。
        btn.post {
            val sl = savedLeft()
            val st = savedTop()
            if (parent.width > 0 && parent.height > 0) {
                if (sl >= 0 && st >= 0) {
                    placeTo(sl, st)
                } else {
                    // 默认右下角：右/下边缘留 edge 间距；默认左下角：左/下边缘留 edge 间距
                    val left = if (defaultBottomStart) edge else parent.width - btnSize - edge
                    placeTo(left, parent.height - btnSize - edge)
                }
            }
            // 父容器宽高为 0（未布局）时不做任何改动，保持 XML 默认角位置
        }

        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        var moved = false
        btn.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!moved && (Math.abs(ev.rawX - downX) > touchSlop || Math.abs(ev.rawY - downY) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        placeTo(startLeft + dx, startTop + dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // 拖动结束：保存位置
                        saveLeft(lp.leftMargin)
                        saveTop(lp.topMargin)
                    } else {
                        onClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 预览页返回：WebView 能回退则回退上一页，否则回上一个 tab（等同系统返回）。 */
    private fun goPreviewBack() {
        val wv = previewWebView()
        if (wv != null && wv.canGoBack()) {
            try {
                wv.goBack()
                return
            } catch (_: Exception) {
            }
        }
        onBackPressedDispatcher.onBackPressed()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

}


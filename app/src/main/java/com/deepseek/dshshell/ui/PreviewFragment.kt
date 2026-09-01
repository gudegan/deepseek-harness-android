package com.deepseek.dshshell.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.deepseek.dshshell.MainActivity
import com.deepseek.dshshell.R
import com.deepseek.dshshell.state.AppState
import com.deepseek.dshshell.state.DshState
import kotlinx.coroutines.launch

/**
 * dsh 预览页。
 *
 * WebView 由 MainActivity 持有（跨 Fragment 生命周期存活）：底部导航切走时会
 * pop 掉本 Fragment，但 WebView 对象仍在 Activity 中，且常驻于 Activity 的
 * preview_holder 容器（仅切换可见性、不脱离窗口），因此切回预览时页面不重载。
 */
class PreviewFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // 占位布局；WebView 由 Activity 的 preview_holder 托管
        return FrameLayout(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val holder = requireActivity().findViewById<FrameLayout>(R.id.webview_container)
        val placeholder = requireActivity().findViewById<View>(R.id.preview_placeholder)
        val wv = (requireActivity() as? MainActivity)?.previewWebView()

        // 首次挂载：把 Activity 持有的 WebView 放进预览容器（之后切 tab 不摘除）
        if (wv != null && wv.parent == null) {
            holder.addView(
                wv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppState.ui.collect { state ->
                    val running = state.dshState == DshState.RUNNING
                    placeholder.isVisible = !running
                    if (running) {
                        val activity = requireActivity() as? MainActivity
                        if (wv != null && activity != null) {
                            if (wv.url == null) {
                                // 首次加载：加载预览页
                                wv.loadUrl(getString(R.string.preview_url))
                                activity.previewLoadedEpoch = state.dshEpoch
                            } else if (activity.previewLoadedEpoch != state.dshEpoch) {
                                // dsh 已重启：重载页面以匹配新进程，避免旧 DOM 与新进程会话错乱/画面重叠
                                wv.reload()
                                activity.previewLoadedEpoch = state.dshEpoch
                            }
                        }
                    }
                }
            }
        }
    }
}

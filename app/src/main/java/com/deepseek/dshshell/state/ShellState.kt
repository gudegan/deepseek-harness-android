package com.deepseek.dshshell.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 沙盒（rootfs）状态 */
enum class SandboxState { UNEXTRACTED, EXTRACTING, READY }

/** dsh 进程状态 */
enum class DshState { STOPPED, STARTING, RUNNING, ERROR }

/** 外壳全局 UI 状态，由 HarnessService 维护、Fragment 收集 */
data class ShellUiState(
    val sandboxState: SandboxState = SandboxState.UNEXTRACTED,
    val sandboxVersion: String? = null,
    val dshState: DshState = DshState.STOPPED,
    /** 解压进度 0f..1f */
    val extractProgress: Float = 0f,
    val lastError: String? = null,
    /** 正在一键更新 dsh（停 dsh → npm 更新 → 重启） */
    val updating: Boolean = false,
    /** 一键更新结果/提示（成功或失败说明） */
    val updateMessage: String? = null,
    /** dsh 启动代号：每次成功启动/重启递增，供预览 WebView 判断重启后是否需要重载页面以匹配新进程 */
    val dshEpoch: Int = 0,
)

object AppState {
    private val _ui = MutableStateFlow(ShellUiState())
    val ui: StateFlow<ShellUiState> = _ui.asStateFlow()

    fun update(transform: (ShellUiState) -> ShellUiState) {
        _ui.value = transform(_ui.value)
    }
}

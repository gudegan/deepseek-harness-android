package com.deepseek.dshshell.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.deepseek.dshshell.R
import com.deepseek.dshshell.databinding.FragmentDashboardBinding
import com.deepseek.dshshell.service.HarnessService
import com.deepseek.dshshell.state.AppState
import com.deepseek.dshshell.state.DshState
import com.deepseek.dshshell.state.SandboxState
import com.deepseek.dshshell.state.ShellUiState
import kotlinx.coroutines.launch

/** 控制台主页：沙盒/dsh 双状态卡 + 启停/重启开关 + 日志入口 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnExtract.setOnClickListener { HarnessService.start(requireContext(), HarnessService.ACTION_EXTRACT) }
        binding.btnStart.setOnClickListener { HarnessService.start(requireContext(), HarnessService.ACTION_START_DSH) }
        binding.btnRestart.setOnClickListener { HarnessService.start(requireContext(), HarnessService.ACTION_RESTART_DSH) }
        binding.btnStop.setOnClickListener { HarnessService.start(requireContext(), HarnessService.ACTION_STOP_DSH) }
        binding.btnClose.setOnClickListener { HarnessService.start(requireContext(), HarnessService.ACTION_SHUTDOWN) }
        binding.btnLog.setOnClickListener { showLogDialog(requireContext()) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppState.ui.collect { render(it) }
            }
        }
    }

    private fun render(state: ShellUiState) {
        // 沙盒状态卡
        binding.tvSandboxStatus.text = when (state.sandboxState) {
            SandboxState.UNEXTRACTED -> getString(R.string.sandbox_unextracted)
            SandboxState.EXTRACTING -> getString(R.string.sandbox_extracting)
            SandboxState.READY -> getString(R.string.sandbox_ready)
        }
        binding.tvSandboxVersion.text = state.sandboxVersion?.let { getString(R.string.dash_version_fmt, it) } ?: ""
        binding.progressExtract.isVisible = state.sandboxState == SandboxState.EXTRACTING
        binding.progressExtract.progress = (state.extractProgress * 10000).toInt()

        // dsh 状态卡
        binding.tvDshStatus.text = when (state.dshState) {
            DshState.STOPPED -> getString(R.string.dsh_stopped)
            DshState.STARTING -> getString(R.string.dsh_starting)
            DshState.RUNNING -> getString(R.string.dsh_running)
            DshState.ERROR -> getString(R.string.dsh_error)
        }
        binding.tvError.isVisible = state.lastError != null
        binding.tvError.text = state.lastError

        // 开关可用性（状态机驱动）
        val sandboxReady = state.sandboxState == SandboxState.READY
        binding.btnExtract.isEnabled = state.sandboxState != SandboxState.EXTRACTING
        binding.btnStart.isEnabled = sandboxReady && state.dshState in setOf(DshState.STOPPED, DshState.ERROR)
        binding.btnRestart.isEnabled = sandboxReady && state.dshState in setOf(DshState.RUNNING, DshState.ERROR)
        binding.btnStop.isEnabled = state.dshState in setOf(DshState.STARTING, DshState.RUNNING)
        // 关闭沙盒始终可用（停止 dsh + 服务，沙盒保留）
        binding.btnClose.isEnabled = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.deepseek.dshshell.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.deepseek.dshshell.BuildConfig
import com.deepseek.dshshell.R
import com.deepseek.dshshell.databinding.FragmentSettingsBinding
import com.deepseek.dshshell.runtime.RuntimeManager
import com.deepseek.dshshell.service.HarnessService
import com.deepseek.dshshell.state.AppState
import com.deepseek.dshshell.state.DshState
import com.deepseek.dshshell.state.SandboxState
import com.deepseek.dshshell.state.ShellUiState
import com.deepseek.dshshell.util.Changelog
import com.deepseek.dshshell.util.Logs
import com.deepseek.dshshell.util.Prefs
import com.deepseek.dshshell.util.ThemeUtil
import com.deepseek.dshshell.util.UpdateChecker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** 设置页：运行状态/占用 / 版本 / 主题 / 更新检测 / API Key / 挂载 / 模式 / 自启 / 保活 / 日志 / 数据管理 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var runtime: RuntimeManager
    private var ignoreThemeCallback = false

    private val logExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { exportLog(it) }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        runtime = RuntimeManager(requireContext())

        // 版本信息
        binding.tvVersion.text = getString(
            R.string.settings_version_fmt,
            BuildConfig.VERSION_NAME,
            runtime.builtinVersion ?: getString(R.string.settings_version_na),
        )

        // 状态（沙盒/dsh/版本）随全局状态刷新
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppState.ui.collect { renderStatus(it) }
            }
        }

        // 占用空间（后台线程算，避免卡主线程）
        binding.tvStorage.text = getString(R.string.settings_storage_na)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val bytes = runtime.storageUsageBytes()
            val text = formatBytes(bytes)
            withContext(Dispatchers.Main) { binding.tvStorage.text = text }
        }

        // dsh 版本（内置 runtime 里解析）
        binding.tvDshVersion.text = UpdateChecker.embeddedDshVersion(runtime.builtinVersion)
            ?: runtime.builtinVersion ?: getString(R.string.settings_version_na)

        // 更新检测
        binding.tvUpdateCurrent.text = getString(
            R.string.settings_update_current,
            UpdateChecker.embeddedDshVersion(runtime.builtinVersion) ?: getString(R.string.settings_version_na),
        )
        binding.btnCheckUpdate.setOnClickListener { checkUpdate() }

        // 主题模式
        setupThemeControls()

        // API Key 状态
        refreshApiKeyStatus()

        // 自启
        binding.swAutoStart.isChecked = Prefs.autoStart
        binding.swAutoStart.setOnCheckedChangeListener { _, v -> Prefs.autoStart = v }

        // 挂载 /sdcard
        binding.swMountSdcard.isChecked = Prefs.mountSdcard
        binding.swMountSdcard.setOnCheckedChangeListener { _, v -> Prefs.mountSdcard = v }


        // 运行模式
        binding.rgMode.check(if (Prefs.runMode == "minimal") R.id.radio_minimal else R.id.radio_standard)
        binding.rgMode.setOnCheckedChangeListener { _, id ->
            Prefs.runMode = if (id == R.id.radio_minimal) "minimal" else "standard"
        }

        binding.btnChangelog.setOnClickListener { Changelog.showFullChangelog(requireContext()) }
        binding.btnBattery.setOnClickListener { requestBatteryWhitelist() }
        binding.btnLog.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_log)
                .setItems(arrayOf(getString(R.string.settings_log_view), getString(R.string.settings_log_export))) { _, which ->
                    when (which) {
                        0 -> showLogDialog(requireContext())
                        1 -> logExportLauncher.launch("dsh.log")
                    }
                }
                .show()
        }
        binding.btnClear.setOnClickListener { confirmClear() }
        binding.btnResetDsh.setOnClickListener { confirmResetDsh() }
    }

    private fun renderStatus(state: ShellUiState) {
        binding.tvSandboxStatus.text = when (state.sandboxState) {
            SandboxState.UNEXTRACTED -> getString(R.string.sandbox_unextracted)
            SandboxState.EXTRACTING -> getString(R.string.sandbox_extracting)
            SandboxState.READY -> getString(R.string.sandbox_ready)
        }
        binding.tvDshStatus.text = when (state.dshState) {
            DshState.STOPPED -> getString(R.string.dsh_stopped)
            DshState.STARTING -> getString(R.string.dsh_starting)
            DshState.RUNNING -> getString(R.string.dsh_running)
            DshState.ERROR -> getString(R.string.dsh_error)
        }
        // 一键更新：进行中 / 结果
        binding.btnCheckUpdate.isEnabled = !state.updating
        binding.btnCheckUpdate.text = if (state.updating) {
            getString(R.string.settings_update_updating)
        } else {
            getString(R.string.settings_update_check)
        }
        state.updateMessage?.let { binding.tvUpdateResult.text = it }
        // 更新后刷新 dsh 版本显示（用户已一键更新则优先展示新版本）
        runtime.currentDshVersion()?.let { v ->
            binding.tvDshVersion.text = v
            binding.tvUpdateCurrent.text = getString(R.string.settings_update_current, v)
        }
    }

    private fun setupThemeControls() {
        // 主题模式
        val mode = Prefs.themeMode
        binding.rgTheme.check(
            when (mode) {
                "light" -> R.id.radio_theme_light
                "dark" -> R.id.radio_theme_dark
                else -> R.id.radio_theme_system
            }
        )
        binding.rgTheme.setOnCheckedChangeListener { _, id ->
            if (ignoreThemeCallback) return@setOnCheckedChangeListener
            val m = when (id) {
                R.id.radio_theme_light -> "light"
                R.id.radio_theme_dark -> "dark"
                else -> "system"
            }
            Prefs.themeMode = m
            ThemeUtil.applyNightMode(m)
            requireActivity().recreate()
        }

        // 强调色
        val accent = Prefs.accentKey
        binding.rgAccent.check(
            when (accent) {
                "ocean" -> R.id.accent_ocean
                "violet" -> R.id.accent_violet
                "deep" -> R.id.accent_deep
                else -> R.id.accent_brand
            }
        )
        binding.rgAccent.setOnCheckedChangeListener { _, id ->
            if (ignoreThemeCallback) return@setOnCheckedChangeListener
            val key = when (id) {
                R.id.accent_ocean -> "ocean"
                R.id.accent_violet -> "violet"
                R.id.accent_deep -> "deep"
                else -> "brand"
            }
            Prefs.accentKey = key
            requireActivity().recreate()
        }
    }

    /** 远程检测更新；网络不可达/超时统一给出友好提示 */
    private fun checkUpdate() {
        if (AppState.ui.value.updating) return // 更新进行中，忽略重复点击
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = getString(R.string.settings_update_checking)
        val embedded = UpdateChecker.embeddedDshVersion(runtime.builtinVersion)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = UpdateChecker.checkLatest()
            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = getString(R.string.settings_update_check)
            if (result.error != null) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_update)
                    .setMessage(getString(R.string.settings_update_error, result.error))
                    .setPositiveButton(R.string.log_close, null)
                    .show()
                return@launch
            }
            val latest = result.latest
            if (UpdateChecker.isNewer(latest, embedded)) {
                showUpdateFoundDialog(latest!!, embedded)
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_update)
                    .setMessage(
                        getString(R.string.settings_update_latest, latest ?: "") +
                            "\n" + getString(R.string.settings_update_source, result.usedRegistry ?: "")
                    )
                    .setPositiveButton(R.string.log_close, null)
                    .show()
            }
        }
    }

    private fun showUpdateFoundDialog(latest: String, embedded: String?) {
        val msg = getString(R.string.settings_update_found, latest, embedded ?: "?")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_update)
            .setMessage(msg)
            .setPositiveButton(R.string.settings_update_now) { _, _ ->
                // 一键更新：停 dsh → 沙盒内 npm 更新（多源回退）→ 重启 dsh
                binding.tvUpdateResult.text = getString(R.string.settings_update_started)
                HarnessService.start(requireContext(), HarnessService.ACTION_UPDATE_DSH)
            }
            .setNeutralButton(R.string.settings_update_open_npm) { _, _ ->
                openUrl("https://www.npmjs.com/package/@deepseek-ai/dsh")
            }
            .setNegativeButton(R.string.log_close, null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(requireContext(), e.message ?: url)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun refreshApiKeyStatus() {
        val cred = runtime.sandboxPathToFile("/root/.dsh/.credentials.yaml")
        binding.tvApiKey.text = if (cred.exists()) {
            getString(R.string.settings_api_key_configured)
        } else {
            getString(R.string.settings_api_key_hint)
        }
    }

    private fun requestBatteryWhitelist() {
        val pm = requireContext().getSystemService(android.os.PowerManager::class.java)
        val pkg = requireContext().packageName
        if (pm.isIgnoringBatteryOptimizations(pkg)) {
            toast(requireContext(), getString(R.string.settings_battery_done))
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$pkg"),
                )
            )
        } catch (_: Exception) {
            toast(requireContext(), getString(R.string.settings_battery_manual))
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.settings_clear_confirm)
            .setPositiveButton(R.string.settings_clear) { _, _ ->
                runtime.clearSandbox()
                toast(requireContext(), getString(R.string.settings_clear_done))
            }
            .setNegativeButton(R.string.log_close, null)
            .show()
    }

    /** DSH 恢复默认设置：停 dsh → 删用户配置层（cordis.patch.yml），保留插件/API Key/会话/产出文件 */
    private fun confirmResetDsh() {
        val running = AppState.ui.value.dshState in setOf(DshState.RUNNING, DshState.STARTING)
        val msg = getString(R.string.settings_reset_dsh_confirm) +
            if (running) "\n\n" + getString(R.string.settings_reset_dsh_running) else ""
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_reset_dsh)
            .setMessage(msg)
            .setPositiveButton(R.string.settings_reset_action) { _, _ ->
                // 先在 dsh 运行/启动中时停止它，避免删除被 watch 的配置文件时触发重载/报错
                if (running) HarnessService.start(requireContext(), HarnessService.ACTION_STOP_DSH)
                val (ok, message) = runtime.resetDshToDefault()
                if (ok) {
                    Logs.file(requireContext(), "DSH 恢复默认设置完成：$message")
                    toast(requireContext(), getString(R.string.settings_reset_dsh_done))
                } else {
                    toast(requireContext(), getString(R.string.settings_reset_dsh_fail, message))
                }
            }
            .setNegativeButton(R.string.log_close, null)
            .show()
    }

    private fun exportLog(uri: Uri) {
        val f = File(requireContext().filesDir, "logs/dsh.log")
        if (!f.exists()) {
            toast(requireContext(), getString(R.string.log_empty))
            return
        }
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                f.inputStream().use { ins -> ins.copyTo(out) }
            }
            toast(requireContext(), getString(R.string.settings_log_exported))
        } catch (e: Exception) {
            toast(requireContext(), getString(R.string.files_export_fail, e.message ?: ""))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.deepseek.dshshell.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.deepseek.dshshell.R
import com.deepseek.dshshell.databinding.FragmentFilesBinding
import com.deepseek.dshshell.runtime.RuntimeManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * 沙盒文件浏览 + 导入/导出/删除（SAF，无需存储权限）。
 * - 单击文件：预览 / 导出 / 删除；
 * - 长按进入多选：批量导出（选目录）、批量删除；
 * - 导入支持一次多选。
 */
class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!

    private var currentPath = "/root"
    private lateinit var runtime: RuntimeManager
    private lateinit var adapter: FileEntryAdapter
    private var pendingExport: File? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { importInto(it) }
            if (uris.isNotEmpty()) setPath(currentPath)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val src = pendingExport
            pendingExport = null
            if (uri != null && src != null) exportTo(uri, src)
        }

    private val multiExportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) batchExportTo(uri)
        }

    /** 隐藏的系统目录，避免误操作 */
    private val hiddenDirs = setOf("usr", "etc", "proc", "sys", "dev", "lib", "lib64", "bin", "sbin", "var", "boot", "opt", "srv", "media", "mnt")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = RuntimeManager(requireContext())
        adapter = FileEntryAdapter(::onOpen, ::onLongPress)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.btnUp.setOnClickListener { navigateUp() }
        binding.btnImport.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        binding.bookmarkRoot.setOnClickListener { setPath("/root") }
        binding.bookmarkHome.setOnClickListener { setPath("/home") }
        binding.bookmarkTmp.setOnClickListener { setPath("/tmp") }

        binding.btnExportSelected.setOnClickListener {
            if (adapter.selectedCount() > 0) multiExportLauncher.launch(null)
        }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        binding.btnCancelSelection.setOnClickListener { exitSelection() }

        // 视图重建后复位多选态（adapter 随 fragment 存活）
        adapter.clearSelection()
        binding.selectionBar.isVisible = false

        setPath(currentPath)
    }

    private fun onOpen(f: File) {
        if (f.isDirectory) {
            setPath(sandboxPath(f))
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(f.name)
                .setItems(
                    arrayOf(
                        getString(R.string.files_open_preview),
                        getString(R.string.files_export),
                        getString(R.string.files_delete),
                    )
                ) { _, which ->
                    when (which) {
                        0 -> previewFile(f)
                        1 -> {
                            pendingExport = f
                            exportLauncher.launch(f.name)
                        }
                        2 -> confirmDelete(listOf(f))
                    }
                }
                .show()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onLongPress(f: File) {
        adapter.setSelectionMode(true)
        binding.selectionBar.isVisible = true
        updateSelectionCount()
    }

    private fun updateSelectionCount() {
        binding.tvSelectionCount.text = getString(R.string.files_selected_count, adapter.selectedCount())
    }

    private fun exitSelection() {
        adapter.clearSelection()
        binding.selectionBar.isVisible = false
    }

    private fun setPath(sandboxPath: String) {
        val dir = runtime.sandboxPathToFile(sandboxPath)
        if (!dir.isDirectory) {
            toast(requireContext(), getString(R.string.files_dir_missing))
            return
        }
        currentPath = sandboxPath
        binding.tvPath.text = sandboxPath
        binding.btnUp.isEnabled = sandboxPath != "/"

        val entries = dir.listFiles()
            ?.filter { !it.name.startsWith(".") && it.name !in hiddenDirs }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
        adapter.submitList(entries)
    }

    private fun navigateUp() {
        if (currentPath == "/") return
        val parent = currentPath.substringBeforeLast('/').ifEmpty { "/" }
        setPath(parent)
    }

    /** 宿主 File → 沙盒内路径 */
    private fun sandboxPath(f: File): String {
        val root = runtime.runtimeDir.absolutePath
        val abs = f.absolutePath
        return if (abs == root) "/" else "/" + abs.removePrefix(root).trimStart('/')
    }

    private fun previewFile(f: File) {
        if (f.length() > 256 * 1024) {
            toast(requireContext(), getString(R.string.files_preview_too_large))
            return
        }
        val text = try {
            f.readText().take(20000)
        } catch (_: Exception) {
            null
        }
        if (text == null) {
            toast(requireContext(), getString(R.string.files_preview_binary))
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(f.name)
            .setMessage(text)
            .setPositiveButton(R.string.log_close, null)
            .show()
    }

    /** 导入：SAF 选本机文件 → 复制到当前目录（支持一次多选） */
    private fun importInto(uri: Uri) {
        val dir = runtime.sandboxPathToFile(currentPath)
        if (!dir.isDirectory) return
        val name = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}"
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { ins ->
                File(dir, name).outputStream().use { out -> ins.copyTo(out) }
            }
            toast(requireContext(), getString(R.string.files_import_ok, name))
        } catch (e: Exception) {
            toast(requireContext(), getString(R.string.files_import_fail, e.message ?: ""))
        }
    }

    /** 导出：复制沙盒文件到 SAF 所选目标 */
    private fun exportTo(uri: Uri, src: File) {
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { ins -> ins.copyTo(out) }
            }
            toast(requireContext(), getString(R.string.files_export_ok, src.name))
        } catch (e: Exception) {
            toast(requireContext(), getString(R.string.files_export_fail, e.message ?: ""))
        }
    }

    /** 批量导出：把勾选的文件写入 SAF 选中的目录（目录项自动跳过） */
    private fun batchExportTo(treeUri: Uri) {
        val files = adapter.selectedFiles().filter { it.isFile }
        val skippedDirs = adapter.selectedFiles().count { it.isDirectory }
        if (files.isEmpty()) {
            toast(requireContext(), getString(R.string.files_export_none))
            exitSelection()
            return
        }
        val tree = DocumentFile.fromTreeUri(requireContext(), treeUri)
        if (tree == null) {
            toast(requireContext(), getString(R.string.files_export_fail_dir))
            return
        }
        val used = HashSet<String>()
        var ok = 0
        for (f in files) {
            val name = uniqueName(f.name, used)
            val target = tree.createFile("application/octet-stream", name) ?: continue
            try {
                requireContext().contentResolver.openOutputStream(target.uri)?.use { out ->
                    f.inputStream().use { ins -> ins.copyTo(out) }
                }
                ok++
            } catch (_: Exception) {
                // 单个失败不中断整体
            }
        }
        toast(
            requireContext(),
            getString(
                R.string.files_export_ok_batch,
                ok,
                files.size,
                if (skippedDirs > 0) getString(R.string.files_export_skip_dir, skippedDirs) else "",
            )
        )
        exitSelection()
    }

    /** 删除确认（单个/批量共用） */
    private fun confirmDelete(files: List<File>) {
        if (files.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.files_delete_title)
            .setMessage(getString(R.string.files_delete_confirm, files.size))
            .setPositiveButton(R.string.files_delete) { _, _ ->
                var ok = 0
                for (f in files) {
                    try {
                        if (f.deleteRecursively()) ok++
                    } catch (_: Exception) {
                        // 单个失败继续
                    }
                }
                toast(requireContext(), getString(R.string.files_delete_ok, ok))
                exitSelection()
                setPath(currentPath)
            }
            .setNegativeButton(R.string.log_close, null)
            .show()
    }

    private fun confirmDeleteSelected() {
        val files = adapter.selectedFiles()
        if (files.isEmpty()) return
        confirmDelete(files)
    }

    /** 生成目标目录内不冲突的文件名 */
    private fun uniqueName(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var i = 1
        while (true) {
            val cand = "$stem($i)$ext"
            if (used.add(cand)) return cand
            i++
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return requireContext().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.deepseek.dshshell.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.deepseek.dshshell.R
import com.deepseek.dshshell.databinding.ItemFileBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 沙盒文件列表项（目录/文件）。
 * - 普通模式：单击 onOpen（预览/导出/删除）；
 * - 长按进入多选模式（onLongPress），单击切换勾选，可批量导出/删除。
 */
class FileEntryAdapter(
    private val onOpen: (File) -> Unit,
    private val onLongPress: (File) -> Unit,
) : ListAdapter<File, FileEntryAdapter.VH>(DIFF) {

    private var selectionMode = false
    private val selected = LinkedHashSet<String>() // absolutePath

    fun isSelectionMode(): Boolean = selectionMode

    /** 进入/退出多选模式；退出时清空已选项 */
    fun setSelectionMode(mode: Boolean) {
        if (selectionMode == mode) return
        selectionMode = mode
        if (!mode) selected.clear()
        notifyDataSetChanged()
    }

    fun toggleSelected(f: File) {
        val path = f.absolutePath
        if (!selected.remove(path)) selected.add(path)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        setSelectionMode(false)
    }

    fun selectedCount(): Int = selected.size

    fun selectedFiles(): List<File> = currentList.filter { it.absolutePath in selected }

    class VH(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = getItem(position)
        val b = holder.binding
        b.ivIcon.setImageResource(if (f.isDirectory) R.drawable.ic_folder else R.drawable.ic_file)
        b.tvName.text = f.name
        b.tvName.setTypeface(null, if (f.isDirectory) Typeface.BOLD else Typeface.NORMAL)
        b.tvMeta.text = if (f.isDirectory) {
            holder.itemView.context.getString(R.string.files_dir_meta, entryCount(f))
        } else {
            holder.itemView.context.getString(R.string.files_file_meta, formatSize(f.length()), formatTime(f.lastModified()))
        }

        // 多选模式：显示勾选框并反映选中态
        b.cbSelect.isVisible = selectionMode
        b.cbSelect.isChecked = f.absolutePath in selected

        holder.itemView.setOnClickListener {
            if (selectionMode) toggleSelected(f) else onOpen(f)
        }
        holder.itemView.setOnLongClickListener {
            if (!selectionMode) {
                setSelectionMode(true)
                onLongPress(f)
                toggleSelected(f)
            } else {
                toggleSelected(f)
            }
            true
        }
    }

    private fun entryCount(dir: File): Int {
        val n = dir.list()?.size ?: 0
        return if (n > 999) 999 else n
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<File>() {
            override fun areItemsTheSame(a: File, b: File) = a.absolutePath == b.absolutePath
            override fun areContentsTheSame(a: File, b: File) =
                a.name == b.name && a.length() == b.length() && a.lastModified() == b.lastModified()
        }

        private val TIME = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
            return String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0)
        }

        fun formatTime(ts: Long): String = TIME.format(Date(ts))
    }
}

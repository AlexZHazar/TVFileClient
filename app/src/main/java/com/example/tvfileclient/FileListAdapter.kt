package com.example.tvfileclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.*

class FileListAdapter(
    private val onItemClick: (MainActivity.RemoteFile) -> Unit
) : ListAdapter<MainActivity.RemoteFile, FileListAdapter.FileViewHolder>(FileDiffCallback()) {

    class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileName: TextView = itemView.findViewById(R.id.fileName)
        private val fileSize: TextView = itemView.findViewById(R.id.fileSize)
        private val fileIcon: TextView = itemView.findViewById(R.id.fileIcon)

        fun bind(file: MainActivity.RemoteFile, onClick: (MainActivity.RemoteFile) -> Unit) {
            fileName.text = file.name
            fileSize.text = formatFileSize(file.size)
            fileIcon.text = getFileIcon(file.name)

            itemView.setOnClickListener { onClick(file) }
        }

        private fun formatFileSize(size: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB")
            var fileSize = size.toDouble()
            var unitIndex = 0

            while (fileSize > 1024 && unitIndex < units.size - 1) {
                fileSize /= 1024
                unitIndex++
            }

            return "%.2f %s".format(Locale.US, fileSize, units[unitIndex])
        }

        private fun getFileIcon(fileName: String): String {
            return when {
                fileName.endsWith(".mp4") || fileName.endsWith(".avi") || fileName.endsWith(".mkv") -> "🎬"
                fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".png") -> "🖼️"
                fileName.endsWith(".mp3") || fileName.endsWith(".wav") || fileName.endsWith(".flac") -> "🎵"
                fileName.endsWith(".txt") || fileName.endsWith(".log") || fileName.endsWith(".json") -> "📄"
                fileName.endsWith(".pdf") -> "📕"
                fileName.endsWith(".apk") -> "📱"
                fileName.endsWith(".zip") || fileName.endsWith(".rar") || fileName.endsWith(".7z") -> "🗜️"
                fileName.endsWith(".doc") || fileName.endsWith(".docx") -> "📘"
                fileName.endsWith(".xls") || fileName.endsWith(".xlsx") -> "📊"
                fileName.endsWith(".ppt") || fileName.endsWith(".pptx") -> "📽️"
                else -> "📁"
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position), onItemClick)
    }

    class FileDiffCallback : DiffUtil.ItemCallback<MainActivity.RemoteFile>() {
        override fun areItemsTheSame(oldItem: MainActivity.RemoteFile, newItem: MainActivity.RemoteFile): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: MainActivity.RemoteFile, newItem: MainActivity.RemoteFile): Boolean {
            return oldItem == newItem
        }
    }
}
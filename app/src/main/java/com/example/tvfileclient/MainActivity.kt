package com.example.tvfileclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.tvfileclient.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var client: OkHttpClient
    private val mainScope = MainScope()

    private var currentServerUrl: String? = null
    private var fileList = mutableListOf<RemoteFile>()
    private lateinit var fileAdapter: FileListAdapter

    private val PICK_FILE_REQUEST = 100
    private val PERMISSION_REQUEST_CODE = 101

    data class RemoteFile(
        val name: String,
        val size: Long,
        val isDirectory: Boolean = false,
        val modifiedDate: Date? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupOkHttpClient()
        setupViews()
        checkPermissions()

        // Загружаем сохраненный URL
        loadSavedServerUrl()
    }

    private fun setupOkHttpClient() {
        val logging = HttpLoggingInterceptor()
        logging.level = HttpLoggingInterceptor.Level.BODY

        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    private fun setupViews() {
        // Настройка RecyclerView
        fileAdapter = FileListAdapter { file ->
            if (file.isDirectory) {
                Toast.makeText(this, "Папки пока не поддерживаются", Toast.LENGTH_SHORT).show()
            } else {
                showFileOptionsDialog(file)
            }
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = fileAdapter
        }

        // Кнопка подключения
        binding.connectButton.setOnClickListener {
            val url = binding.serverUrlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                connectToServer(url)
            } else {
                binding.serverUrlInput.error = "Введите URL сервера"
            }
        }

        // Кнопка сканирования QR-кода
        binding.scanQrButton.setOnClickListener {
            scanQRCode()
        }

        // Кнопка выбора файла
        binding.selectFileButton.setOnClickListener {
            selectFile()
        }

        // Кнопка обновления
        binding.refreshLayout.setOnRefreshListener {
            currentServerUrl?.let { loadFileList(it) }
        }

        // Кнопка настроек
        binding.settingsButton.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun scanQRCode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                PERMISSION_REQUEST_CODE
            )
            return
        }

        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Сканируйте QR-код с приставки")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(false)
        integrator.setBarcodeImageEnabled(false)
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Результат сканирования QR-кода
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                val qrContent = result.contents
                binding.serverUrlInput.setText(qrContent)
                connectToServer(qrContent)
            }
        }

        // Результат выбора файла
        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                uploadFile(uri)
            }
        }
    }

    private fun connectToServer(url: String) {
        showLoading(true)
        currentServerUrl = url

        mainScope.launch {
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        mainScope.launch {
                            showLoading(false)
                            showError("Ошибка подключения: ${e.message}")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        mainScope.launch {
                            showLoading(false)
                            if (response.isSuccessful) {
                                saveServerUrl(url)
                                showSuccess("Подключено к серверу")
                                loadFileList(url)
                            } else {
                                showError("Ошибка сервера: ${response.code}")
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                showLoading(false)
                showError("Ошибка: ${e.message}")
            }
        }
    }

    private fun loadFileList(serverUrl: String) {
        showLoading(true)

        mainScope.launch {
            try {
                val request = Request.Builder()
                    .url(serverUrl)
                    .get()
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        mainScope.launch {
                            showLoading(false)
                            binding.refreshLayout.isRefreshing = false
                            showError("Ошибка загрузки списка файлов")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        mainScope.launch {
                            showLoading(false)
                            binding.refreshLayout.isRefreshing = false

                            if (response.isSuccessful) {
                                val html = response.body?.string() ?: ""
                                parseFileListFromHtml(html)
                            } else {
                                showError("Ошибка загрузки: ${response.code}")
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                showLoading(false)
                binding.refreshLayout.isRefreshing = false
                showError("Ошибка: ${e.message}")
            }
        }
    }

    private fun parseFileListFromHtml(html: String) {
        fileList.clear()

        // Простой парсинг HTML для извлечения информации о файлах
        val filePattern = "<tr>.*?<td>(.*?)</td>.*?<td>(.*?)</td>.*?</tr>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val matches = filePattern.findAll(html)

        matches.forEach { match ->
            val groups = match.groupValues
            if (groups.size >= 3) {
                val fileName = groups[1].trim()
                val fileSize = parseFileSize(groups[2].trim())

                // Пропускаем заголовки таблицы
                if (fileName != "Имя файла" && fileName.isNotEmpty()) {
                    fileList.add(RemoteFile(fileName, fileSize))
                }
            }
        }

        fileAdapter.submitList(fileList)

        if (fileList.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    private fun parseFileSize(sizeStr: String): Long {
        return try {
            val parts = sizeStr.split(" ")
            if (parts.size == 2) {
                val value = parts[0].toDouble()
                val unit = parts[1].uppercase(Locale.getDefault())

                when (unit) {
                    "KB" -> (value * 1024).toLong()
                    "MB" -> (value * 1024 * 1024).toLong()
                    "GB" -> (value * 1024 * 1024 * 1024).toLong()
                    else -> value.toLong()
                }
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun selectFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, PICK_FILE_REQUEST)
    }

    private fun uploadFile(uri: Uri) {
        val serverUrl = currentServerUrl ?: run {
            showError("Сначала подключитесь к серверу")
            return
        }

        showLoading(true)

        mainScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val fileName = getFileName(uri)
                val tempFile = File(cacheDir, "upload_${System.currentTimeMillis()}")

                inputStream?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("postData", fileName,
                        tempFile.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .addFormDataPart("filename", fileName)
                    .build()

                val request = Request.Builder()
                    .url(serverUrl)
                    .post(requestBody)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        mainScope.launch {
                            showLoading(false)
                            tempFile.delete()
                            showError("Ошибка загрузки: ${e.message}")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        mainScope.launch {
                            showLoading(false)
                            tempFile.delete()

                            if (response.isSuccessful) {
                                showSuccess("Файл успешно загружен")
                                loadFileList(serverUrl)
                            } else {
                                showError("Ошибка загрузки: ${response.code}")
                            }
                        }
                    }
                })

            } catch (e: Exception) {
                showLoading(false)
                showError("Ошибка: ${e.message}")
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var fileName = "file_${System.currentTimeMillis()}"

        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && it.moveToFirst()) {
                    fileName = it.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return fileName
    }

    private fun showFileOptionsDialog(file: RemoteFile) {
        val items = arrayOf("📥 Скачать", "🗑️ Удалить")

        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> downloadFile(file)
                    1 -> deleteFile(file)
                }
            }
            .show()
    }

    private fun downloadFile(file: RemoteFile) {
        val serverUrl = currentServerUrl ?: return
        val downloadUrl = "$serverUrl/download/${file.name}"

        Toast.makeText(this, "Скачивание пока не реализовано", Toast.LENGTH_SHORT).show()
    }

    private fun deleteFile(file: RemoteFile) {
        val serverUrl = currentServerUrl ?: return

        MaterialAlertDialogBuilder(this)
            .setTitle("Подтверждение")
            .setMessage("Удалить файл ${file.name}?")
            .setPositiveButton("Удалить") { _, _ ->
                performDelete(serverUrl, file.name)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun performDelete(serverUrl: String, fileName: String) {
        showLoading(true)

        mainScope.launch {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/delete/$fileName")
                    .get()
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        mainScope.launch {
                            showLoading(false)
                            showError("Ошибка удаления")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        mainScope.launch {
                            showLoading(false)
                            if (response.isSuccessful) {
                                showSuccess("Файл удален")
                                loadFileList(serverUrl)
                            } else {
                                showError("Ошибка удаления")
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                showLoading(false)
                showError("Ошибка: ${e.message}")
            }
        }
    }

    private fun saveServerUrl(url: String) {
        getSharedPreferences("app_prefs", MODE_PRIVATE)
            .edit()
            .putString("server_url", url)
            .apply()
    }

    private fun loadSavedServerUrl() {
        val url = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getString("server_url", null)

        url?.let {
            binding.serverUrlInput.setText(it)
        }
    }

    private fun showSettingsDialog() {
        Toast.makeText(this, "Настройки в разработке", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this,
                permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                showSuccess("Разрешения получены")
            } else {
                showError("Нужны разрешения для работы с файлами")
            }
        }
    }

    private fun showLoading(show: Boolean) {
        runOnUiThread {
            if (show) {
                binding.progressBar.visibility = View.VISIBLE
                binding.contentLayout.alpha = 0.5f
            } else {
                binding.progressBar.visibility = View.GONE
                binding.contentLayout.alpha = 1f
            }
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                .setBackgroundTint(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                .setTextColor(ContextCompat.getColor(this, android.R.color.white))
                .show()
        }
    }

    private fun showSuccess(message: String) {
        runOnUiThread {
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
                .setBackgroundTint(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                .setTextColor(ContextCompat.getColor(this, android.R.color.white))
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }
}
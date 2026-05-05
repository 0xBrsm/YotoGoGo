package com.yotogogo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.yotogogo.databinding.ActivityCardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class CardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCardBinding
    private val api = YotoApi()
    private var currentTracks: List<TrackItem> = emptyList()
    private var trackAdapter: TrackAdapter? = null
    private var cardTitle = ""

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            saveTreeUri(uri)
            updateFolderLabel(uri)
            startDownload(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val slug = intent.getStringExtra(EXTRA_SLUG) ?: run { finish(); return }
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: slug

        binding.tvCardTitle.text = displayName
        binding.rvTracks.layoutManager = LinearLayoutManager(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnChooseFolder.setOnClickListener { folderPicker.launch(savedTreeUri()) }
        binding.btnDownloadAll.setOnClickListener { downloadAll() }
        binding.cbSelectAll.setOnCheckedChangeListener { _, checked ->
            trackAdapter?.setAllSelected(checked)
        }

        val prefs = getSharedPreferences("yoto", MODE_PRIVATE)
        binding.switchMp3.isChecked = prefs.getBoolean("save_as_mp3", false)
        binding.switchMp3.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("save_as_mp3", checked).apply()
        }

        savedTreeUri()?.let { updateFolderLabel(it) }

        loadCard(slug)
    }

    private fun loadCard(slug: String) {
        val token = authToken() ?: run { finish(); return }
        binding.tvStatus.text = "Loading…"
        binding.progress.visibility = View.VISIBLE
        binding.btnDownloadAll.isEnabled = false

        lifecycleScope.launch {
            runCatching { api.getCard(token, slug) }
                .onSuccess { card ->
                    cardTitle = card.title ?: slug
                    binding.tvCardTitle.text = cardTitle
                    currentTracks = api.buildTrackList(card)
                    trackAdapter = TrackAdapter(currentTracks)
                    binding.rvTracks.adapter = trackAdapter
                    binding.cbSelectAll.visibility = View.VISIBLE
                    binding.tvStatus.text = "${currentTracks.size} track(s)"
                    binding.btnDownloadAll.isEnabled = currentTracks.isNotEmpty()
                }
                .onFailure { e ->
                    binding.tvStatus.text = "Error: ${e.message}"
                }
            binding.progress.visibility = View.GONE
        }
    }

    private fun downloadAll() {
        val treeUri = savedTreeUri()
        if (treeUri == null) folderPicker.launch(null) else startDownload(treeUri)
    }

    private fun startDownload(treeUri: Uri) {
        val tracksToDownload = trackAdapter?.getSelectedItems() ?: return
        if (tracksToDownload.isEmpty()) {
            binding.tvStatus.text = "No tracks selected"
            return
        }
        binding.btnDownloadAll.isEnabled = false
        val safe = cardTitle.replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
        val rootDir = DocumentFile.fromTreeUri(this, treeUri) ?: run {
            binding.tvStatus.text = "Error: cannot access selected folder"
            binding.btnDownloadAll.isEnabled = true
            return
        }
        val yotoDir = rootDir.findFile("Yoto GoGo") ?: rootDir.createDirectory("Yoto GoGo")
        val destDir = yotoDir?.findFile(safe) ?: yotoDir?.createDirectory(safe)
        if (destDir == null) {
            binding.tvStatus.text = "Error: could not create destination folder"
            binding.btnDownloadAll.isEnabled = true
            return
        }
        binding.tvStatus.text = "Saving…"

        val saveAsMp3 = binding.switchMp3.isChecked

        startService(Intent(this, DownloadService::class.java))

        lifecycleScope.launch {
            val httpClient = OkHttpClient()
            val semaphore = Semaphore(4)
            var doneCount = 0
            var errorCount = 0

            tracksToDownload.map { track ->
                val globalIndex = currentTracks.indexOf(track)
                trackAdapter?.updateStatus(globalIndex, DownloadStatus.DOWNLOADING)
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                    runCatching {
                        val (outFilename, outMime) = if (saveAsMp3)
                            track.filename.replaceAfterLast('.', "mp3") to "audio/mpeg"
                        else
                            track.filename to track.mimeType

                        val destFile = destDir.createFile(outMime, outFilename)
                            ?: throw Exception("Could not create file")

                        if (saveAsMp3) {
                            contentResolver.openOutputStream(destFile.uri)?.use { out ->
                                Transcoder.toMp3(track.url, out)
                            } ?: throw Exception("Could not open output stream")
                        } else {
                            val req = Request.Builder().url(track.url).build()
                            httpClient.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                                contentResolver.openOutputStream(destFile.uri)?.use { out ->
                                    resp.body?.byteStream()?.use { it.copyTo(out) }
                                        ?: throw Exception("Empty body")
                                } ?: throw Exception("Could not open output stream")
                            }
                        }
                    }.also { result ->
                        withContext(Dispatchers.Main) {
                            if (result.isSuccess) {
                                doneCount++
                                trackAdapter?.updateStatus(globalIndex, DownloadStatus.DONE)
                            } else {
                                errorCount++
                                trackAdapter?.updateStatus(globalIndex, DownloadStatus.ERROR)
                                binding.tvStatus.text = "Error: ${result.exceptionOrNull()?.message}"
                            }
                        }
                    }
                    } // semaphore.withPermit
                }
            }.awaitAll()

            stopService(Intent(this@CardActivity, DownloadService::class.java))
            binding.tvStatus.text = buildString {
                append("$doneCount/${tracksToDownload.size} tracks saved")
                if (errorCount > 0) append(" ($errorCount errors)")
            }
            binding.btnDownloadAll.isEnabled = true
        }
    }

    private fun savedTreeUri(): Uri? =
        getSharedPreferences("yoto", MODE_PRIVATE).getString("tree_uri", null)?.let { Uri.parse(it) }

    private fun saveTreeUri(uri: Uri) {
        getSharedPreferences("yoto", MODE_PRIVATE).edit()
            .putString("tree_uri", uri.toString()).apply()
    }

    private fun updateFolderLabel(uri: Uri) {
        val doc = DocumentFile.fromTreeUri(this, uri)
        binding.tvFolderPath.text = doc?.uri?.lastPathSegment ?: uri.toString()
    }

    private fun authToken() =
        getSharedPreferences("yoto", MODE_PRIVATE).getString("auth_token", null)

    companion object {
        const val EXTRA_SLUG = "slug"
        const val EXTRA_DISPLAY_NAME = "display_name"
    }
}

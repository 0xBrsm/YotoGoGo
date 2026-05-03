package com.yotogogo

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.yotogogo.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private val api = YotoApi()
    private var currentTracks: List<TrackItem> = emptyList()
    private var trackAdapter: TrackAdapter? = null
    private var cardTitle = "Yoto Card"

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
            if (currentTracks.isNotEmpty()) startDownload(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            binding.tvStatus.text = "This device does not support NFC"
            return
        }

        binding.rvTracks.layoutManager = LinearLayoutManager(this)
        binding.rvLibrary.layoutManager = LinearLayoutManager(this)

        binding.btnDownloadAll.setOnClickListener { downloadAll() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnChooseFolder.setOnClickListener { folderPicker.launch(savedTreeUri()) }

        savedTreeUri()?.let { updateFolderLabel(it) }

        loadLibrary()
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(
            this,
            PendingIntent.getActivity(
                this, 0,
                Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE
            ),
            null, null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    private fun loadLibrary() {
        val token = authToken() ?: return
        binding.tvLibraryStatus.visibility = View.VISIBLE
        binding.tvLibraryStatus.text = "Loading…"
        lifecycleScope.launch {
            runCatching { api.getLibrary(token) }
                .onSuccess { cards ->
                    binding.tvLibraryStatus.visibility = View.GONE
                    binding.rvLibrary.adapter = LibraryAdapter(cards) { card ->
                        card.slug?.let { loadCard(it, card.title ?: it) }
                    }
                }
                .onFailure { e ->
                    binding.tvLibraryStatus.text = "Library unavailable: ${e.message}"
                }
        }
    }

    private fun handleNfcIntent(intent: Intent) {
        if (intent.action !in listOf(
                NfcAdapter.ACTION_NDEF_DISCOVERED,
                NfcAdapter.ACTION_TAG_DISCOVERED,
                NfcAdapter.ACTION_TECH_DISCOVERED
            )
        ) return

        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        val nfcUrl = readUrlFromTag(tag) ?: run {
            binding.tvStatus.text = "Could not read card. Raw tag ID: ${tag.id.toHex()}"
            return
        }
        val slug = Uri.parse(nfcUrl).lastPathSegment ?: nfcUrl
        loadCard(slug, slug)
    }

    private fun readUrlFromTag(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val message = ndef.ndefMessage
            ndef.close()
            message?.records?.firstNotNullOfOrNull { record ->
                if (record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                    record.type.contentEquals(NdefRecord.RTD_URI)
                ) {
                    val payload = record.payload
                    val uri = uriPrefixFor(payload[0]) + String(payload.drop(1).toByteArray())
                    uri.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Exception) {
            binding.tvStatus.text = "NFC read error: ${e.message}"
            null
        }
    }

    private fun uriPrefixFor(code: Byte): String = when (code.toInt()) {
        0x01 -> "http://www."
        0x02 -> "https://www."
        0x03 -> "http://"
        0x04 -> "https://"
        else -> ""
    }

    private fun loadCard(slug: String, displayName: String) {
        val token = authToken() ?: run { logout(); return }

        setLoading(true)
        binding.tvStatus.text = "Fetching: $displayName…"
        binding.layoutDownload.visibility = View.GONE
        currentTracks = emptyList()
        trackAdapter = null
        binding.rvTracks.adapter = null
        binding.tvCardTitle.text = ""

        lifecycleScope.launch {
            runCatching { api.getCard(token, slug) }
                .onSuccess { card ->
                    cardTitle = card.title ?: displayName
                    binding.tvCardTitle.text = cardTitle
                    currentTracks = api.buildTrackList(card)
                    trackAdapter = TrackAdapter(currentTracks)
                    binding.rvTracks.adapter = trackAdapter
                    binding.tvStatus.text = "${currentTracks.size} track(s) — tap Download to save"
                    binding.layoutDownload.visibility =
                        if (currentTracks.isNotEmpty()) View.VISIBLE else View.GONE
                }
                .onFailure { e ->
                    binding.tvStatus.text = "Error: ${e.message}"
                }
            setLoading(false)
        }
    }

    private fun downloadAll() {
        val treeUri = savedTreeUri()
        if (treeUri == null) {
            folderPicker.launch(null)
        } else {
            startDownload(treeUri)
        }
    }

    private fun startDownload(treeUri: Uri) {
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
        binding.tvStatus.text = "Saving to: ${destDir.uri.lastPathSegment}"

        lifecycleScope.launch {
            val httpClient = OkHttpClient()
            currentTracks.forEachIndexed { i, track ->
                trackAdapter?.updateStatus(i, DownloadStatus.DOWNLOADING)
                runCatching {
                    withContext(Dispatchers.IO) {
                        val req = Request.Builder().url(track.url).build()
                        httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                            val existing = destDir.findFile(track.filename)
                            val file = existing ?: destDir.createFile(track.mimeType, track.filename)
                                ?: throw Exception("Could not create file")
                            contentResolver.openOutputStream(file.uri)?.use { out ->
                                resp.body?.byteStream()?.use { it.copyTo(out) }
                                    ?: throw Exception("Empty body")
                            } ?: throw Exception("Could not open output stream")
                        }
                    }
                }.onSuccess {
                    trackAdapter?.updateStatus(i, DownloadStatus.DONE)
                }.onFailure {
                    trackAdapter?.updateStatus(i, DownloadStatus.ERROR)
                }
            }
            val done = currentTracks.count { it.status == DownloadStatus.DONE }
            binding.tvStatus.text = "$done/${currentTracks.size} tracks saved"
            binding.btnDownloadAll.isEnabled = true
        }
    }

    private fun savedTreeUri(): Uri? {
        val str = getSharedPreferences("yoto", MODE_PRIVATE).getString("tree_uri", null)
        return str?.let { Uri.parse(it) }
    }

    private fun saveTreeUri(uri: Uri) {
        getSharedPreferences("yoto", MODE_PRIVATE).edit()
            .putString("tree_uri", uri.toString()).apply()
    }

    private fun updateFolderLabel(uri: Uri) {
        val doc = DocumentFile.fromTreeUri(this, uri)
        binding.tvFolderPath.text = doc?.uri?.lastPathSegment ?: uri.toString()
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun authToken() =
        getSharedPreferences("yoto", MODE_PRIVATE).getString("auth_token", null)

    private fun logout() {
        getSharedPreferences("yoto", MODE_PRIVATE).edit().clear().apply()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

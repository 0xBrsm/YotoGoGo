package com.yotogogo

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.yotogogo.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private val api = YotoApi()
    private var currentTracks: List<TrackItem> = emptyList()
    private var trackAdapter: TrackAdapter? = null
    private var cardTitle = "Yoto Card"

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
        binding.btnDownloadAll.setOnClickListener { downloadAll() }
        binding.btnLogout.setOnClickListener { logout() }

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

    private fun handleNfcIntent(intent: Intent) {
        if (intent.action !in listOf(
                NfcAdapter.ACTION_NDEF_DISCOVERED,
                NfcAdapter.ACTION_TAG_DISCOVERED,
                NfcAdapter.ACTION_TECH_DISCOVERED
            )
        ) return

        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        val slug = readSlugFromTag(tag)

        if (slug == null) {
            binding.tvStatus.text = "Could not read card. Raw tag ID: ${tag.id.toHex()}"
            return
        }

        fetchCard(slug)
    }

    private fun readSlugFromTag(tag: Tag): String? {
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
                    binding.tvStatus.text = "Read: $uri"
                    extractSlug(uri)
                } else null
            }
        } catch (e: Exception) {
            binding.tvStatus.text = "NFC read error: ${e.message}"
            null
        }
    }

    private fun extractSlug(uri: String): String? {
        if (uri.startsWith("https://yoto.io/") || uri.startsWith("https://yotoplay.com/")) {
            return uri.substringAfterLast('/').takeIf { it.isNotBlank() }
        }
        Regex("^yoto://[^/]+/(.+)$").find(uri)?.let {
            return it.groupValues[1].trimEnd('/')
        }
        return uri.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    private fun uriPrefixFor(code: Byte): String = when (code.toInt()) {
        0x01 -> "http://www."
        0x02 -> "https://www."
        0x03 -> "http://"
        0x04 -> "https://"
        else -> ""
    }

    private fun fetchCard(slug: String) {
        val token = authToken() ?: run { logout(); return }

        setLoading(true)
        binding.tvStatus.text = "Fetching: $slug…"
        binding.btnDownloadAll.visibility = View.GONE

        lifecycleScope.launch {
            runCatching { api.getCard(token, slug) }
                .onSuccess { card ->
                    cardTitle = card.title ?: slug
                    binding.tvCardTitle.text = cardTitle
                    currentTracks = api.buildTrackList(card)
                    trackAdapter = TrackAdapter(currentTracks)
                    binding.rvTracks.adapter = trackAdapter
                    binding.tvStatus.text = "${currentTracks.size} track(s) — tap Download to save"
                    binding.btnDownloadAll.visibility =
                        if (currentTracks.isNotEmpty()) View.VISIBLE else View.GONE
                }
                .onFailure { e -> binding.tvStatus.text = "Error fetching \"$slug\":\n${e.message}" }
            setLoading(false)
        }
    }

    private fun downloadAll() {
        binding.btnDownloadAll.isEnabled = false
        val destDir = resolveDestDir(cardTitle)
        binding.tvStatus.text = "Saving to: ${destDir.absolutePath}"

        lifecycleScope.launch {
            val httpClient = OkHttpClient()
            currentTracks.forEachIndexed { i, track ->
                trackAdapter?.updateStatus(i, DownloadStatus.DOWNLOADING)
                runCatching {
                    withContext(Dispatchers.IO) {
                        // CDN URLs are pre-signed — no auth header needed
                        val req = Request.Builder().url(track.url).build()
                        httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                            val file = File(destDir, track.filename)
                            resp.body?.byteStream()?.use { input ->
                                file.outputStream().use { output -> input.copyTo(output) }
                            } ?: throw Exception("Empty body")
                        }
                    }
                }.onSuccess {
                    trackAdapter?.updateStatus(i, DownloadStatus.DONE)
                }.onFailure {
                    trackAdapter?.updateStatus(i, DownloadStatus.ERROR)
                }
            }
            val done = currentTracks.count { it.status == DownloadStatus.DONE }
            binding.tvStatus.text = "$done/${currentTracks.size} tracks saved to ${destDir.absolutePath}"
            binding.btnDownloadAll.isEnabled = true
        }
    }

    private fun resolveDestDir(title: String): File {
        val externalDirs = getExternalFilesDirs(null)
        val base = if (externalDirs.size > 1) externalDirs[1] else externalDirs[0]
        val safe = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
        return File(base, "Yoto GoGo/$safe").also { it.mkdirs() }
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

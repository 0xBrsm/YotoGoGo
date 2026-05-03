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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.yotogogo.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private val api = YotoApi()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            binding.tvStatus.text = "This device does not support NFC"
            return
        }

        binding.rvLibrary.layoutManager = LinearLayoutManager(this)
        binding.btnLogout.setOnClickListener { logout() }

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
                        openCard(card.slug ?: return@LibraryAdapter, card.title ?: card.slug ?: "")
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
        openCard(slug, slug)
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

    private fun openCard(slug: String, displayName: String) {
        startActivity(
            Intent(this, CardActivity::class.java)
                .putExtra(CardActivity.EXTRA_SLUG, slug)
                .putExtra(CardActivity.EXTRA_DISPLAY_NAME, displayName)
        )
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

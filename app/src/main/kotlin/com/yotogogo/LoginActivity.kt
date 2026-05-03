package com.yotogogo

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import com.yotogogo.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val api = YotoApi()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedToken() != null) { startMain(); return }

        // Returning from browser redirect
        handleCallbackIntent(intent)

        binding.btnConnect.setOnClickListener { startPkceFlow() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCallbackIntent(intent)
    }

    private fun handleCallbackIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "com.yotogogo" || uri.host != "callback") return

        val error = uri.getQueryParameter("error")
        if (error != null) {
            showError("Login denied: $error")
            return
        }

        val code = uri.getQueryParameter("code") ?: run {
            showError("No code in callback")
            return
        }

        exchangeCode(code)
    }

    private fun startPkceFlow() {
        val verifier  = YotoApi.generateCodeVerifier()
        val challenge = YotoApi.generateCodeChallenge(verifier)

        prefs().edit().putString("pkce_verifier", verifier).apply()

        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(this, Uri.parse(YotoApi.buildAuthUrl(challenge)))
    }

    private fun exchangeCode(code: String) {
        val verifier = prefs().getString("pkce_verifier", null) ?: run {
            showError("Session expired — please try again")
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            runCatching { api.exchangeCodeForToken(code, verifier) }
                .onSuccess { token ->
                    prefs().edit()
                        .putString("auth_token", token)
                        .remove("pkce_verifier")
                        .apply()
                    saveTokenForScript(token)
                    startMain()
                }
                .onFailure { e -> showError(e.message ?: "Authentication failed") }
        }
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
        setLoading(false)
        binding.btnConnect.isEnabled = true
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnConnect.isEnabled = !loading
    }

    private fun saveTokenForScript(token: String) {
        val filename = ".yoto_token"
        val resolver = contentResolver
        val existing = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(filename), null
        )
        existing?.use { if (it.moveToFirst()) resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads._ID}=?", arrayOf(it.getString(0))) }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            resolver.openOutputStream(uri)?.use { it.write(token.toByteArray()) }
        }
    }

    private fun prefs() = getSharedPreferences("yoto", MODE_PRIVATE)
    private fun savedToken() = prefs().getString("auth_token", null)

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

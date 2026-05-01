package com.yotogogo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        binding.btnConnect.setOnClickListener { startDeviceFlow() }
    }

    private fun startDeviceFlow() {
        binding.btnConnect.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        binding.tvInstructions.visibility = View.GONE

        lifecycleScope.launch {
            runCatching { api.requestDeviceCode() }
                .onSuccess { codes ->
                    binding.tvCode.text = codes.userCode
                    binding.tvUrl.text = codes.verificationUri
                    binding.layoutCode.visibility = View.VISIBLE

                    binding.btnOpenBrowser.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(codes.verificationUri)))
                    }
                    binding.tvCode.setOnClickListener {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Yoto code", codes.userCode))
                        Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show()
                    }

                    // Poll in the background until authorized
                    runCatching { api.pollForToken(codes) }
                        .onSuccess { token ->
                            getSharedPreferences("yoto", MODE_PRIVATE)
                                .edit().putString("auth_token", token).apply()
                            startMain()
                        }
                        .onFailure { e -> showError(e.message ?: "Authorization failed") }
                }
                .onFailure { e -> showError(e.message ?: "Could not start login") }
        }
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
        binding.progress.visibility = View.GONE
        binding.layoutCode.visibility = View.GONE
        binding.btnConnect.isEnabled = true
        binding.tvInstructions.visibility = View.VISIBLE
    }

    private fun savedToken() =
        getSharedPreferences("yoto", MODE_PRIVATE).getString("auth_token", null)

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

package com.yotodl

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yotodl.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val api = YotoApi()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Skip login if token already saved
        if (savedToken() != null) {
            startMain()
            return
        }

        binding.btnLogin.setOnClickListener { doLogin() }
    }

    private fun doLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (email.isEmpty() || password.isEmpty()) {
            binding.tvError.text = "Email and password are required"
            binding.tvError.visibility = View.VISIBLE
            return
        }

        binding.btnLogin.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE

        lifecycleScope.launch {
            runCatching { api.login(email, password) }
                .onSuccess { token ->
                    getSharedPreferences("yoto", MODE_PRIVATE)
                        .edit().putString("auth_token", token).apply()
                    startMain()
                }
                .onFailure { e ->
                    binding.tvError.text = e.message ?: "Login failed"
                    binding.tvError.visibility = View.VISIBLE
                    binding.btnLogin.isEnabled = true
                    binding.progress.visibility = View.GONE
                }
        }
    }

    private fun savedToken() =
        getSharedPreferences("yoto", MODE_PRIVATE).getString("auth_token", null)

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

package cl.inacap.piscicultura.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cl.inacap.piscicultura.PisciculturaApp
import com.example.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private val viewModel: LoginViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as PisciculturaApp
                return LoginViewModel(app.userRepository) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializar usuario demo en almacenamiento local seguro
        viewModel.seedDefaultUser()

        // Verificación de sesión persistente activa (expiración de 24 horas)
        if (viewModel.isSessionActive()) {
            navigateToRoleSelect()
            return
        }

        restoreFormState()
        setupListeners()
        observeState()
    }

    private fun restoreFormState() {
        if (viewModel.savedUsername.isNotEmpty()) {
            binding.etUsername.setText(viewModel.savedUsername)
        }
        if (viewModel.savedPasswordChars.isNotEmpty()) {
            binding.etPassword.setText(viewModel.savedPasswordChars)
        }
    }

    private fun setupListeners() {
        binding.etUsername.doAfterTextChanged {
            viewModel.savedUsername = it?.toString().orEmpty()
        }

        binding.etPassword.doAfterTextChanged {
            viewModel.savedPasswordChars = it?.toString().orEmpty()
        }

        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text?.toString().orEmpty()
            val password = binding.etPassword.text
            // Hashing y validación se ejecutan en Dispatchers.IO en el repositorio
            // Cero credenciales expuestas en logs
            viewModel.login(username, password ?: "")
        }

        // Acceso rápido directo para pruebas / evaluación del sistema
        binding.btnQuickGuest.setOnClickListener {
            viewModel.enterAsGuest()
        }

        // Llenado automático con credenciales de prueba preconfiguradas
        binding.btnFillDemo.setOnClickListener {
            binding.etUsername.setText("operador")
            binding.etPassword.setText("Inacap2024*")
            Toast.makeText(this, "Credenciales demo cargadas", Toast.LENGTH_SHORT).show()
        }

        binding.btnGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loginState.collect { state ->
                    when (state) {
                        is LoginState.Idle -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnLogin.isEnabled = true
                            binding.tvError.visibility = View.GONE
                        }
                        is LoginState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.btnLogin.isEnabled = false
                            binding.tvError.visibility = View.GONE
                        }
                        is LoginState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnLogin.isEnabled = true
                            binding.tvError.visibility = View.GONE
                            Toast.makeText(this@LoginActivity, "Bienvenido: ${state.username}", Toast.LENGTH_SHORT).show()
                            navigateToRoleSelect()
                        }
                        is LoginState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnLogin.isEnabled = true
                            binding.tvError.visibility = View.VISIBLE
                            binding.tvError.text = state.message
                        }
                        is LoginState.LockedOut -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnLogin.isEnabled = false
                            binding.tvError.visibility = View.VISIBLE
                            binding.tvError.text = "Demasiados intentos fallidos. Bloqueado temporalmente por ${state.secondsRemaining} segundos."
                        }
                    }
                }
            }
        }
    }

    private fun navigateToRoleSelect() {
        val intent = Intent(this, RoleSelectActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}

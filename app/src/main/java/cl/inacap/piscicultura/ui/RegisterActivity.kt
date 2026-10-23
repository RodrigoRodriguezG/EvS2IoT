package cl.inacap.piscicultura.ui

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
import com.example.databinding.ActivityRegisterBinding
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    private val viewModel: RegisterViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as PisciculturaApp
                return RegisterViewModel(app.userRepository) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreFormState()
        setupListeners()
        observeState()
    }

    private fun restoreFormState() {
        if (viewModel.savedUsername.isNotEmpty()) {
            binding.etRegUsername.setText(viewModel.savedUsername)
        }
        if (viewModel.savedPasswordChars.isNotEmpty()) {
            binding.etRegPassword.setText(viewModel.savedPasswordChars)
        }
        if (viewModel.savedConfirmChars.isNotEmpty()) {
            binding.etRegConfirmPassword.setText(viewModel.savedConfirmChars)
        }
    }

    private fun setupListeners() {
        binding.etRegUsername.doAfterTextChanged {
            viewModel.savedUsername = it?.toString().orEmpty()
        }

        binding.etRegPassword.doAfterTextChanged {
            viewModel.savedPasswordChars = it?.toString().orEmpty()
        }

        binding.etRegConfirmPassword.doAfterTextChanged {
            viewModel.savedConfirmChars = it?.toString().orEmpty()
        }

        binding.btnBackToLogin.setOnClickListener {
            finish()
        }

        binding.btnSubmitRegister.setOnClickListener {
            val username = binding.etRegUsername.text?.toString().orEmpty()
            val pass = binding.etRegPassword.text ?: ""
            val confirm = binding.etRegConfirmPassword.text ?: ""

            // Cero credenciales expuestas en logs
            viewModel.register(username, pass, confirm)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.registerState.collect { state ->
                    when (state) {
                        is RegisterState.Idle -> {
                            binding.progressReg.visibility = View.GONE
                            binding.btnSubmitRegister.isEnabled = true
                            binding.tvRegError.visibility = View.GONE
                        }
                        is RegisterState.Loading -> {
                            binding.progressReg.visibility = View.VISIBLE
                            binding.btnSubmitRegister.isEnabled = false
                            binding.tvRegError.visibility = View.GONE
                        }
                        is RegisterState.Success -> {
                            binding.progressReg.visibility = View.GONE
                            binding.btnSubmitRegister.isEnabled = true
                            binding.tvRegError.visibility = View.GONE
                            Toast.makeText(
                                this@RegisterActivity,
                                "Usuario ${state.username} registrado exitosamente. Ahora puedes ingresar.",
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                        }
                        is RegisterState.Error -> {
                            binding.progressReg.visibility = View.GONE
                            binding.btnSubmitRegister.isEnabled = true
                            binding.tvRegError.visibility = View.VISIBLE
                            binding.tvRegError.text = state.message
                        }
                    }
                }
            }
        }
    }
}

package cl.inacap.piscicultura.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.inacap.piscicultura.data.RegisterResult
import cl.inacap.piscicultura.data.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class RegisterState {
    object Idle : RegisterState()
    object Loading : RegisterState()
    data class Success(val username: String) : RegisterState()
    data class Error(val message: String) : RegisterState()
}

class RegisterViewModel(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _registerState = MutableStateFlow<RegisterState>(RegisterState.Idle)
    val registerState: StateFlow<RegisterState> = _registerState.asStateFlow()

    // Preservar estado del formulario ante rotación de pantalla
    var savedUsername: String = ""
    var savedPasswordChars: CharSequence = ""
    var savedConfirmChars: CharSequence = ""

    fun register(
        username: String,
        passwordChars: CharSequence,
        confirmPasswordChars: CharSequence
    ) {
        val trimmedUser = username.trim()

        if (trimmedUser.isBlank() || passwordChars.isEmpty() || confirmPasswordChars.isEmpty()) {
            _registerState.value = RegisterState.Error("Por favor completa todos los campos.")
            return
        }

        if (passwordChars.toString() != confirmPasswordChars.toString()) {
            _registerState.value = RegisterState.Error("Las contraseñas no coinciden.")
            return
        }

        if (passwordChars.length < 8) {
            _registerState.value = RegisterState.Error("La contraseña debe tener al menos 8 caracteres.")
            return
        }

        val hasLetter = passwordChars.any { it.isLetter() }
        val hasDigit = passwordChars.any { it.isDigit() }
        if (!hasLetter || !hasDigit) {
            _registerState.value = RegisterState.Error("La contraseña debe incluir al menos una letra y un número.")
            return
        }

        savedUsername = trimmedUser
        savedPasswordChars = passwordChars
        savedConfirmChars = confirmPasswordChars

        _registerState.value = RegisterState.Loading

        viewModelScope.launch {
            // Hashing y almacenamiento en Dispatchers.IO
            when (val result = userRepository.registerUser(trimmedUser, passwordChars)) {
                is RegisterResult.Success -> {
                    _registerState.value = RegisterState.Success(result.username)
                }
                is RegisterResult.Error -> {
                    _registerState.value = RegisterState.Error(result.message)
                }
            }
        }
    }

    fun clearState() {
        _registerState.value = RegisterState.Idle
    }
}

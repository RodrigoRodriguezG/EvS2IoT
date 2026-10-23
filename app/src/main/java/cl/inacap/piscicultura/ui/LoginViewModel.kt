package cl.inacap.piscicultura.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.inacap.piscicultura.data.LoginResult
import cl.inacap.piscicultura.data.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    data class Success(val username: String) : LoginState()
    data class Error(val message: String) : LoginState()
    data class LockedOut(val secondsRemaining: Long) : LoginState()
}

class LoginViewModel(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    // Preservar estado del formulario ante rotación de pantalla
    var savedUsername: String = ""
    var savedPasswordChars: CharSequence = ""

    fun isSessionActive(): Boolean {
        return userRepository.isSessionValid()
    }

    fun getActiveUsername(): String? {
        return userRepository.getActiveUsername()
    }

    fun login(username: String, passwordChars: CharSequence) {
        val trimmedUser = username.trim()
        if (trimmedUser.isBlank() || passwordChars.isEmpty()) {
            _loginState.value = LoginState.Error("Por favor ingresa usuario y contraseña.")
            return
        }

        savedUsername = trimmedUser
        savedPasswordChars = passwordChars

        _loginState.value = LoginState.Loading

        viewModelScope.launch {
            // Se ejecuta en UserRepository con Dispatchers.IO
            when (val result = userRepository.loginUser(trimmedUser, passwordChars)) {
                is LoginResult.Success -> {
                    _loginState.value = LoginState.Success(result.username)
                }
                is LoginResult.InvalidCredentials -> {
                    _loginState.value = LoginState.Error(result.message)
                }
                is LoginResult.LockedOut -> {
                    _loginState.value = LoginState.LockedOut(result.remainingSeconds)
                }
            }
        }
    }

    fun clearState() {
        _loginState.value = LoginState.Idle
    }
}

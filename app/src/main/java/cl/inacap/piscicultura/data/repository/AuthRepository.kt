package cl.inacap.piscicultura.data.repository

import cl.inacap.piscicultura.security.EncryptedSessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await

sealed class AuthResult {
    data class Success(val user: FirebaseUser?, val email: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

class AuthRepository(
    private val sessionManager: EncryptedSessionManager,
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    val currentUser: FirebaseUser?
        get() = firebaseAuth.currentUser

    fun isUserLoggedIn(): Boolean {
        return sessionManager.isLoggedIn() || firebaseAuth.currentUser != null
    }

    fun getLoggedUserEmail(): String? {
        return firebaseAuth.currentUser?.email ?: sessionManager.getUserEmail()
    }

    suspend fun login(email: String, passChars: CharSequence): AuthResult {
        return try {
            val password = passChars.toString()
            val authResult = firebaseAuth.signInWithEmailAndPassword(email.trim(), password).await()
            val user = authResult.user
            if (user != null) {
                sessionManager.saveUserSession(user.email ?: email.trim(), user.uid)
                AuthResult.Success(user, user.email ?: email.trim())
            } else {
                AuthResult.Error("No se pudo obtener información del usuario.")
            }
        } catch (e: Exception) {
            // Si no hay internet pero la sesión ya estaba autenticada previamente en este dispositivo
            if (sessionManager.isLoggedIn() && sessionManager.getUserEmail().equals(email.trim(), ignoreCase = true)) {
                AuthResult.Success(null, email.trim())
            } else {
                AuthResult.Error(e.localizedMessage ?: "Error de autenticación en Firebase.")
            }
        }
    }

    suspend fun register(email: String, passChars: CharSequence): AuthResult {
        return try {
            val password = passChars.toString()
            val authResult = firebaseAuth.createUserWithEmailAndPassword(email.trim(), password).await()
            val user = authResult.user
            if (user != null) {
                sessionManager.saveUserSession(user.email ?: email.trim(), user.uid)
                AuthResult.Success(user, user.email ?: email.trim())
            } else {
                AuthResult.Error("Registro completado pero no se obtuvo usuario.")
            }
        } catch (e: Exception) {
            AuthResult.Error(e.localizedMessage ?: "Error al registrar usuario en Firebase.")
        }
    }

    fun logout() {
        try {
            firebaseAuth.signOut()
        } catch (_: Exception) {
        }
        sessionManager.clearSession()
    }
}

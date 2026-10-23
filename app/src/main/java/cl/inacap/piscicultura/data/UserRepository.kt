package cl.inacap.piscicultura.data

import cl.inacap.piscicultura.security.PasswordHasher
import cl.inacap.piscicultura.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class RegisterResult {
    data class Success(val username: String) : RegisterResult()
    data class Error(val message: String) : RegisterResult()
}

sealed class LoginResult {
    data class Success(val username: String) : LoginResult()
    data class InvalidCredentials(val message: String) : LoginResult()
    data class LockedOut(val remainingSeconds: Long) : LoginResult()
}

/**
 * Repositorio de autenticación local segura con EncryptedSharedPreferences (SecureStore).
 * - CERO contraseñas en texto plano: hashing PBKDF2 con salt en Dispatchers.IO.
 * - Validación estricta de contraseñas (mínimo 8 caracteres, al menos 1 letra y 1 número).
 * - Persistencia de sesión con expiración de 24 horas.
 * - Bloqueo temporal de 30 segundos tras 5 intentos fallidos consecutivos.
 * - Respuestas genéricas para prevenir enumeración de usuarios.
 */
class UserRepository(
    private val secureStore: SecureStore
) {

    suspend fun registerUser(
        usernameInput: String,
        passwordChars: CharSequence
    ): RegisterResult = withContext(Dispatchers.IO) {
        val username = usernameInput.trim()

        if (username.length < 3) {
            return@withContext RegisterResult.Error("El nombre de usuario debe tener al menos 3 caracteres.")
        }

        if (isUserRegistered(username)) {
            return@withContext RegisterResult.Error("El nombre de usuario ya se encuentra registrado.")
        }

        // Validación de contraseña: mínimo 8 caracteres, con letra y número
        if (passwordChars.length < 8) {
            return@withContext RegisterResult.Error("La contraseña debe tener un mínimo de 8 caracteres.")
        }

        val hasLetter = passwordChars.any { it.isLetter() }
        val hasDigit = passwordChars.any { it.isDigit() }

        if (!hasLetter || !hasDigit) {
            return@withContext RegisterResult.Error("La contraseña debe incluir al menos una letra y un número.")
        }

        // Generar hash en hilo de I/O
        val hashResult = PasswordHasher.hashPassword(passwordChars)

        // Almacenar en SecureStore
        val keyUser = getUserKeyPrefix(username)
        secureStore.putString("${keyUser}_salt", hashResult.saltBase64)
        secureStore.putString("${keyUser}_hash", hashResult.hashBase64)
        addRegisteredUser(username)

        return@withContext RegisterResult.Success(username)
    }

    suspend fun seedDefaultUserIfNeeded() = withContext(Dispatchers.IO) {
        if (!isUserRegistered("operador")) {
            val hashResult = PasswordHasher.hashPassword("Inacap2024*")
            val keyUser = getUserKeyPrefix("operador")
            secureStore.putString("${keyUser}_salt", hashResult.saltBase64)
            secureStore.putString("${keyUser}_hash", hashResult.hashBase64)
            addRegisteredUser("operador")
        }
    }

    fun setGuestSession(guestName: String = "operador_demo") {
        secureStore.putString(KEY_ACTIVE_USERNAME, guestName)
        secureStore.putString(KEY_SESSION_TOKEN, UUID.randomUUID().toString())
        secureStore.putLong(KEY_SESSION_TIMESTAMP, System.currentTimeMillis())
    }

    suspend fun loginUser(
        usernameInput: String,
        passwordChars: CharSequence
    ): LoginResult = withContext(Dispatchers.IO) {
        val username = usernameInput.trim()

        // 1. Verificar bloqueo por intentos fallidos
        val lockoutUntil = secureStore.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val currentTime = System.currentTimeMillis()

        if (currentTime < lockoutUntil) {
            val remainingSec = ((lockoutUntil - currentTime) / 1000L).coerceAtLeast(1L)
            return@withContext LoginResult.LockedOut(remainingSec)
        }

        // 2. Obtener salt y hash almacenados
        val keyUser = getUserKeyPrefix(username)
        val storedSalt = secureStore.getString("${keyUser}_salt")
        val storedHash = secureStore.getString("${keyUser}_hash")

        if (storedSalt.isNullOrEmpty() || storedHash.isNullOrEmpty()) {
            return@withContext handleFailedAttempt()
        }

        // 3. Verificar hash en tiempo constante (Dispatchers.IO)
        val isValid = PasswordHasher.verifyPassword(passwordChars, storedSalt, storedHash)

        if (!isValid) {
            return@withContext handleFailedAttempt()
        }

        // 4. Éxito: resetear contadores de intentos fallidos
        secureStore.putInt(KEY_FAILED_ATTEMPTS, 0)
        secureStore.putLong(KEY_LOCKOUT_UNTIL, 0L)

        // 5. Guardar sesión activa (usuario + token aleatorio + timestamp)
        val sessionToken = UUID.randomUUID().toString()
        secureStore.putString(KEY_ACTIVE_USERNAME, username)
        secureStore.putString(KEY_SESSION_TOKEN, sessionToken)
        secureStore.putLong(KEY_SESSION_TIMESTAMP, System.currentTimeMillis())

        return@withContext LoginResult.Success(username)
    }

    private fun handleFailedAttempt(): LoginResult {
        val currentAttempts = secureStore.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        return if (currentAttempts >= MAX_FAILED_ATTEMPTS) {
            val lockoutTime = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            secureStore.putLong(KEY_LOCKOUT_UNTIL, lockoutTime)
            secureStore.putInt(KEY_FAILED_ATTEMPTS, 0)
            LoginResult.LockedOut(LOCKOUT_DURATION_MS / 1000L)
        } else {
            secureStore.putInt(KEY_FAILED_ATTEMPTS, currentAttempts)
            LoginResult.InvalidCredentials("Credenciales inválidas. Verifica usuario y contraseña.")
        }
    }

    fun isSessionValid(): Boolean {
        val activeUser = secureStore.getString(KEY_ACTIVE_USERNAME)
        val token = secureStore.getString(KEY_SESSION_TOKEN)
        val timestamp = secureStore.getLong(KEY_SESSION_TIMESTAMP, 0L)

        if (activeUser.isNullOrEmpty() || token.isNullOrEmpty() || timestamp <= 0L) {
            return false
        }

        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - timestamp

        return if (elapsed < SESSION_EXPIRATION_MS) {
            true
        } else {
            // Sesión caducada tras 24 horas
            logout()
            false
        }
    }

    fun getActiveUsername(): String? {
        return if (isSessionValid()) {
            secureStore.getString(KEY_ACTIVE_USERNAME)
        } else {
            null
        }
    }

    fun logout() {
        secureStore.remove(KEY_ACTIVE_USERNAME)
        secureStore.remove(KEY_SESSION_TOKEN)
        secureStore.remove(KEY_SESSION_TIMESTAMP)
    }

    private fun isUserRegistered(username: String): Boolean {
        val keyUser = getUserKeyPrefix(username)
        return secureStore.contains("${keyUser}_hash")
    }

    private fun getUserKeyPrefix(username: String): String {
        return "user_" + username.lowercase().replace("[^a-z0-9_]".toRegex(), "_")
    }

    private fun addRegisteredUser(username: String) {
        val existing = secureStore.getString(KEY_USER_LIST, "") ?: ""
        val updated = if (existing.isEmpty()) username else "$existing,$username"
        secureStore.putString(KEY_USER_LIST, updated)
    }

    companion object {
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L // 30 segundos
        private const val SESSION_EXPIRATION_MS = 24 * 60 * 60 * 1000L // 24 horas

        private const val KEY_ACTIVE_USERNAME = "active_session_username"
        private const val KEY_SESSION_TOKEN = "active_session_token"
        private const val KEY_SESSION_TIMESTAMP = "active_session_timestamp"
        private const val KEY_FAILED_ATTEMPTS = "auth_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "auth_lockout_until"
        private const val KEY_USER_LIST = "registered_users_list"
    }
}

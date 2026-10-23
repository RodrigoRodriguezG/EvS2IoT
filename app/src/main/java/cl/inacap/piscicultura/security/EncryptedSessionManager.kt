package cl.inacap.piscicultura.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Gestor de sesión seguro utilizando EncryptedSharedPreferences con MasterKey (AES-256).
 * Cumple con el requisito de CERO contraseñas en texto plano (nunca almacena passwords).
 */
class EncryptedSessionManager(context: Context) {

    private val sharedPreferences: SharedPreferences = try {
        val masterKey: MasterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveUserSession(email: String, uid: String) {
        sharedPreferences.edit()
            .putString(KEY_USER_EMAIL, email)
            .putString(KEY_USER_UID, uid)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
    }

    fun saveSelectedRole(role: String) {
        sharedPreferences.edit()
            .putString(KEY_SELECTED_ROLE, role)
            .apply()
    }

    fun getSelectedRole(): String? {
        return sharedPreferences.getString(KEY_SELECTED_ROLE, null)
    }

    fun getUserEmail(): String? {
        return sharedPreferences.getString(KEY_USER_EMAIL, null)
    }

    fun getUserUid(): String? {
        return sharedPreferences.getString(KEY_USER_UID, null)
    }

    fun isLoggedIn(): Boolean {
        return sharedPreferences.getBoolean(KEY_IS_LOGGED_IN, false)
    }

    fun clearSession() {
        sharedPreferences.edit()
            .clear()
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "piscicultura_secure_session_prefs"
        private const val KEY_USER_EMAIL = "encrypted_user_email"
        private const val KEY_USER_UID = "encrypted_user_uid"
        private const val KEY_IS_LOGGED_IN = "encrypted_is_logged_in"
        private const val KEY_SELECTED_ROLE = "encrypted_selected_role"

        const val ROLE_NODO = "NODO_SENSOR"
        const val ROLE_ESTACION = "ESTACION_MONITOREO"
    }
}

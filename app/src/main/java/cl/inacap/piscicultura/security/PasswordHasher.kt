package cl.inacap.piscicultura.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Módulo de hashing de contraseñas seguro mediante PBKDF2WithHmacSHA256.
 * - Iteraciones: 120.000
 * - Salt criptográfico de 16 bytes con SecureRandom
 * - Comparación en tiempo constante con MessageDigest.isEqual
 * - CERO almacenamiento de contraseñas en texto plano: solo salt y hash en Base64.
 */
object PasswordHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATION_COUNT = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16

    data class HashResult(
        val saltBase64: String,
        val hashBase64: String
    )

    fun hashPassword(password: CharSequence): HashResult {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES)
        random.nextBytes(salt)

        val hash = generateHash(password, salt)

        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashBase64 = Base64.encodeToString(hash, Base64.NO_WRAP)

        return HashResult(saltBase64, hashBase64)
    }

    fun verifyPassword(
        password: CharSequence,
        storedSaltBase64: String,
        storedHashBase64: String
    ): Boolean {
        return try {
            val salt = Base64.decode(storedSaltBase64, Base64.NO_WRAP)
            val expectedHash = Base64.decode(storedHashBase64, Base64.NO_WRAP)
            val computedHash = generateHash(password, salt)

            // Comparación en tiempo constante para mitigar ataques de temporización
            MessageDigest.isEqual(computedHash, expectedHash)
        } catch (_: Exception) {
            false
        }
    }

    private fun generateHash(password: CharSequence, salt: ByteArray): ByteArray {
        val chars = CharArray(password.length)
        for (i in password.indices) {
            chars[i] = password[i]
        }

        try {
            val spec = PBEKeySpec(chars, salt, ITERATION_COUNT, KEY_LENGTH_BITS)
            val factory = SecretKeyFactory.getInstance(ALGORITHM)
            return factory.generateSecret(spec).encoded
        } finally {
            // Limpieza en memoria del array de caracteres sensible
            chars.fill('\u0000')
        }
    }
}

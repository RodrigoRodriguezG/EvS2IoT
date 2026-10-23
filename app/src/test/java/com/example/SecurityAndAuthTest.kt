package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cl.inacap.piscicultura.data.LoginResult
import cl.inacap.piscicultura.data.RegisterResult
import cl.inacap.piscicultura.data.UserRepository
import cl.inacap.piscicultura.security.PasswordHasher
import cl.inacap.piscicultura.security.SecureStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecurityAndAuthTest {

    private lateinit var secureStore: SecureStore
    private lateinit var userRepository: UserRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        secureStore = SecureStore(context)
        secureStore.clear()
        userRepository = UserRepository(secureStore)
    }

    @Test
    fun testPasswordHasherGeneratesSaltAndHashAndVerifiesCorrectly() {
        val password = "SalmonSecurePass2026"
        val hashResult = PasswordHasher.hashPassword(password)

        assertNotNull(hashResult.saltBase64)
        assertNotNull(hashResult.hashBase64)
        assertTrue(hashResult.saltBase64.isNotEmpty())
        assertTrue(hashResult.hashBase64.isNotEmpty())

        // Verificación con password correcto
        val isMatch = PasswordHasher.verifyPassword(password, hashResult.saltBase64, hashResult.hashBase64)
        assertTrue(isMatch)

        // Verificación con password incorrecto
        val isWrong = PasswordHasher.verifyPassword("WrongPassword123", hashResult.saltBase64, hashResult.hashBase64)
        assertFalse(isWrong)
    }

    @Test
    fun testUserRepositoryRegistrationPolicy() = runBlocking {
        // Fallo por menos de 8 caracteres
        val resShort = userRepository.registerUser("operador1", "Short1")
        assertTrue(resShort is RegisterResult.Error)

        // Fallo por no tener números
        val resNoDigit = userRepository.registerUser("operador1", "PasswordSinNumero")
        assertTrue(resNoDigit is RegisterResult.Error)

        // Fallo por no tener letras
        val resNoLetter = userRepository.registerUser("operador1", "1234567890")
        assertTrue(resNoLetter is RegisterResult.Error)

        // Éxito con contraseña válida (8+ caracteres, letra y número)
        val resOk = userRepository.registerUser("operador1", "Salmon2026")
        assertTrue(resOk is RegisterResult.Success)

        // Fallo por usuario duplicado
        val resDup = userRepository.registerUser("operador1", "OtraPassword2026")
        assertTrue(resDup is RegisterResult.Error)
    }

    @Test
    fun testUserRepositoryLoginAndLockoutAfterFiveFailedAttempts() = runBlocking {
        val username = "tecnico_piscicultura"
        val password = "SecurePassword1"
        userRepository.registerUser(username, password)

        // 4 intentos fallidos: deben retornar InvalidCredentials
        for (i in 1..4) {
            val resFail = userRepository.loginUser(username, "BadPassword123")
            assertTrue("Intento $i debió ser InvalidCredentials", resFail is LoginResult.InvalidCredentials)
        }

        // 5to intento fallido: debe bloquear temporalmente por 30s
        val resLocked = userRepository.loginUser(username, "BadPassword123")
        assertTrue("5to intento debió disparar LockedOut", resLocked is LoginResult.LockedOut)

        // Mientras esté bloqueado, incluso con password correcto debe rechazar con LockedOut
        val resWhileLocked = userRepository.loginUser(username, password)
        assertTrue(resWhileLocked is LoginResult.LockedOut)
    }

    @Test
    fun testUserRepositoryActiveSessionAndExpiration() = runBlocking {
        val username = "admin_iot"
        val password = "AdminPassword99"
        userRepository.registerUser(username, password)

        assertFalse(userRepository.isSessionValid())

        // Login exitoso
        val loginRes = userRepository.loginUser(username, password)
        assertTrue(loginRes is LoginResult.Success)
        assertTrue(userRepository.isSessionValid())
        assertEquals(username, userRepository.getActiveUsername())

        // Logout
        userRepository.logout()
        assertFalse(userRepository.isSessionValid())
    }
}

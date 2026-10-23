package cl.inacap.piscicultura

import android.app.Application
import cl.inacap.piscicultura.data.UserRepository
import cl.inacap.piscicultura.security.EncryptedSessionManager
import cl.inacap.piscicultura.security.SecureStore

class PisciculturaApp : Application() {

    val secureStore: SecureStore by lazy {
        SecureStore(this)
    }

    val userRepository: UserRepository by lazy {
        UserRepository(secureStore)
    }

    val sessionManager: EncryptedSessionManager by lazy {
        EncryptedSessionManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: PisciculturaApp
            private set
    }
}

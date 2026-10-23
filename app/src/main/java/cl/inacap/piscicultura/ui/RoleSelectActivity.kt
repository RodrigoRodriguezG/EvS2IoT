package cl.inacap.piscicultura.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import cl.inacap.piscicultura.PisciculturaApp
import cl.inacap.piscicultura.data.repository.AuthRepository
import cl.inacap.piscicultura.security.EncryptedSessionManager
import com.example.databinding.ActivityRoleSelectBinding

class RoleSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleSelectBinding
    private lateinit var sessionManager: EncryptedSessionManager
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoleSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as PisciculturaApp
        sessionManager = app.sessionManager
        val userRepository = app.userRepository

        val activeUser = userRepository.getActiveUsername() ?: sessionManager.getUserEmail() ?: "Operador Piscicultura"
        binding.tvUserEmail.text = "Sesión activa: $activeUser"

        binding.btnSelectNodo.setOnClickListener {
            sessionManager.saveSelectedRole(EncryptedSessionManager.ROLE_NODO)
            startActivity(Intent(this, NodoActivity::class.java))
        }

        binding.btnSelectEstacion.setOnClickListener {
            sessionManager.saveSelectedRole(EncryptedSessionManager.ROLE_ESTACION)
            startActivity(Intent(this, EstacionActivity::class.java))
        }

        binding.btnLogout.setOnClickListener {
            userRepository.logout()
            sessionManager.clearSession()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}

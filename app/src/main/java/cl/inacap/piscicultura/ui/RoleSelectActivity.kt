package cl.inacap.piscicultura.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cl.inacap.piscicultura.PisciculturaApp
import cl.inacap.piscicultura.bluetooth.PermissionHelper
import cl.inacap.piscicultura.data.UserRepository
import cl.inacap.piscicultura.security.EncryptedSessionManager
import com.example.databinding.ActivityRoleSelectBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Pantalla de Selección de Rol (Nodo Sensor ESP32 o Estación de Monitoreo).
 * Gestiona el flujo seguro de permisos Bluetooth en runtime y activación del adaptador.
 * Garantiza que la aplicación nunca se cierre ante denegaciones de permisos.
 */
class RoleSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleSelectBinding
    private lateinit var sessionManager: EncryptedSessionManager
    private lateinit var userRepository: UserRepository

    private var pendingTargetRole: String? = null

    // Launcher para solicitud de permisos en runtime (Android 12+ y legacy)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            binding.cardPermissionWarning.visibility = View.GONE
            Toast.makeText(this, "Permisos de Bluetooth concedidos.", Toast.LENGTH_SHORT).show()
            val targetRole = pendingTargetRole
            pendingTargetRole = null
            if (targetRole != null) {
                checkBluetoothAndProceed(targetRole)
            } else {
                updateBluetoothStateUI()
            }
        } else {
            pendingTargetRole = null
            binding.cardPermissionWarning.visibility = View.VISIBLE
            Toast.makeText(
                this,
                "Se requieren permisos Bluetooth para sincronizar el sistema acuícola.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Launcher para solicitar encendido del adaptador Bluetooth
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (PermissionHelper.isBluetoothEnabled(this)) {
            binding.cardBluetoothDisabled.visibility = View.GONE
            val targetRole = pendingTargetRole
            pendingTargetRole = null
            if (targetRole != null) {
                navigateToRole(targetRole)
            }
        } else {
            pendingTargetRole = null
            binding.cardBluetoothDisabled.visibility = View.VISIBLE
            Toast.makeText(
                this,
                "El Bluetooth permanece desactivado. Actívalo para la comunicación RFCOMM.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoleSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as PisciculturaApp
        sessionManager = app.sessionManager
        userRepository = app.userRepository

        setupUserInfo()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionAndBluetoothStatus()
    }

    private fun setupUserInfo() {
        val activeUser = userRepository.getActiveUsername()
            ?: sessionManager.getUserEmail()
            ?: "Operador Piscicultura"
        binding.tvUserEmail.text = "Sesión activa: $activeUser"
    }

    private fun setupListeners() {
        // Botón reintentar permisos
        binding.btnRetryPermissions.setOnClickListener {
            requestPermissionsWithRationale(null)
        }

        // Botón abrir ajustes de la aplicación si el usuario marcó "No volver a preguntar"
        binding.btnOpenSettings.setOnClickListener {
            PermissionHelper.openAppSettings(this)
        }

        // Botón activar Bluetooth
        binding.btnEnableBluetooth.setOnClickListener {
            promptEnableBluetooth(null)
        }

        // Selección: Nodo Sensor
        binding.btnSelectNodo.setOnClickListener {
            handleRoleSelection(EncryptedSessionManager.ROLE_NODO)
        }

        // Selección: Estación de Monitoreo
        binding.btnSelectEstacion.setOnClickListener {
            handleRoleSelection(EncryptedSessionManager.ROLE_ESTACION)
        }

        // Cierre de sesión seguro
        binding.btnLogout.setOnClickListener {
            userRepository.logout()
            sessionManager.clearSession()
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }

    private fun handleRoleSelection(role: String) {
        // 1. Validar presencia de hardware Bluetooth
        if (!PermissionHelper.isBluetoothSupported(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Hardware no disponible")
                .setMessage("Este dispositivo no cuenta con hardware Bluetooth. No es posible ejecutar el enlace RFCOMM.")
                .setPositiveButton("Aceptar", null)
                .show()
            return
        }

        // 2. Validar permisos en tiempo de ejecución
        if (!PermissionHelper.hasPermissions(this)) {
            requestPermissionsWithRationale(role)
            return
        }

        // 3. Validar estado del adaptador Bluetooth
        checkBluetoothAndProceed(role)
    }

    private fun checkBluetoothAndProceed(role: String) {
        if (!PermissionHelper.isBluetoothEnabled(this)) {
            promptEnableBluetooth(role)
        } else {
            navigateToRole(role)
        }
    }

    private fun requestPermissionsWithRationale(targetRole: String?) {
        pendingTargetRole = targetRole

        if (PermissionHelper.shouldShowRationale(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Permisos de Bluetooth Necesarios")
                .setMessage(
                    "El sistema IoT de monitoreo de piscicultura requiere permisos de Bluetooth para sincronizar las lecturas de los sensores (temperatura y turbidez) y el estado del aireador de oxígeno entre los dos dispositivos mediante RFCOMM.\n\nNo se comparte ni almacena información de ubicación personal."
                )
                .setPositiveButton("Continuar") { dialog, _ ->
                    dialog.dismiss()
                    permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
                }
                .setNegativeButton("Cancelar") { dialog, _ ->
                    dialog.dismiss()
                    pendingTargetRole = null
                    binding.cardPermissionWarning.visibility = View.VISIBLE
                }
                .setCancelable(false)
                .show()
        } else {
            permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
        }
    }

    private fun promptEnableBluetooth(targetRole: String?) {
        pendingTargetRole = targetRole
        try {
            enableBluetoothLauncher.launch(PermissionHelper.createEnableBluetoothIntent())
        } catch (_: SecurityException) {
            Toast.makeText(
                this,
                "Error de seguridad: permisos Bluetooth insuficientes para activar el adaptador.",
                Toast.LENGTH_SHORT
            ).show()
            binding.cardBluetoothDisabled.visibility = View.VISIBLE
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "No se pudo solicitar la activación de Bluetooth. Por favor actívalo en los ajustes.",
                Toast.LENGTH_SHORT
            ).show()
            binding.cardBluetoothDisabled.visibility = View.VISIBLE
        }
    }

    private fun navigateToRole(role: String) {
        sessionManager.saveSelectedRole(role)
        when (role) {
            EncryptedSessionManager.ROLE_NODO -> {
                startActivity(Intent(this, NodoActivity::class.java))
            }
            EncryptedSessionManager.ROLE_ESTACION -> {
                startActivity(Intent(this, EstacionActivity::class.java))
            }
        }
    }

    private fun updatePermissionAndBluetoothStatus() {
        val hasPermissions = PermissionHelper.hasPermissions(this)
        if (hasPermissions) {
            binding.cardPermissionWarning.visibility = View.GONE
            updateBluetoothStateUI()
        } else {
            binding.cardPermissionWarning.visibility = View.VISIBLE
            binding.cardBluetoothDisabled.visibility = View.GONE
        }
    }

    private fun updateBluetoothStateUI() {
        if (!PermissionHelper.isBluetoothSupported(this)) {
            binding.cardBluetoothDisabled.visibility = View.GONE
            return
        }

        val isEnabled = PermissionHelper.isBluetoothEnabled(this)
        if (isEnabled) {
            binding.cardBluetoothDisabled.visibility = View.GONE
        } else {
            binding.cardBluetoothDisabled.visibility = View.VISIBLE
        }
    }
}

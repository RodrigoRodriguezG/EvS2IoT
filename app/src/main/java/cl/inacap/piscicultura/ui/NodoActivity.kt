package cl.inacap.piscicultura.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cl.inacap.piscicultura.bluetooth.BluetoothPermissionHelper
import cl.inacap.piscicultura.bluetooth.ServerConnectionState
import cl.inacap.piscicultura.domain.ModoAireador
import cl.inacap.piscicultura.domain.PiscicultureState
import com.example.R
import com.example.databinding.ActivityNodoBinding
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Pantalla de control del NODO SENSOR (Simulador ESP32).
 * - Rol visible "NODO SENSOR" (chip).
 * - Estado de conexión RFCOMM (Esperando estación / Conectado a <nombre>).
 * - Sliders interactivos de Temperatura (0-40 °C) y Turbidez (0-100 %).
 * - Tarjeta de Aireador con indicador "ACTIVADO" en rojo / "APAGADO" en verde.
 * - Controles de servidor Bluetooth (Iniciar / Detener / Reiniciar).
 * - Registro dinámico de eventos.
 * - Preservación de estado y sliders ante rotación de pantalla.
 * - Cierre seguro de sockets al salir de la pantalla.
 */
class NodoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNodoBinding
    private val viewModel: NodoViewModel by viewModels()

    private var isUpdatingSlidersProgrammatically = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.startServer()
        } else {
            Toast.makeText(
                this,
                "Permisos Bluetooth no concedidos. El nodo funcionará en modo simulación local.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNodoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        observeState()
        checkBluetoothPermissions()
    }

    private fun checkBluetoothPermissions() {
        if (!BluetoothPermissionHelper.hasPermissions(this)) {
            permissionLauncher.launch(BluetoothPermissionHelper.getRequiredPermissions())
        }
    }

    private fun setupListeners() {
        // Volver a la pantalla de selección de rol
        binding.btnChangeRole.setOnClickListener {
            finish()
        }

        // Control iniciar / detener servidor
        binding.btnToggleServer.setOnClickListener {
            viewModel.toggleServer()
        }

        binding.btnRestartServer.setOnClickListener {
            viewModel.startServer()
        }

        // Slider Temperatura (0 - 40 °C)
        binding.sliderTemp.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdatingSlidersProgrammatically) {
                viewModel.setTemperatura(value)
            }
        }

        // Slider Turbidez (0 - 100 %)
        binding.sliderTurb.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdatingSlidersProgrammatically) {
                viewModel.setTurbidez(value)
            }
        }

        // Modos de Aireador (AUTO, ON, OFF)
        binding.btnAeratorAuto.setOnClickListener {
            viewModel.setModoAireador(ModoAireador.AUTO)
        }

        binding.btnAeratorOn.setOnClickListener {
            viewModel.setModoAireador(ModoAireador.ON)
        }

        binding.btnAeratorOff.setOnClickListener {
            viewModel.setModoAireador(ModoAireador.OFF)
        }

        // Limpiar log de eventos
        binding.btnClearLog.setOnClickListener {
            viewModel.clearLogs()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Estado del estanque y actuadores
                launch {
                    viewModel.piscicultureState.collect { state ->
                        updatePiscicultureUi(state)
                    }
                }

                // Estado de la conexión Bluetooth
                launch {
                    viewModel.connectionState.collect { connState ->
                        updateConnectionUi(connState)
                    }
                }

                // Log de eventos
                launch {
                    viewModel.eventLogs.collect { logs ->
                        binding.tvEventLog.text = if (logs.isEmpty()) {
                            "Sin eventos recientes..."
                        } else {
                            logs.joinToString("\n")
                        }
                    }
                }

                // Último JSON enviado
                launch {
                    viewModel.lastSentJson.collect { json ->
                        binding.tvJsonDebug.text = "Último DATA transmitido:\n${json.ifEmpty { "(Esperando ciclo)" }}"
                    }
                }
            }
        }
    }

    private fun updatePiscicultureUi(state: PiscicultureState) {
        // Actualizar textos de sliders
        binding.tvTempValue.text = String.format(Locale.US, "%.1f °C", state.temp)
        binding.tvTurbValue.text = String.format(Locale.US, "%.1f %%", state.turbidez)

        // Sincronizar sliders sin disparar eventos redundantes
        isUpdatingSlidersProgrammatically = true
        if (binding.sliderTemp.value != state.temp) {
            binding.sliderTemp.value = state.temp.coerceIn(0.0f, 40.0f)
        }
        if (binding.sliderTurb.value != state.turbidez) {
            binding.sliderTurb.value = state.turbidez.coerceIn(0.0f, 100.0f)
        }
        isUpdatingSlidersProgrammatically = false

        // --- Tarjeta AIREADOR (ACTIVADO en rojo / apagado en verde) ---
        if (state.aireadorActivo) {
            binding.tvAireadorStatusBadge.text = "ACTIVADO"
            binding.tvAireadorStatusBadge.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_active)
            )
            binding.tvAireadorStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.white))
        } else {
            binding.tvAireadorStatusBadge.text = "APAGADO"
            binding.tvAireadorStatusBadge.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_inactive)
            )
            binding.tvAireadorStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.color_background))
        }

        // Indicador de modo del aireador
        when (state.modoAireador) {
            ModoAireador.AUTO -> {
                binding.tvAireadorRule.text = "Modo: AUTO (ON si Temp < 18 o > 26 °C, o Turbidez > 60%)"
                binding.btnAeratorAuto.alpha = 1.0f
                binding.btnAeratorOn.alpha = 0.6f
                binding.btnAeratorOff.alpha = 0.6f
            }
            ModoAireador.ON -> {
                binding.tvAireadorRule.text = "Modo: MANUAL (Forzado ENCENDIDO por operador)"
                binding.btnAeratorAuto.alpha = 0.6f
                binding.btnAeratorOn.alpha = 1.0f
                binding.btnAeratorOff.alpha = 0.6f
            }
            ModoAireador.OFF -> {
                binding.tvAireadorRule.text = "Modo: MANUAL (Forzado APAGADO por operador)"
                binding.btnAeratorAuto.alpha = 0.6f
                binding.btnAeratorOn.alpha = 0.6f
                binding.btnAeratorOff.alpha = 1.0f
            }
        }
    }

    private fun updateConnectionUi(state: ServerConnectionState) {
        when (state) {
            is ServerConnectionState.DESCONECTADO -> {
                binding.viewBtStatusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_text_secondary)
                )
                binding.tvBtServerStatus.text = "Servidor Bluetooth inactivo"
                binding.btnToggleServer.text = "Iniciar servidor Bluetooth"
            }
            is ServerConnectionState.ESPERANDO -> {
                binding.viewBtStatusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_warning)
                )
                binding.tvBtServerStatus.text = "Esperando estación..."
                binding.btnToggleServer.text = "Detener servidor Bluetooth"
            }
            is ServerConnectionState.CONECTADO -> {
                binding.viewBtStatusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_inactive)
                )
                binding.tvBtServerStatus.text = "Conectado a ${state.deviceName}"
                binding.btnToggleServer.text = "Detener servidor Bluetooth"
            }
            is ServerConnectionState.ERROR -> {
                binding.viewBtStatusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_active)
                )
                binding.tvBtServerStatus.text = "Error: ${state.mensaje}"
                binding.btnToggleServer.text = "Iniciar servidor Bluetooth"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            // Cierre seguro de sockets cuando el usuario sale de la actividad
            viewModel.stopServer()
        }
    }
}

package cl.inacap.piscicultura.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
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
import cl.inacap.piscicultura.data.model.SensorData
import com.example.R
import com.example.databinding.ActivityNodoBinding
import kotlinx.coroutines.launch
import java.util.Locale

class NodoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNodoBinding
    private val viewModel: NodoViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.initBluetooth(applicationContext)
        } else {
            Toast.makeText(
                this,
                "Permisos Bluetooth denegados. La simulación local continuará activa pero no se podrá sincronizar vía RFCOMM.",
                Toast.LENGTH_LONG
            ).show()
            // Inicializar simulación local aunque no haya permisos Bluetooth completos
            viewModel.initBluetooth(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNodoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        observeState()
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        if (BluetoothPermissionHelper.hasPermissions(this)) {
            viewModel.initBluetooth(applicationContext)
        } else {
            permissionLauncher.launch(BluetoothPermissionHelper.getRequiredPermissions())
        }
    }

    private fun setupListeners() {
        binding.btnChangeRole.setOnClickListener {
            finish()
        }

        binding.btnRestartServer.setOnClickListener {
            viewModel.startServer()
        }

        binding.sliderTemp.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setTemperatura(value)
            }
        }

        binding.sliderTurb.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setTurbidez(value)
            }
        }

        binding.btnToggleManualAerator.setOnClickListener {
            viewModel.toggleModoManualAireador()
        }

        binding.btnManualStateSwitch.setOnClickListener {
            viewModel.toggleAireadorManualState()
        }

        binding.btnForceFeed.setOnClickListener {
            val fed = viewModel.forceFeed()
            if (!fed) {
                Toast.makeText(
                    this,
                    "⚠️ Alimentación pospuesta: El aireador está encendido",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this, "🐟 Ración dispensada con éxito", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.telemetryState.collect { data ->
                        updateTelemetryUi(data)
                    }
                }

                launch {
                    viewModel.connectionState.collect { connState ->
                        updateConnectionUi(connState)
                    }
                }

                launch {
                    viewModel.lastSentJson.collect { json ->
                        binding.tvJsonDebug.text = json
                    }
                }
            }
        }
    }

    private fun updateTelemetryUi(data: SensorData) {
        binding.tvTempValue.text = String.format(Locale.US, "%.1f °C", data.temperatura)
        binding.tvTurbValue.text = String.format(Locale.US, "%.1f %%", data.turbidez)

        // Aireador
        if (data.aireadorEncendido) {
            binding.tvAireadorStatusBadge.text = "ENCENDIDO"
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

        if (data.modoManualAireador) {
            binding.tvAireadorRule.text = "Modo: MANUAL (Control manual de operador activo)"
            binding.btnManualStateSwitch.visibility = View.VISIBLE
        } else {
            binding.tvAireadorRule.text = "Lógica automática: ON si Temp < 18 o > 26 °C, o Turbidez > 60%"
            binding.btnManualStateSwitch.visibility = View.GONE
        }

        // Alimentador
        when {
            data.alimentadorActivo -> {
                binding.tvAlimentadorStatusBadge.text = "Dispensando..."
                binding.tvAlimentadorStatusBadge.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_accent)
                )
                binding.tvAlimentadorStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.color_on_primary))
                binding.tvCountdown.text = "¡Dispensando alimento a los salmones!"
            }
            data.alimentadorPospuesto -> {
                binding.tvAlimentadorStatusBadge.text = "Pospuesto (Aireador ON)"
                binding.tvAlimentadorStatusBadge.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_warning)
                )
                binding.tvAlimentadorStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.color_background))
                binding.tvCountdown.text = "Pospuesto: Reintentando cada 2 s hasta apagar aireador"
            }
            else -> {
                binding.tvAlimentadorStatusBadge.text = "En Espera"
                binding.tvAlimentadorStatusBadge.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_surface_variant)
                )
                binding.tvAlimentadorStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
                binding.tvCountdown.text = "Próximo intento en: ${data.segundosRestantesAlimentacion} s"
            }
        }

        binding.tvTotalFeeds.text = "Alimentaciones completadas: ${data.totalAlimentaciones}"
    }

    private fun updateConnectionUi(state: ServerConnectionState) {
        when (state) {
            is ServerConnectionState.Idle -> {
                binding.viewBtStatusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_text_secondary)
                )
                binding.tvBtServerStatus.text = "Servidor Bluetooth inactivo"
            }
            is ServerConnectionState.Listening -> {
                binding.viewBtStatusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_warning)
                )
                binding.tvBtServerStatus.text = "Esperando conexión de Estación (RFCOMM)..."
            }
            is ServerConnectionState.Connected -> {
                binding.viewBtStatusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_inactive)
                )
                binding.tvBtServerStatus.text = "Conectado a: ${state.deviceName} (${state.deviceAddress})"
            }
            is ServerConnectionState.Error -> {
                binding.viewBtStatusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_active)
                )
                binding.tvBtServerStatus.text = state.message
            }
        }
    }
}

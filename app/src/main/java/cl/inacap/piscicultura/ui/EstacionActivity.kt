package cl.inacap.piscicultura.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import cl.inacap.piscicultura.bluetooth.BluetoothPermissionHelper
import cl.inacap.piscicultura.bluetooth.ClientConnectionState
import cl.inacap.piscicultura.data.model.SensorData
import com.example.R
import com.example.databinding.ActivityEstacionBinding
import com.example.databinding.DialogDeviceListBinding
import kotlinx.coroutines.launch
import java.util.Locale

class EstacionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEstacionBinding
    private val viewModel: EstacionViewModel by viewModels()

    private var deviceDialog: AlertDialog? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            showPairedDevicesDialog()
        } else {
            Toast.makeText(
                this,
                "Se requieren permisos Bluetooth para buscar y conectarse al Nodo Sensor.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEstacionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        observeState()
    }

    private fun setupListeners() {
        binding.btnStationChangeRole.setOnClickListener {
            finish()
        }

        binding.btnConnectBt.setOnClickListener {
            if (BluetoothPermissionHelper.hasPermissions(this)) {
                showPairedDevicesDialog()
            } else {
                permissionLauncher.launch(BluetoothPermissionHelper.getRequiredPermissions())
            }
        }

        binding.btnDisconnectBt.setOnClickListener {
            viewModel.disconnect()
        }

        binding.btnCommandForceFeed.setOnClickListener {
            viewModel.sendForceFeedCommand()
            Toast.makeText(this, "Enviando comando de alimentación forzada...", Toast.LENGTH_SHORT).show()
        }

        binding.btnCommandToggleAerator.setOnClickListener {
            viewModel.sendToggleAeratorCommand()
            Toast.makeText(this, "Enviando comando para alternar aireador...", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevicesDialog() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager?.adapter

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Por favor activa el Bluetooth en tu dispositivo.", Toast.LENGTH_LONG).show()
            return
        }

        val pairedDevices: Set<BluetoothDevice> = try {
            adapter.bondedDevices ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }

        val dialogBinding = DialogDeviceListBinding.inflate(LayoutInflater.from(this))
        val builder = AlertDialog.Builder(this)
            .setView(dialogBinding.root)

        val dialog = builder.create()
        deviceDialog = dialog

        dialogBinding.btnCancelDialog.setOnClickListener {
            dialog.dismiss()
        }

        if (pairedDevices.isEmpty()) {
            dialogBinding.tvEmptyDevices.visibility = View.VISIBLE
            dialogBinding.rvDevices.visibility = View.GONE
        } else {
            dialogBinding.tvEmptyDevices.visibility = View.GONE
            dialogBinding.rvDevices.visibility = View.VISIBLE
            dialogBinding.rvDevices.layoutManager = LinearLayoutManager(this)

            val deviceList = pairedDevices.toList()
            val listAdapter = DeviceListAdapter(deviceList) { selectedDevice ->
                dialog.dismiss()
                viewModel.connect(selectedDevice)
            }
            dialogBinding.rvDevices.adapter = listAdapter
        }

        dialog.show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionUi(state)
                    }
                }

                launch {
                    viewModel.telemetry.collect { data ->
                        if (data != null) {
                            updateTelemetryUi(data)
                        }
                    }
                }

                launch {
                    viewModel.lastSyncTime.collect { time ->
                        if (time > 0) {
                            binding.tvLastSync.text = "Sincronizado"
                        }
                    }
                }
            }
        }
    }

    private fun updateConnectionUi(state: ClientConnectionState) {
        when (state) {
            is ClientConnectionState.Disconnected -> {
                binding.viewClientBtDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_active)
                )
                binding.tvClientBtStatus.text = "Desconectado"
                binding.btnConnectBt.isEnabled = true
                binding.btnDisconnectBt.isEnabled = false
                binding.btnCommandForceFeed.isEnabled = false
                binding.btnCommandToggleAerator.isEnabled = false
            }
            is ClientConnectionState.Connecting -> {
                binding.viewClientBtDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_warning)
                )
                binding.tvClientBtStatus.text = "Conectando a ${state.deviceName}..."
                binding.btnConnectBt.isEnabled = false
                binding.btnDisconnectBt.isEnabled = true
                binding.btnCommandForceFeed.isEnabled = false
                binding.btnCommandToggleAerator.isEnabled = false
            }
            is ClientConnectionState.Connected -> {
                binding.viewClientBtDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_inactive)
                )
                binding.tvClientBtStatus.text = "Conectado: ${state.deviceName}"
                binding.btnConnectBt.isEnabled = false
                binding.btnDisconnectBt.isEnabled = true
                binding.btnCommandForceFeed.isEnabled = true
                binding.btnCommandToggleAerator.isEnabled = true
            }
            is ClientConnectionState.Error -> {
                binding.viewClientBtDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_active)
                )
                binding.tvClientBtStatus.text = "Error: ${state.message}"
                binding.btnConnectBt.isEnabled = true
                binding.btnDisconnectBt.isEnabled = false
                binding.btnCommandForceFeed.isEnabled = false
                binding.btnCommandToggleAerator.isEnabled = false
            }
        }
    }

    private fun updateTelemetryUi(data: SensorData) {
        // Temperatura
        binding.tvStationTemp.text = String.format(Locale.US, "%.1f °C", data.temperatura)
        if (data.temperatura < 18.0f || data.temperatura > 26.0f) {
            binding.tvTempRangeStatus.text = "⚠️ Fuera de rango óptimo (18 - 26 °C)"
            binding.tvTempRangeStatus.setTextColor(ContextCompat.getColor(this, R.color.color_active))
        } else {
            binding.tvTempRangeStatus.text = "✓ Rango óptimo (18 - 26 °C)"
            binding.tvTempRangeStatus.setTextColor(ContextCompat.getColor(this, R.color.color_inactive))
        }

        // Turbidez
        binding.tvStationTurb.text = String.format(Locale.US, "%.1f %%", data.turbidez)
        if (data.turbidez > 60.0f) {
            binding.tvTurbRangeStatus.text = "⚠️ Turbidez crítica (> 60 %)"
            binding.tvTurbRangeStatus.setTextColor(ContextCompat.getColor(this, R.color.color_active))
        } else {
            binding.tvTurbRangeStatus.text = "✓ Nivel de agua claro (≤ 60 %)"
            binding.tvTurbRangeStatus.setTextColor(ContextCompat.getColor(this, R.color.color_inactive))
        }

        // Aireador
        if (data.aireadorEncendido) {
            binding.tvStationAireadorBadge.text = "ENCENDIDO"
            binding.tvStationAireadorBadge.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_active)
            )
            binding.tvStationAireadorBadge.setTextColor(ContextCompat.getColor(this, R.color.white))
        } else {
            binding.tvStationAireadorBadge.text = "APAGADO"
            binding.tvStationAireadorBadge.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_inactive)
            )
            binding.tvStationAireadorBadge.setTextColor(ContextCompat.getColor(this, R.color.color_background))
        }

        if (data.modoManualAireador) {
            binding.tvStationAireadorMode.text = "Modo: MANUAL (Control manual de operador)"
        } else {
            binding.tvStationAireadorMode.text = "Modo: Automático (Control por sensores OT)"
        }

        // Alimentador
        when {
            data.alimentadorActivo -> {
                binding.tvStationFeederBadge.text = "Dispensando..."
                binding.tvStationFeederBadge.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_accent)
                )
                binding.tvStationFeederBadge.setTextColor(ContextCompat.getColor(this, R.color.color_on_primary))
                binding.tvStationCountdown.text = "¡Dispensando ración ahora!"
                binding.tvStationFeederConflict.visibility = View.GONE
            }
            data.alimentadorPospuesto -> {
                binding.tvStationFeederBadge.text = "Pospuesto"
                binding.tvStationFeederBadge.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_warning)
                )
                binding.tvStationFeederBadge.setTextColor(ContextCompat.getColor(this, R.color.color_background))
                binding.tvStationCountdown.text = "Reintentando cada 2 s"
                binding.tvStationFeederConflict.visibility = View.VISIBLE
                binding.tvStationFeederConflict.text = "⚠️ Alimentación pospuesta: El aireador está encendido"
            }
            else -> {
                binding.tvStationFeederBadge.text = "En Espera"
                binding.tvStationFeederBadge.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_surface_variant)
                )
                binding.tvStationFeederBadge.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
                binding.tvStationCountdown.text = "Próxima ración en: ${data.segundosRestantesAlimentacion} s"
                binding.tvStationFeederConflict.visibility = View.GONE
            }
        }

        binding.tvStationTotalFeeds.text = "Raciones entregadas: ${data.totalAlimentaciones}"
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceDialog?.dismiss()
    }
}

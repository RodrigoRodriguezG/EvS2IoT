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
import android.widget.ArrayAdapter
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
import cl.inacap.piscicultura.domain.ProtocolMessage
import com.example.R
import com.example.databinding.ActivityEstacionBinding
import com.example.databinding.DialogDeviceListBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Pantalla de la ESTACIÓN CENTRAL DE MONITOREO.
 * - Encabezado con ROL VISIBLE "ESTACIÓN" (chip).
 * - Selector de dispositivo emparejado (dropdown reactivo y diálogo de selección).
 * - Indicadores visuales de estado de conexión (color, texto y puntos de enlace).
 * - Dashboard con tarjetas Material:
 *     1) Temperatura (°C) con alerta roja si está fuera del rango óptimo (18 - 26 °C).
 *     2) Turbidez (%) con alerta roja si excede el límite crítico (> 60 %).
 *     3) Aireador con badge (ACTIVADO en rojo / APAGADO en verde) y selector de modos (AUTO / ON / OFF).
 * - Indicador dinámico de frescura: "Última actualización hace X s", alerta si > 6 s sin telemetría.
 * - Tolerancia a fallos: ante desconexión forzada no crashea, muestra Snackbar con acción "Reconectar",
 *   mantiene la UI estable y cierra sockets de forma segura.
 * - Soporte total de rotación de pantalla sin perder datos ni conexión activa.
 * - Toda la UI observa StateFlows mediante repeatOnLifecycle.
 */
class EstacionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEstacionBinding
    private val viewModel: EstacionViewModel by viewModels()

    private var deviceDialog: AlertDialog? = null
    private var pairedDevicesList: List<BluetoothDevice> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.refreshBondedDevices()
            Toast.makeText(this, "Permisos concedidos. Conectando al nodo...", Toast.LENGTH_SHORT).show()
            viewModel.connect()
        } else {
            Snackbar.make(
                binding.root,
                "Se requieren permisos Bluetooth para sincronizar con el Nodo Sensor.",
                Snackbar.LENGTH_LONG
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
        // Volver a selección de rol
        binding.btnStationChangeRole.setOnClickListener {
            finish()
        }

        // Selección de dispositivo desde el Dropdown
        binding.actvPairedDevices.setOnItemClickListener { _, _, position, _ ->
            if (position in pairedDevicesList.indices) {
                viewModel.selectDevice(pairedDevicesList[position])
            }
        }

        // Botón Conectar
        binding.btnConnectBt.setOnClickListener {
            handleConnectRequest()
        }

        // Botón Desconectar
        binding.btnDisconnectBt.setOnClickListener {
            viewModel.disconnect()
            Toast.makeText(this, "Desconectado del nodo sensor.", Toast.LENGTH_SHORT).show()
        }

        // Botón abrir diálogo alternativo de dispositivos
        binding.btnChooseDeviceDialog.setOnClickListener {
            showPairedDevicesDialog()
        }

        // Botones de Modo Aireador (AUTO, ON, OFF)
        binding.btnAireadorAuto.setOnClickListener {
            viewModel.setAireador(ProtocolMessage.Cmd.VALUE_AUTO)
            Toast.makeText(this, "Enviando comando: Aireador en AUTO", Toast.LENGTH_SHORT).show()
        }

        binding.btnAireadorOn.setOnClickListener {
            viewModel.setAireador(ProtocolMessage.Cmd.VALUE_ON)
            Toast.makeText(this, "Enviando comando: Aireador ENCENDIDO", Toast.LENGTH_SHORT).show()
        }

        binding.btnAireadorOff.setOnClickListener {
            viewModel.setAireador(ProtocolMessage.Cmd.VALUE_OFF)
            Toast.makeText(this, "Enviando comando: Aireador APAGADO", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Verifica permisos y activa conexión al dispositivo seleccionado.
     */
    private fun handleConnectRequest() {
        if (!BluetoothPermissionHelper.hasPermissions(this)) {
            permissionLauncher.launch(BluetoothPermissionHelper.getRequiredPermissions())
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager?.adapter

        if (adapter == null || !adapter.isEnabled) {
            Snackbar.make(
                binding.root,
                "Por favor active el Bluetooth del teléfono para conectar.",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        viewModel.connect()
    }

    /**
     * Observa reactivamente los flujos del ViewModel en Lifecycle.State.STARTED.
     */
    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 1) Estado de conexión RFCOMM
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionUi(state)
                    }
                }

                // 2) Lista de dispositivos emparejados
                launch {
                    viewModel.bondedDevices.collect { devices ->
                        updatePairedDevicesDropdown(devices)
                    }
                }

                // 3) Dispositivo seleccionado
                launch {
                    viewModel.selectedDevice.collect { device ->
                        if (device != null) {
                            val label = formatDeviceLabel(device)
                            if (binding.actvPairedDevices.text.toString() != label) {
                                binding.actvPairedDevices.setText(label, false)
                            }
                        }
                    }
                }

                // 4) Telemetría periódica recibida del nodo
                launch {
                    viewModel.telemetry.collect { data ->
                        if (data != null) {
                            updateTelemetryUi(data)
                        }
                    }
                }

                // 5) Indicador de última sincronización
                launch {
                    viewModel.secondsSinceLastSync.collect { elapsed ->
                        if (viewModel.telemetry.value != null) {
                            binding.tvLastSync.text = "Última actualización: hace $elapsed s"
                        } else {
                            binding.tvLastSync.text = "Sin datos aún"
                        }
                    }
                }

                // 6) Alerta de datos desactualizados (> 6 s)
                launch {
                    viewModel.isStale.collect { stale ->
                        binding.tvStaleWarning.visibility = if (stale) View.VISIBLE else View.GONE
                    }
                }

                // 7) Eventos de Snackbar (errores, caídas de conexión y confirmaciones)
                launch {
                    viewModel.snackbarEvent.collect { message ->
                        showRobustSnackbar(message)
                    }
                }
            }
        }
    }

    /**
     * Muestra Snackbar tolerante a fallos con acción de reconexión manual.
     */
    private fun showRobustSnackbar(message: String) {
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        if (message.contains("Desconexión", ignoreCase = true) ||
            message.contains("perdida", ignoreCase = true) ||
            message.contains("error", ignoreCase = true)
        ) {
            snackbar.setAction("Reconectar") {
                viewModel.reconectar()
            }
        }
        snackbar.show()
    }

    /**
     * Actualiza el selector dropdown de dispositivos Bluetooth emparejados.
     */
    private fun updatePairedDevicesDropdown(devices: List<BluetoothDevice>) {
        pairedDevicesList = devices
        val labels = devices.map { formatDeviceLabel(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.actvPairedDevices.setAdapter(adapter)

        val currentSelected = viewModel.selectedDevice.value
        if (currentSelected != null) {
            binding.actvPairedDevices.setText(formatDeviceLabel(currentSelected), false)
        } else if (devices.isNotEmpty()) {
            binding.actvPairedDevices.setText(formatDeviceLabel(devices.first()), false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun formatDeviceLabel(device: BluetoothDevice): String {
        val name = try {
            device.name ?: "Dispositivo Bluetooth"
        } catch (_: Exception) {
            "Dispositivo Bluetooth"
        }
        return "$name (${device.address})"
    }

    /**
     * Modifica los controles e indicadores de enlace según el estado actual.
     */
    private fun updateConnectionUi(state: ClientConnectionState) {
        when (state) {
            is ClientConnectionState.DESCONECTADO -> {
                binding.viewClientBtDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_outline)
                )
                binding.tvClientBtStatus.text = "Desconectado"
                binding.tvClientBtStatus.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary))

                binding.btnConnectBt.isEnabled = true
                binding.btnDisconnectBt.isEnabled = false
                setControlButtonsEnabled(false)
            }
            is ClientConnectionState.CONECTANDO -> {
                binding.viewClientBtDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_warning)
                )
                binding.tvClientBtStatus.text = "Conectando a ${state.deviceName}..."
                binding.tvClientBtStatus.setTextColor(ContextCompat.getColor(this, R.color.color_warning))

                binding.btnConnectBt.isEnabled = false
                binding.btnDisconnectBt.isEnabled = true
                setControlButtonsEnabled(false)
            }
            is ClientConnectionState.CONECTADO -> {
                binding.viewClientBtDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_inactive)
                )
                binding.tvClientBtStatus.text = "Conectado a: ${state.deviceName}"
                binding.tvClientBtStatus.setTextColor(ContextCompat.getColor(this, R.color.color_inactive))

                binding.btnConnectBt.isEnabled = false
                binding.btnDisconnectBt.isEnabled = true
                setControlButtonsEnabled(true)
            }
            is ClientConnectionState.ERROR -> {
                binding.viewClientBtDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.color_active)
                )
                binding.tvClientBtStatus.text = "Error: ${state.mensaje}"
                binding.tvClientBtStatus.setTextColor(ContextCompat.getColor(this, R.color.color_active))

                binding.btnConnectBt.isEnabled = true
                binding.btnDisconnectBt.isEnabled = false
                setControlButtonsEnabled(false)
            }
        }
    }

    private fun setControlButtonsEnabled(enabled: Boolean) {
        binding.btnAireadorAuto.isEnabled = enabled
        binding.btnAireadorOn.isEnabled = enabled
        binding.btnAireadorOff.isEnabled = enabled
    }

    /**
     * Procesa los datos telemétricos del sensor y aplica los estilos de las tarjetas.
     */
    private fun updateTelemetryUi(data: ProtocolMessage.Data) {
        // --- 1) Tarjeta Temperatura (°C) ---
        binding.tvStationTemp.text = String.format(Locale.US, "%.1f °C", data.temp)
        val isTempOutOfRange = data.temp < 18.0f || data.temp > 26.0f

        if (isTempOutOfRange) {
            binding.tvStationTemp.setTextColor(ContextCompat.getColor(this, R.color.color_active))
            binding.cardStationTemp.strokeColor = ContextCompat.getColor(this, R.color.color_active)
            binding.tvTempRangeStatus.text = "⚠️ Fuera de rango óptimo (18 - 26 °C)"
            binding.tvTempRangeStatus.setTextColor(ContextCompat.getColor(this, R.color.color_active))
        } else {
            binding.tvStationTemp.setTextColor(ContextCompat.getColor(this, R.color.color_accent))
            binding.cardStationTemp.strokeColor = ContextCompat.getColor(this, R.color.color_outline)
            binding.tvTempRangeStatus.text = "✓ Rango óptimo (18 - 26 °C)"
            binding.tvTempRangeStatus.setTextColor(ContextCompat.getColor(this, R.color.color_inactive))
        }

        // --- 2) Tarjeta Turbidez (%) ---
        binding.tvStationTurb.text = String.format(Locale.US, "%.1f %%", data.turb)
        val isTurbCritical = data.turb > 60.0f

        if (isTurbCritical) {
            binding.tvStationTurb.setTextColor(ContextCompat.getColor(this, R.color.color_active))
            binding.cardStationTurb.strokeColor = ContextCompat.getColor(this, R.color.color_active)
            binding.tvTurbRangeStatus.text = "⚠️ Turbidez crítica (> 60 %)"
            binding.tvTurbRangeStatus.setTextColor(ContextCompat.getColor(this, R.color.color_active))
        } else {
            binding.tvStationTurb.setTextColor(ContextCompat.getColor(this, R.color.color_accent))
            binding.cardStationTurb.strokeColor = ContextCompat.getColor(this, R.color.color_outline)
            binding.tvTurbRangeStatus.text = "✓ Nivel claro (≤ 60 %)"
            binding.tvTurbRangeStatus.setTextColor(ContextCompat.getColor(this, R.color.color_inactive))
        }

        // --- 3) Tarjeta Aireador ---
        if (data.aireador) {
            binding.tvStationAireadorBadge.text = "ACTIVADO"
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

        if (data.manual) {
            binding.tvStationAireadorMode.text = "Modo: MANUAL (Control manual de operador)"
        } else {
            binding.tvStationAireadorMode.text = "Modo: Automático (Control por sensores OT)"
        }
    }

    /**
     * Muestra diálogo con la lista de dispositivos Bluetooth emparejados.
     */
    @SuppressLint("MissingPermission")
    private fun showPairedDevicesDialog() {
        if (!BluetoothPermissionHelper.hasPermissions(this)) {
            permissionLauncher.launch(BluetoothPermissionHelper.getRequiredPermissions())
            return
        }

        val pairedDevices = viewModel.bondedDevices.value
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

            val listAdapter = DeviceListAdapter(pairedDevices) { selectedDevice ->
                dialog.dismiss()
                viewModel.selectDevice(selectedDevice)
                viewModel.connect(selectedDevice)
            }
            dialogBinding.rvDevices.adapter = listAdapter
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceDialog?.dismiss()
        deviceDialog = null
    }
}

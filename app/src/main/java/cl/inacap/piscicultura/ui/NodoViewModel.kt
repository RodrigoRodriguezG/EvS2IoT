package cl.inacap.piscicultura.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.inacap.piscicultura.bluetooth.BluetoothServerManager
import cl.inacap.piscicultura.bluetooth.ServerConnectionState
import cl.inacap.piscicultura.data.model.CommandMessage
import cl.inacap.piscicultura.data.model.SensorData
import cl.inacap.piscicultura.domain.NodoLogicController
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NodoViewModel : ViewModel() {

    private val gson = Gson()

    private var serverManager: BluetoothServerManager? = null
    private var logicController: NodoLogicController? = null

    private val _telemetryState = MutableStateFlow(SensorData())
    val telemetryState: StateFlow<SensorData> = _telemetryState.asStateFlow()

    private val _connectionState = MutableStateFlow<ServerConnectionState>(ServerConnectionState.Idle)
    val connectionState: StateFlow<ServerConnectionState> = _connectionState.asStateFlow()

    private val _lastSentJson = MutableStateFlow("")
    val lastSentJson: StateFlow<String> = _lastSentJson.asStateFlow()

    private var isInitialized = false

    fun initBluetooth(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager?.adapter

        serverManager = BluetoothServerManager(adapter, viewModelScope)

        logicController = NodoLogicController(viewModelScope) { currentTelemetry ->
            _telemetryState.value = currentTelemetry
            val json = gson.toJson(currentTelemetry)
            _lastSentJson.value = json
            viewModelScope.launch {
                serverManager?.sendJsonLine(json)
            }
        }

        // Observar estado de conexión del servidor
        serverManager?.let { sm ->
            viewModelScope.launch {
                sm.connectionState.collect { state ->
                    _connectionState.value = state
                }
            }

            // Escuchar comandos remotos enviados desde la Estación
            viewModelScope.launch {
                sm.incomingCommands.collect { jsonCommand ->
                    handleIncomingCommand(jsonCommand)
                }
            }
        }

        logicController?.start()
        startServer()
    }

    fun startServer() {
        serverManager?.startListening()
    }

    fun setTemperatura(temp: Float) {
        logicController?.setTemperatura(temp)
    }

    fun setTurbidez(turb: Float) {
        logicController?.setTurbidez(turb)
    }

    fun toggleModoManualAireador() {
        logicController?.toggleModoManualAireador()
    }

    fun toggleAireadorManualState() {
        val current = _telemetryState.value.aireadorEncendido
        logicController?.setAireadorManualState(!current)
    }

    fun forceFeed(): Boolean {
        return logicController?.forceFeed() ?: false
    }

    private fun handleIncomingCommand(json: String) {
        try {
            val cmd = gson.fromJson(json, CommandMessage::class.java)
            when (cmd.action) {
                CommandMessage.ACTION_FORCE_FEED -> {
                    forceFeed()
                }
                CommandMessage.ACTION_TOGGLE_AERATOR -> {
                    toggleModoManualAireador()
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        super.onCleared()
        logicController?.stop()
        serverManager?.stop()
    }
}

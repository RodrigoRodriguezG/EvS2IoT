package cl.inacap.piscicultura.ui

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.inacap.piscicultura.bluetooth.BluetoothClientManager
import cl.inacap.piscicultura.bluetooth.ClientConnectionState
import cl.inacap.piscicultura.data.model.CommandMessage
import cl.inacap.piscicultura.data.model.SensorData
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EstacionViewModel : ViewModel() {

    private val gson = Gson()
    private val clientManager = BluetoothClientManager(viewModelScope)

    val connectionState: StateFlow<ClientConnectionState> = clientManager.connectionState

    private val _telemetry = MutableStateFlow<SensorData?>(null)
    val telemetry: StateFlow<SensorData?> = _telemetry.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Long>(0L)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    init {
        viewModelScope.launch {
            clientManager.incomingTelemetry.collect { jsonLine ->
                try {
                    val data = gson.fromJson(jsonLine, SensorData::class.java)
                    _telemetry.value = data
                    _lastSyncTime.value = System.currentTimeMillis()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        clientManager.connectToDevice(device)
    }

    fun disconnect() {
        clientManager.disconnect()
    }

    fun sendForceFeedCommand() {
        val command = CommandMessage(action = CommandMessage.ACTION_FORCE_FEED)
        val json = gson.toJson(command)
        viewModelScope.launch {
            clientManager.sendCommand(json)
        }
    }

    fun sendToggleAeratorCommand() {
        val command = CommandMessage(action = CommandMessage.ACTION_TOGGLE_AERATOR)
        val json = gson.toJson(command)
        viewModelScope.launch {
            clientManager.sendCommand(json)
        }
    }

    override fun onCleared() {
        super.onCleared()
        clientManager.disconnect()
    }
}

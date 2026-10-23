package cl.inacap.piscicultura.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

sealed class ClientConnectionState {
    object Disconnected : ClientConnectionState()
    data class Connecting(val deviceName: String) : ClientConnectionState()
    data class Connected(val deviceName: String, val deviceAddress: String) : ClientConnectionState()
    data class Error(val message: String) : ClientConnectionState()
}

class BluetoothClientManager(
    private val scope: CoroutineScope
) {
    private val _connectionState = MutableStateFlow<ClientConnectionState>(ClientConnectionState.Disconnected)
    val connectionState: StateFlow<ClientConnectionState> = _connectionState.asStateFlow()

    private val _incomingTelemetry = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingTelemetry: SharedFlow<String> = _incomingTelemetry.asSharedFlow()

    private var socket: BluetoothSocket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    private var connectJob: Job? = null
    private var lastTargetDevice: BluetoothDevice? = null

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        lastTargetDevice = device
        disconnect()

        connectJob = scope.launch(Dispatchers.IO) {
            val deviceName = try { device.name ?: "Dispositivo" } catch (_: Exception) { "Dispositivo" }
            _connectionState.value = ClientConnectionState.Connecting(deviceName)

            try {
                val clientSocket = device.createRfcommSocketToServiceRecord(BluetoothConstants.SPP_UUID)
                socket = clientSocket

                clientSocket.connect()

                val inStream = clientSocket.inputStream
                val outStream = clientSocket.outputStream

                reader = BufferedReader(InputStreamReader(inStream, Charsets.UTF_8))
                writer = BufferedWriter(OutputStreamWriter(outStream, Charsets.UTF_8))

                _connectionState.value = ClientConnectionState.Connected(deviceName, device.address)

                // Bucle de lectura de telemetría continua
                while (isActive && clientSocket.isConnected) {
                    val line = reader?.readLine() ?: break
                    if (line.isNotBlank()) {
                        _incomingTelemetry.emit(line)
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    _connectionState.value = ClientConnectionState.Error("Conexión perdida o rechazada: ${e.message}")
                }
            } catch (e: Exception) {
                if (isActive) {
                    _connectionState.value = ClientConnectionState.Error("Fallo de conexión Bluetooth: ${e.message}")
                }
            } finally {
                closeResources()
                if (isActive && _connectionState.value is ClientConnectionState.Connected) {
                    _connectionState.value = ClientConnectionState.Disconnected
                }
            }
        }
    }

    suspend fun sendCommand(jsonCommand: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val w = writer
            val s = socket
            if (w != null && s?.isConnected == true) {
                w.write(jsonCommand.trimEnd() + "\n")
                w.flush()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            _connectionState.value = ClientConnectionState.Error("Error al enviar comando: ${e.message}")
            false
        }
    }

    fun reconnect() {
        lastTargetDevice?.let { connectToDevice(it) }
    }

    private fun closeResources() {
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        reader = null
        writer = null
        socket = null
    }

    fun disconnect() {
        connectJob?.cancel()
        closeResources()
        _connectionState.value = ClientConnectionState.Disconnected
    }
}

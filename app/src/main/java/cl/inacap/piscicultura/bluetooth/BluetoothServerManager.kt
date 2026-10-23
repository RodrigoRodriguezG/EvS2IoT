package cl.inacap.piscicultura.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.google.gson.Gson
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

sealed class ServerConnectionState {
    object Idle : ServerConnectionState()
    object Listening : ServerConnectionState()
    data class Connected(val deviceName: String, val deviceAddress: String) : ServerConnectionState()
    data class Error(val message: String) : ServerConnectionState()
}

class BluetoothServerManager(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val scope: CoroutineScope
) {
    private val gson = Gson()

    private val _connectionState = MutableStateFlow<ServerConnectionState>(ServerConnectionState.Idle)
    val connectionState: StateFlow<ServerConnectionState> = _connectionState.asStateFlow()

    private val _incomingCommands = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingCommands: SharedFlow<String> = _incomingCommands.asSharedFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var connectedSocket: BluetoothSocket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    private var acceptJob: Job? = null
    private var readJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _connectionState.value = ServerConnectionState.Error("Bluetooth no está disponible o está desactivado.")
            return
        }

        stop()

        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                _connectionState.value = ServerConnectionState.Listening
                val ss = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                    BluetoothConstants.SERVICE_NAME,
                    BluetoothConstants.SPP_UUID
                )
                serverSocket = ss

                while (isActive) {
                    val socket: BluetoothSocket = try {
                        ss.accept()
                    } catch (e: IOException) {
                        if (!isActive) break
                        _connectionState.value = ServerConnectionState.Error("Servidor cerrado o error de escucha: ${e.message}")
                        break
                    }

                    // Cliente conectado
                    connectedSocket = socket
                    val deviceName = try { socket.remoteDevice?.name ?: "Dispositivo Remoto" } catch (_: Exception) { "Estación" }
                    val deviceAddress = try { socket.remoteDevice?.address ?: "00:00:00:00:00:00" } catch (_: Exception) { "" }

                    _connectionState.value = ServerConnectionState.Connected(deviceName, deviceAddress)

                    handleClientCommunication(socket)
                }
            } catch (e: Exception) {
                _connectionState.value = ServerConnectionState.Error("Error iniciando servidor RFCOMM: ${e.message}")
            }
        }
    }

    private suspend fun handleClientCommunication(socket: BluetoothSocket) = withContext(Dispatchers.IO) {
        try {
            val inStream = socket.inputStream
            val outStream = socket.outputStream

            reader = BufferedReader(InputStreamReader(inStream, Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(outStream, Charsets.UTF_8))

            while (isActive && socket.isConnected) {
                val line = reader?.readLine() ?: break
                if (line.isNotBlank()) {
                    _incomingCommands.emit(line)
                }
            }
        } catch (e: IOException) {
            // Desconexión del cliente
        } finally {
            closeClientResources()
            if (isActive) {
                _connectionState.value = ServerConnectionState.Listening
            }
        }
    }

    suspend fun sendJsonLine(json: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val w = writer
            if (w != null && connectedSocket?.isConnected == true) {
                w.write(json.trimEnd() + "\n")
                w.flush()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            closeClientResources()
            _connectionState.value = ServerConnectionState.Listening
            false
        }
    }

    private fun closeClientResources() {
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { connectedSocket?.close() } catch (_: Exception) {}
        reader = null
        writer = null
        connectedSocket = null
    }

    fun stop() {
        acceptJob?.cancel()
        readJob?.cancel()
        closeClientResources()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        _connectionState.value = ServerConnectionState.Idle
    }
}

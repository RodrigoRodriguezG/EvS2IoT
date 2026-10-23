package cl.inacap.piscicultura.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import cl.inacap.piscicultura.domain.ProtocolMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Estados del servidor Bluetooth RFCOMM (Rol NODO).
 */
sealed class ServerConnectionState {
    object DESCONECTADO : ServerConnectionState()
    object ESPERANDO : ServerConnectionState()
    data class CONECTADO(val deviceName: String, val deviceAddress: String) : ServerConnectionState()
    data class ERROR(val mensaje: String) : ServerConnectionState()
}

/**
 * Servidor Bluetooth RFCOMM para el rol de NODO Sensor.
 * - Utiliza BluetoothServerSocket con listenUsingRfcommWithServiceRecord(nombre, UUID fijo).
 * - Ejecuta accept() en Dispatchers.IO.
 * - Expone el estado de conexión reactivo mediante StateFlow (DESCONECTADO, ESPERANDO, CONECTADO, ERROR).
 * - Delega la gestión de flujo y latidos a ConnectionManager.
 * - Protegido contra SecurityException y fallos de hardware.
 */
class BluetoothServer(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BluetoothServer"
    }

    private val _connectionState = MutableStateFlow<ServerConnectionState>(ServerConnectionState.DESCONECTADO)
    val connectionState: StateFlow<ServerConnectionState> = _connectionState.asStateFlow()

    val connectionManager = ConnectionManager(scope)

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null

    init {
        // Monitorea el estado del connectionManager para sincronizar cuando el peer se desconecta
        scope.launch {
            connectionManager.connectionState.collect { managedState ->
                when (managedState) {
                    is ManagedConnectionState.Desconectado -> {
                        if (acceptJob?.isActive == true && serverSocket != null) {
                            _connectionState.value = ServerConnectionState.ESPERANDO
                        } else if (_connectionState.value !is ServerConnectionState.ERROR) {
                            _connectionState.value = ServerConnectionState.DESCONECTADO
                        }
                    }
                    is ManagedConnectionState.Error -> {
                        _connectionState.value = ServerConnectionState.ERROR(managedState.mensaje)
                    }
                    is ManagedConnectionState.Conectado -> {
                        _connectionState.value = ServerConnectionState.CONECTADO(
                            deviceName = managedState.deviceName,
                            deviceAddress = managedState.deviceAddress
                        )
                    }
                }
            }
        }
    }

    /**
     * Inicia la escucha de conexiones RFCOMM entrantes en segundo plano (Dispatchers.IO).
     */
    @SuppressLint("MissingPermission")
    fun startListening() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _connectionState.value = ServerConnectionState.ERROR("Adaptador Bluetooth no disponible o desactivado.")
            return
        }

        stop()

        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                _connectionState.value = ServerConnectionState.ESPERANDO
                Log.d(TAG, "Iniciando servidor RFCOMM con UUID: ${BluetoothConstants.SPP_UUID}")

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
                        Log.e(TAG, "IOException al aceptar conexión de cliente: ${e.message}")
                        _connectionState.value = ServerConnectionState.ERROR("Error de escucha: ${e.message}")
                        break
                    }

                    // Cliente aceptado
                    val deviceName = try {
                        socket.remoteDevice?.name ?: "Estación Remota"
                    } catch (_: SecurityException) {
                        "Estación"
                    } catch (_: Exception) {
                        "Dispositivo Remoto"
                    }

                    val deviceAddress = try {
                        socket.remoteDevice?.address ?: ""
                    } catch (_: Exception) {
                        ""
                    }

                    Log.d(TAG, "Cliente RFCOMM conectado: $deviceName ($deviceAddress)")
                    _connectionState.value = ServerConnectionState.CONECTADO(deviceName, deviceAddress)

                    // Delegar el socket al ConnectionManager para I/O y latidos seguros
                    connectionManager.attachSocket(socket, deviceName, deviceAddress)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permisos de Bluetooth insuficientes para listenUsingRfcomm: ${e.message}")
                _connectionState.value = ServerConnectionState.ERROR("Permisos de Bluetooth requeridos no concedidos.")
            } catch (e: Exception) {
                Log.e(TAG, "Fallo inesperado al iniciar servidor RFCOMM: ${e.message}")
                _connectionState.value = ServerConnectionState.ERROR("No se pudo iniciar el servidor: ${e.message}")
            }
        }
    }

    /**
     * Envía un mensaje estructurado del protocolo al cliente conectado.
     */
    suspend fun sendMessage(message: ProtocolMessage): Boolean {
        return connectionManager.send(message)
    }

    /**
     * Detiene la escucha y libera todos los sockets asociados.
     */
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        connectionManager.disconnect()

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        _connectionState.value = ServerConnectionState.DESCONECTADO
    }
}

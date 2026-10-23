package cl.inacap.piscicultura.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import cl.inacap.piscicultura.domain.ProtocolMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Estados de la conexión del cliente Bluetooth RFCOMM (Rol ESTACIÓN).
 */
sealed class ClientConnectionState {
    object DESCONECTADO : ClientConnectionState()
    data class CONECTANDO(val deviceName: String) : ClientConnectionState()
    data class CONECTADO(val deviceName: String, val deviceAddress: String) : ClientConnectionState()
    data class ERROR(val mensaje: String) : ClientConnectionState()
}

/**
 * Cliente Bluetooth RFCOMM para el rol de ESTACIÓN de Monitoreo.
 * - Lista dispositivos emparejados (bondedDevices).
 * - Permite elegir un dispositivo y conectarse mediante createRfcommSocketToServiceRecord.
 * - Invoca cancelDiscovery() antes de connect() para asegurar estabilidad y ancho de banda.
 * - Realiza la conexión en Dispatchers.IO con timeout configurable (por defecto 10 s).
 * - Delega la gestión continua de mensajes, latidos y watchdog a ConnectionManager.
 * - Control de excepciones de seguridad (SecurityException) e I/O.
 */
class BluetoothClient(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BluetoothClient"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10000L
    }

    private val _connectionState = MutableStateFlow<ClientConnectionState>(ClientConnectionState.DESCONECTADO)
    val connectionState: StateFlow<ClientConnectionState> = _connectionState.asStateFlow()

    val connectionManager = ConnectionManager(scope)

    private var connectJob: Job? = null
    private var lastTargetDevice: BluetoothDevice? = null

    init {
        // Sincroniza los estados reportados por ConnectionManager
        scope.launch {
            connectionManager.connectionState.collect { managedState ->
                when (managedState) {
                    is ManagedConnectionState.Desconectado -> {
                        if (_connectionState.value !is ClientConnectionState.ERROR) {
                            _connectionState.value = ClientConnectionState.DESCONECTADO
                        }
                    }
                    is ManagedConnectionState.Error -> {
                        _connectionState.value = ClientConnectionState.ERROR(managedState.mensaje)
                    }
                    is ManagedConnectionState.Conectado -> {
                        _connectionState.value = ClientConnectionState.CONECTADO(
                            deviceName = managedState.deviceName,
                            deviceAddress = managedState.deviceAddress
                        )
                    }
                }
            }
        }
    }

    /**
     * Obtiene el conjunto de dispositivos Bluetooth ya emparejados con este teléfono.
     * Retorna una lista vacía si no hay permisos o el adaptador no está disponible.
     */
    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            return emptyList()
        }
        return try {
            bluetoothAdapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al obtener bondedDevices: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error al listar dispositivos emparejados: ${e.message}")
            emptyList()
        }
    }

    /**
     * Inicia la conexión RFCOMM hacia el dispositivo especificado con timeout en Dispatchers.IO.
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice, timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS) {
        lastTargetDevice = device
        disconnect()

        connectJob = scope.launch(Dispatchers.IO) {
            val deviceName = try {
                device.name ?: "Nodo Piscicultura"
            } catch (_: SecurityException) {
                "Nodo Sensor"
            } catch (_: Exception) {
                "Dispositivo"
            }

            _connectionState.value = ClientConnectionState.CONECTANDO(deviceName)

            var socket: BluetoothSocket? = null
            try {
                // Cancelar cualquier proceso de descubrimiento antes de conectar
                try {
                    if (bluetoothAdapter?.isDiscovering == true) {
                        bluetoothAdapter.cancelDiscovery()
                        Log.d(TAG, "cancelDiscovery ejecutado exitosamente antes de conectar")
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "No se pudo cancelar descubrimiento por falta de permiso: ${e.message}")
                }

                // Crear socket RFCOMM con el UUID estándar SPP
                socket = device.createRfcommSocketToServiceRecord(BluetoothConstants.SPP_UUID)

                // Intentar conexión con timeout
                withTimeout(timeoutMs) {
                    Log.d(TAG, "Intentando socket.connect() hacia ${device.address}...")
                    socket.connect()
                }

                if (!isActive) {
                    socket.close()
                    return@launch
                }

                Log.d(TAG, "Conexión RFCOMM establecida con éxito a $deviceName (${device.address})")
                _connectionState.value = ClientConnectionState.CONECTADO(deviceName, device.address)

                // Adjuntar socket a ConnectionManager para lectura, latidos y watchdog
                connectionManager.attachSocket(socket, deviceName, device.address)

            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timeout agotado tras ${timeoutMs}ms intentando conectar a $deviceName")
                try { socket?.close() } catch (_: Exception) {}
                _connectionState.value = ClientConnectionState.ERROR("Tiempo de espera agotado (timeout de $timeoutMs ms)")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException durante la conexión RFCOMM: ${e.message}")
                try { socket?.close() } catch (_: Exception) {}
                _connectionState.value = ClientConnectionState.ERROR("Permisos Bluetooth insuficientes para conectar.")
            } catch (e: IOException) {
                Log.e(TAG, "IOException al conectar a socket RFCOMM: ${e.message}")
                try { socket?.close() } catch (_: Exception) {}
                _connectionState.value = ClientConnectionState.ERROR("No se pudo conectar al Nodo: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Excepción inesperada al conectar: ${e.message}")
                try { socket?.close() } catch (_: Exception) {}
                _connectionState.value = ClientConnectionState.ERROR("Error de conexión: ${e.message}")
            }
        }
    }

    /**
     * Reintenta la conexión con el último dispositivo objetivo seleccionado.
     */
    fun reconnect() {
        lastTargetDevice?.let { connectToDevice(it) }
    }

    /**
     * Envía un mensaje estructurado del protocolo hacia el Nodo.
     */
    suspend fun sendMessage(message: ProtocolMessage): Boolean {
        return connectionManager.send(message)
    }

    /**
     * Cierra la conexión activa y resetea el estado a DESCONECTADO.
     */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        connectionManager.disconnect()
        _connectionState.value = ClientConnectionState.DESCONECTADO
    }
}

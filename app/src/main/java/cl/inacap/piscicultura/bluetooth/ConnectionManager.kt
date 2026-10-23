package cl.inacap.piscicultura.bluetooth

import android.bluetooth.BluetoothSocket
import android.util.Log
import cl.inacap.piscicultura.domain.Protocol
import cl.inacap.piscicultura.domain.ProtocolMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Estados de la conexión gestionada por ConnectionManager.
 */
sealed class ManagedConnectionState {
    object Desconectado : ManagedConnectionState()
    data class Conectado(val deviceName: String, val deviceAddress: String) : ManagedConnectionState()
    data class Error(val mensaje: String) : ManagedConnectionState()
}

/**
 * Gestor de la capa de transporte RFCOMM sobre un BluetoothSocket activo.
 * - Lectura de líneas con BufferedReader en Dispatchers.IO.
 * - Escritura sincronizada con Mutex.
 * - Parseo seguro ignorando JSON corrupto sin crashear.
 * - Heartbeat PING/PONG cada 5 s y timeout por inactividad tras 10 s sin mensajes.
 * - Límite de 2 KB por línea contra saturación y DoS.
 * - Cierre seguro de streams y sockets en bloque finally.
 * Diseñado para residir en un ViewModel o Service y sobrevivir a la rotación de pantalla.
 */
class ConnectionManager(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ConnectionManager"
        const val MAX_LINE_CHARACTERS = Protocol.MAX_LINE_BYTES // 2048 caracteres (2 KB)
        const val HEARTBEAT_INTERVAL_MS = 5000L                 // PING cada 5 segundos
        const val INACTIVITY_TIMEOUT_MS = 10000L               // Desconexión a los 10 segundos sin tráfico
    }

    private val writeMutex = Mutex()

    private val _connectionState = MutableStateFlow<ManagedConnectionState>(ManagedConnectionState.Desconectado)
    val connectionState: StateFlow<ManagedConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<ProtocolMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<ProtocolMessage> = _incomingMessages.asSharedFlow()

    @Volatile
    private var activeSocket: BluetoothSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var lastReceivedTimestamp: Long = 0L

    val isConnected: Boolean
        get() = _connectionState.value is ManagedConnectionState.Conectado && activeSocket?.isConnected == true

    /**
     * Vincula un socket Bluetooth ya conectado e inicia las tareas de I/O, latidos y watchdog.
     */
    fun attachSocket(socket: BluetoothSocket, deviceName: String, deviceAddress: String) {
        closeInternal("Reemplazando socket activo")

        activeSocket = socket
        lastReceivedTimestamp = System.currentTimeMillis()
        _connectionState.value = ManagedConnectionState.Conectado(deviceName, deviceAddress)

        // Inicia el bucle de lectura seguro
        readJob = scope.launch(Dispatchers.IO) {
            try {
                val inputStream = socket.inputStream
                val outputStream = socket.outputStream

                reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))

                startHeartbeat()
                startWatchdog()

                while (isActive && socket.isConnected) {
                    val line = readBoundedLine(reader, MAX_LINE_CHARACTERS) ?: break

                    // Actualizar marca de tiempo de actividad ante cualquier byte válido recibido
                    lastReceivedTimestamp = System.currentTimeMillis()

                    val message = Protocol.parse(line)
                    if (message != null) {
                        when (message) {
                            is ProtocolMessage.Ping -> {
                                // Responder inmediatamente con PONG
                                scope.launch(Dispatchers.IO) {
                                    send(ProtocolMessage.Pong)
                                }
                            }
                            is ProtocolMessage.Pong -> {
                                // Latido confirmado, no requiere acción adicional
                                Log.d(TAG, "PONG recibido del peer")
                            }
                            else -> {
                                // Emitir mensaje de datos, comando, saludo o confirmación
                                _incomingMessages.emit(message)
                            }
                        }
                    } else {
                        Log.w(TAG, "Línea ignorada por JSON inválido o estructura no reconocida: $line")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error de I/O en socket Bluetooth: ${e.message}")
                if (isActive) {
                    _connectionState.value = ManagedConnectionState.Error("Conexión perdida: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Excepción inesperada en lectura Bluetooth: ${e.message}")
                if (isActive) {
                    _connectionState.value = ManagedConnectionState.Error("Error en comunicación: ${e.message}")
                }
            } finally {
                // Cierre seguro de streams y socket
                closeInternal("Finalización del bucle de lectura")
                if (isActive && _connectionState.value !is ManagedConnectionState.Error) {
                    _connectionState.value = ManagedConnectionState.Desconectado
                }
            }
        }
    }

    /**
     * Envía un mensaje del protocolo con sincronización estricta por Mutex.
     */
    suspend fun send(message: ProtocolMessage): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            return@withLock try {
                val currentWriter = writer
                val socket = activeSocket
                if (currentWriter != null && socket?.isConnected == true) {
                    val wireData = message.toWire()
                    currentWriter.write(wireData)
                    currentWriter.flush()
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallo al escribir en socket Bluetooth: ${e.message}")
                closeInternal("Fallo de escritura: ${e.message}")
                _connectionState.value = ManagedConnectionState.Error("Fallo de transmisión: ${e.message}")
                false
            }
        }
    }

    /**
     * Inicia el envío periódico de PING cada 5 segundos para verificar enlace.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnected) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (isActive && isConnected) {
                    send(ProtocolMessage.Ping)
                }
            }
        }
    }

    /**
     * Monitorea la inactividad de mensajes entrantes.
     * Si transcurren más de 10 s sin mensajes, asume desconexión del peer.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnected) {
                delay(1000L)
                val elapsed = System.currentTimeMillis() - lastReceivedTimestamp
                if (elapsed > INACTIVITY_TIMEOUT_MS) {
                    Log.w(TAG, "Timeout de inactividad alcanzado ($elapsed ms sin mensajes). Cerrando conexión.")
                    _connectionState.value = ManagedConnectionState.Error("Desconexión por inactividad (sin respuesta en 10 s)")
                    closeInternal("Watchdog timeout")
                    break
                }
            }
        }
    }

    /**
     * Lee una línea de texto con límite estricto de caracteres para evitar desbordamientos de memoria.
     */
    private fun readBoundedLine(reader: BufferedReader?, maxChars: Int): String? {
        if (reader == null) return null
        val sb = StringBuilder()
        var exceeded = false

        while (true) {
            val charCode = reader.read()
            if (charCode == -1) {
                // EOF alcanzado
                return if (sb.isEmpty() && !exceeded) null else if (exceeded) null else sb.toString()
            }

            val c = charCode.toChar()
            if (c == '\n') {
                return if (exceeded) null else sb.toString()
            }
            if (c == '\r') {
                continue
            }

            if (sb.length < maxChars) {
                sb.append(c)
            } else {
                exceeded = true
            }
        }
    }

    /**
     * Cierra de forma segura todos los recursos, cancela corrutinas asociadas y limpia referencias.
     */
    private fun closeInternal(reason: String) {
        Log.d(TAG, "Cerrando recursos de conexión Bluetooth: $reason")
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
        heartbeatJob = null
        watchdogJob = null

        try {
            reader?.close()
        } catch (_: Exception) {}
        try {
            writer?.close()
        } catch (_: Exception) {}
        try {
            activeSocket?.close()
        } catch (_: Exception) {}

        reader = null
        writer = null
        activeSocket = null
    }

    /**
     * Método público para forzar la desconexión manual.
     */
    fun disconnect() {
        readJob?.cancel()
        readJob = null
        closeInternal("Desconexión solicitada por usuario")
        _connectionState.value = ManagedConnectionState.Desconectado
    }
}

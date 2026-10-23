package cl.inacap.piscicultura.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.inacap.piscicultura.bluetooth.BluetoothServer
import cl.inacap.piscicultura.bluetooth.ServerConnectionState
import cl.inacap.piscicultura.data.model.SensorData
import cl.inacap.piscicultura.domain.ModoAireador
import cl.inacap.piscicultura.domain.PiscicultureLogic
import cl.inacap.piscicultura.domain.PiscicultureState
import cl.inacap.piscicultura.domain.ProtocolMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel para el rol de NODO SENSOR (Simulador ESP32 de Piscicultura).
 * - Mantiene y preserva la lógica de negocio y el estado ante cambios de configuración / rotación.
 * - Ejecuta un loop continuo de 2 s (CICLO = 2000 ms) en Dispatchers.Default que evalúa las reglas OT
 *   y, si hay una Estación conectada, transmite la telemetría en formato DATA JSON de una línea.
 * - Procesa comandos remotos RFCOMM (AIREADOR) respondiendo inmediatamente con ACK.
 * - Administra de forma segura el ciclo de vida del BluetoothServerSocket y la conexión activa.
 */
class NodoViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private val logic = PiscicultureLogic(tiempoInicialMs = System.currentTimeMillis())

    private val _piscicultureState = MutableStateFlow(logic.getState())
    val piscicultureState: StateFlow<PiscicultureState> = _piscicultureState.asStateFlow()

    // Compatibilidad retroactiva con modelos existentes
    private val _telemetryState = MutableStateFlow(mapToSensorData(logic.getState()))
    val telemetryState: StateFlow<SensorData> = _telemetryState.asStateFlow()

    private val server: BluetoothServer = BluetoothServer(bluetoothAdapter, viewModelScope)
    val connectionState: StateFlow<ServerConnectionState> = server.connectionState

    private val _lastSentJson = MutableStateFlow("")
    val lastSentJson: StateFlow<String> = _lastSentJson.asStateFlow()

    private val _eventLogs = MutableStateFlow<List<String>>(emptyList())
    val eventLogs: StateFlow<List<String>> = _eventLogs.asStateFlow()

    private var loopJob: Job? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        addLog("Inicializado Nodo Sensor (Simulador ESP32)")

        // Escucha reactiva de mensajes del protocolo recibidos desde la Estación
        viewModelScope.launch {
            server.connectionManager.incomingMessages.collect { message ->
                handleIncomingMessage(message)
            }
        }

        // Registro de cambios en la conexión RFCOMM
        viewModelScope.launch {
            server.connectionState.collect { state ->
                when (state) {
                    is ServerConnectionState.DESCONECTADO -> addLog("Servidor Bluetooth: Desconectado")
                    is ServerConnectionState.ESPERANDO -> addLog("Servidor Bluetooth: Esperando estación...")
                    is ServerConnectionState.CONECTADO -> addLog("Conectado con: ${state.deviceName} (${state.deviceAddress})")
                    is ServerConnectionState.ERROR -> addLog("Error Servidor: ${state.mensaje}")
                }
            }
        }

        // Iniciar loop periódico en Dispatchers.Default (CICLO = 2000 ms)
        startPeriodicLoop()

        // Iniciar servidor RFCOMM por defecto
        startServer()
    }

    /**
     * Loop continuo en Dispatchers.Default que evalúa la lógica cada 2 segundos
     * y despacha telemetría DATA hacia la estación conectada.
     */
    private fun startPeriodicLoop() {
        loopJob?.cancel()
        loopJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(PiscicultureLogic.CICLO) // 2000L
                val ahoraMs = System.currentTimeMillis()
                val updatedState = logic.evaluarCiclo(ahoraMs)

                _piscicultureState.value = updatedState
                _telemetryState.value = mapToSensorData(updatedState)

                // Construcción de la trama de telemetría DATA
                val dataMsg = ProtocolMessage.Data(
                    ts = ahoraMs,
                    temp = updatedState.temp,
                    turb = updatedState.turbidez,
                    aireador = updatedState.aireadorActivo,
                    manual = updatedState.modoManual
                )

                val json = dataMsg.toJson()
                _lastSentJson.value = json

                // Envío por RFCOMM si existe una estación conectada
                if (server.connectionState.value is ServerConnectionState.CONECTADO) {
                    server.sendMessage(dataMsg)
                }
            }
        }
    }

    fun initBluetooth(context: Context? = null) {
        startServer()
    }

    fun startServer() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            addLog("Aviso: Bluetooth apagado o no disponible en hardware")
        }
        server.startListening()
    }

    fun stopServer() {
        server.stop()
        addLog("Servidor Bluetooth detenido")
    }

    fun toggleServer() {
        when (server.connectionState.value) {
            is ServerConnectionState.DESCONECTADO, is ServerConnectionState.ERROR -> startServer()
            is ServerConnectionState.ESPERANDO, is ServerConnectionState.CONECTADO -> stopServer()
        }
    }

    fun setTemperatura(temp: Float) {
        val newState = logic.setTemperatura(temp)
        _piscicultureState.value = newState
        _telemetryState.value = mapToSensorData(newState)
        sendImmediateTelemetryIfConnected()
    }

    fun setTurbidez(turb: Float) {
        val newState = logic.setTurbidez(turb)
        _piscicultureState.value = newState
        _telemetryState.value = mapToSensorData(newState)
        sendImmediateTelemetryIfConnected()
    }

    fun setModoAireador(modo: ModoAireador) {
        val newState = logic.setModoAireador(modo)
        _piscicultureState.value = newState
        _telemetryState.value = mapToSensorData(newState)
        addLog("Aireador en modo: $modo (Activo: ${newState.aireadorActivo})")
        sendImmediateTelemetryIfConnected()
    }

    fun setModoAireadorString(modoStr: String): Boolean {
        val ok = logic.setModoAireadorString(modoStr)
        val newState = logic.getState()
        _piscicultureState.value = newState
        _telemetryState.value = mapToSensorData(newState)
        if (ok) {
            addLog("Aireador cambiado a: ${newState.modoAireador}")
            sendImmediateTelemetryIfConnected()
        }
        return ok
    }

    fun toggleModoManualAireador() {
        val newState = logic.toggleModoManualAireador()
        _piscicultureState.value = newState
        _telemetryState.value = mapToSensorData(newState)
        addLog("Modo manual conmutado: ${newState.modoAireador}")
        sendImmediateTelemetryIfConnected()
    }

    fun toggleAireadorManualState() {
        val newState = logic.toggleAireadorManualState()
        _piscicultureState.value = newState
        _telemetryState.value = mapToSensorData(newState)
        addLog("Estado manual aireador: ${if (newState.aireadorActivo) "ENCENDIDO" else "APAGADO"}")
        sendImmediateTelemetryIfConnected()
    }

    fun clearLogs() {
        _eventLogs.value = emptyList()
    }

    private fun addLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $message"
        val current = _eventLogs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 30) {
            _eventLogs.value = current.take(30)
        } else {
            _eventLogs.value = current
        }
    }

    private fun sendImmediateTelemetryIfConnected() {
        val state = _piscicultureState.value
        val ahoraMs = System.currentTimeMillis()
        val dataMsg = ProtocolMessage.Data(
            ts = ahoraMs,
            temp = state.temp,
            turb = state.turbidez,
            aireador = state.aireadorActivo,
            manual = state.modoManual
        )
        _lastSentJson.value = dataMsg.toJson()
        if (server.connectionState.value is ServerConnectionState.CONECTADO) {
            viewModelScope.launch {
                server.sendMessage(dataMsg)
            }
        }
    }

    private fun handleIncomingMessage(message: ProtocolMessage) {
        when (message) {
            is ProtocolMessage.Cmd -> {
                addLog("📥 Comando recibido: ${message.cmd} (${message.value ?: ""})")
                when (message.cmd) {
                    ProtocolMessage.Cmd.CMD_AIREADOR -> {
                        val value = message.value ?: "AUTO"
                        val ok = logic.setModoAireadorString(value)
                        val newState = logic.getState()
                        _piscicultureState.value = newState
                        _telemetryState.value = mapToSensorData(newState)

                        addLog("✅ CMD AIREADOR ejecutado -> $value (ok=$ok)")
                        viewModelScope.launch {
                            server.sendMessage(ProtocolMessage.Ack(ref = ProtocolMessage.Cmd.CMD_AIREADOR, ok = ok))
                        }
                        sendImmediateTelemetryIfConnected()
                    }
                    else -> {
                        viewModelScope.launch {
                            server.sendMessage(ProtocolMessage.Ack(ref = message.cmd, ok = false))
                        }
                    }
                }
            }
            is ProtocolMessage.Hello -> {
                addLog("🤝 Conexión negociada con ${message.role} (${message.user})")
                viewModelScope.launch {
                    server.sendMessage(ProtocolMessage.Hello(role = ProtocolMessage.Hello.ROLE_NODO, user = "ESP32-Nodo"))
                }
            }
            is ProtocolMessage.Ack -> {
                addLog("📬 ACK recibido para '${message.ref}': ${if (message.ok) "OK" else "ERROR"}")
            }
            is ProtocolMessage.Ping, is ProtocolMessage.Pong -> {
                // Heartbeat interno
            }
            else -> {}
        }
    }

    private fun mapToSensorData(state: PiscicultureState): SensorData {
        return SensorData(
            temperatura = state.temp,
            turbidez = state.turbidez,
            aireadorEncendido = state.aireadorActivo,
            modoManualAireador = state.modoManual,
            timestamp = System.currentTimeMillis()
        )
    }

    override fun onCleared() {
        super.onCleared()
        loopJob?.cancel()
        server.stop()
        addLog("NodoViewModel destruido: Sockets RFCOMM cerrados")
    }
}

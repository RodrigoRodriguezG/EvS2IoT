package cl.inacap.piscicultura.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.inacap.piscicultura.bluetooth.BluetoothClient
import cl.inacap.piscicultura.bluetooth.ClientConnectionState
import cl.inacap.piscicultura.domain.ProtocolMessage
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

/**
 * ViewModel del Rol ESTACIÓN.
 * - Utiliza BluetoothClient y ConnectionManager para comunicación RFCOMM.
 * - Expone StateFlows reactivos con telemetría en tiempo real, estado de enlace y frescura de datos.
 * - Monitorea inactividad y marca los datos como desactualizados si transcurren > 6 s sin telemetría.
 * - Soporta reconexión manual ante desconexiones forzadas sin crashear.
 * - Sobrevive a la rotación de pantalla manteniendo intacta la conexión y el estado.
 */
class EstacionViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private val client = BluetoothClient(bluetoothAdapter, viewModelScope)

    // Estado del cliente RFCOMM (DESCONECTADO, CONECTANDO, CONECTADO, ERROR)
    val connectionState: StateFlow<ClientConnectionState> = client.connectionState

    // Último paquete de datos recibido del Nodo Sensor
    private val _telemetry = MutableStateFlow<ProtocolMessage.Data?>(null)
    val telemetry: StateFlow<ProtocolMessage.Data?> = _telemetry.asStateFlow()

    // Dispositivos Bluetooth emparejados
    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()

    // Dispositivo actualmente seleccionado para conectar o reconectar
    private val _selectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val selectedDevice: StateFlow<BluetoothDevice?> = _selectedDevice.asStateFlow()

    // Segundos transcurridos desde el último DATA recibido
    private val _secondsSinceLastSync = MutableStateFlow(0)
    val secondsSinceLastSync: StateFlow<Int> = _secondsSinceLastSync.asStateFlow()

    // Indicador booleano de desactualización (> 6 s sin mensajes mientras está conectado)
    private val _isStale = MutableStateFlow(false)
    val isStale: StateFlow<Boolean> = _isStale.asStateFlow()

    // Eventos únicos para Snackbar (notificaciones de error, caídas de enlace o ACK)
    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    // Última confirmación ACK recibida del nodo
    private val _lastAck = MutableStateFlow<ProtocolMessage.Ack?>(null)
    val lastAck: StateFlow<ProtocolMessage.Ack?> = _lastAck.asStateFlow()

    // Registro temporal del último mensaje DATA
    @Volatile
    private var lastDataReceivedTimestamp: Long = 0L

    private var tickerJob: Job? = null
    private var wasPreviouslyConnected: Boolean = false

    init {
        refreshBondedDevices()
        observeIncomingMessages()
        observeConnectionLifecycle()
        startPeriodicTicker()
    }

    /**
     * Actualiza la lista de dispositivos Bluetooth emparejados en el sistema.
     */
    fun refreshBondedDevices() {
        val devices = client.getBondedDevices()
        _bondedDevices.value = devices
        if (_selectedDevice.value == null && devices.isNotEmpty()) {
            _selectedDevice.value = devices.first()
        }
    }

    /**
     * Selecciona un dispositivo para conexión.
     */
    fun selectDevice(device: BluetoothDevice) {
        _selectedDevice.value = device
    }

    /**
     * Inicia la conexión RFCOMM hacia el dispositivo especificado o el seleccionado.
     */
    fun connect(device: BluetoothDevice? = null) {
        val target = device ?: _selectedDevice.value
        if (target != null) {
            _selectedDevice.value = target
            client.connectToDevice(target)
        } else {
            viewModelScope.launch {
                _snackbarEvent.emit("Por favor selecciona un dispositivo emparejado primero.")
            }
        }
    }

    /**
     * Reconexión manual rápida ante caídas de enlace o desconexión forzada.
     */
    fun reconectar() {
        val target = _selectedDevice.value
        if (target != null) {
            client.connectToDevice(target)
        } else {
            client.reconnect()
        }
    }

    /**
     * Desconexión manual limpia y segura de sockets.
     */
    fun disconnect() {
        wasPreviouslyConnected = false
        _isStale.value = false
        client.disconnect()
    }

    /**
     * Envía comando para configurar el modo de aireador (AUTO, ON, OFF).
     */
    fun setAireador(modo: String) {
        val normalized = modo.uppercase().trim()
        if (normalized !in listOf(
                ProtocolMessage.Cmd.VALUE_AUTO,
                ProtocolMessage.Cmd.VALUE_ON,
                ProtocolMessage.Cmd.VALUE_OFF
            )
        ) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val sent = client.sendMessage(ProtocolMessage.Cmd.aireador(normalized))
            if (!sent) {
                _snackbarEvent.emit("No se pudo enviar AIREADOR ($normalized): sin conexión.")
            }
        }
    }

    /**
     * Escucha continua de mensajes entrantes emitidos por ConnectionManager.
     */
    private fun observeIncomingMessages() {
        viewModelScope.launch {
            client.connectionManager.incomingMessages.collect { message ->
                when (message) {
                    is ProtocolMessage.Data -> {
                        _telemetry.value = message
                        lastDataReceivedTimestamp = System.currentTimeMillis()
                        _secondsSinceLastSync.value = 0
                        _isStale.value = false
                    }
                    is ProtocolMessage.Ack -> {
                        _lastAck.value = message
                        if (message.ok) {
                            _snackbarEvent.emit("✓ Comando '${message.ref}' aplicado por el nodo.")
                        } else {
                            _snackbarEvent.emit("⚠️ Comando '${message.ref}' rechazado o no permitido.")
                        }
                    }
                    is ProtocolMessage.Hello -> {
                        // Saludo negociado correctamente
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Supervisa los cambios en el estado de la conexión para enviar el saludo inicial HELLO
     * y notificar desconexiones forzadas sin generar cierres inesperados de la app.
     */
    private fun observeConnectionLifecycle() {
        viewModelScope.launch {
            client.connectionState.collect { state ->
                when (state) {
                    is ClientConnectionState.CONECTADO -> {
                        wasPreviouslyConnected = true
                        _isStale.value = false
                        // Enviar saludo inicial identificando el rol ESTACIÓN
                        client.sendMessage(
                            ProtocolMessage.Hello(
                                role = ProtocolMessage.Hello.ROLE_ESTACION,
                                user = "Operador Estación"
                            )
                        )
                    }
                    is ClientConnectionState.ERROR -> {
                        _isStale.value = false
                        wasPreviouslyConnected = false
                        _snackbarEvent.emit("Desconexión o error: ${state.mensaje}")
                    }
                    is ClientConnectionState.DESCONECTADO -> {
                        if (wasPreviouslyConnected) {
                            _snackbarEvent.emit("Conexión perdida con el Nodo Sensor.")
                        }
                        wasPreviouslyConnected = false
                        _isStale.value = false
                    }
                    is ClientConnectionState.CONECTANDO -> {
                        _isStale.value = false
                    }
                }
            }
        }
    }

    /**
     * Ticker periódico de 1 segundo para:
     * 1) Actualizar los segundos desde la última sincronización.
     * 2) Marcar datos como desactualizados si transcurren > 6 s sin telemetría.
     */
    private fun startPeriodicTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000L)
                val currentState = client.connectionState.value
                if (currentState is ClientConnectionState.CONECTADO) {
                    // Evaluar frescura de la telemetría recibida
                    if (lastDataReceivedTimestamp > 0L) {
                        val elapsed = ((System.currentTimeMillis() - lastDataReceivedTimestamp) / 1000L).toInt()
                        _secondsSinceLastSync.value = elapsed
                        _isStale.value = (elapsed > 6)
                    }
                } else {
                    _isStale.value = false
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
        client.disconnect()
    }
}

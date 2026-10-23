package cl.inacap.piscicultura.domain

import cl.inacap.piscicultura.data.model.SensorData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Controlador de la lógica del Nodo Sensor (Simulador ESP32 de Piscicultura).
 * - Aireador ON si temperatura < 18 °C o > 26 °C, o turbidez > 60 %.
 * - Alimentador: cada 15 s intenta alimentar SOLO si el aireador está apagado;
 *   si está encendido, pospone y reintenta cada 2 s sin reiniciar el temporizador principal.
 */
class NodoLogicController(
    private val scope: CoroutineScope,
    private val onTelemetryTick: (SensorData) -> Unit
) {
    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    private var feederJob: Job? = null
    private var telemetryJob: Job? = null

    // Parámetros internos
    private var temperaturaActual: Float = 21.0f
    private var turbidezActual: Float = 25.0f
    private var modoManual: Boolean = false
    private var aireadorManualState: Boolean = false

    private var segundosRestantes: Int = 15
    private var alimentadorActivo: Boolean = false
    private var alimentadorPospuesto: Boolean = false
    private var contadorAlimentaciones: Int = 0

    fun start() {
        evaluateAerator()
        startFeederLoop()
        startTelemetryLoop()
    }

    fun setTemperatura(temp: Float) {
        temperaturaActual = temp.coerceIn(0.0f, 40.0f)
        evaluateAerator()
    }

    fun setTurbidez(turb: Float) {
        turbidezActual = turb.coerceIn(0.0f, 100.0f)
        evaluateAerator()
    }

    fun toggleModoManualAireador() {
        modoManual = !modoManual
        if (modoManual) {
            // Mantiene el estado actual al entrar en manual
            aireadorManualState = _sensorData.value.aireadorEncendido
        }
        evaluateAerator()
    }

    fun setAireadorManualState(encendido: Boolean) {
        modoManual = true
        aireadorManualState = encendido
        evaluateAerator()
    }

    fun forceFeed(): Boolean {
        val aireadorOn = _sensorData.value.aireadorEncendido
        if (aireadorOn) {
            // No se puede alimentar si el aireador está encendido
            alimentadorPospuesto = true
            updateState()
            return false
        }

        // Ejecutar alimentación forzada
        triggerFeeding()
        return true
    }

    private fun evaluateAerator() {
        val aireadorOn = if (modoManual) {
            aireadorManualState
        } else {
            // Regla automática: < 18 °C o > 26 °C, o turbidez > 60 %
            (temperaturaActual < 18.0f || temperaturaActual > 26.0f || turbidezActual > 60.0f)
        }

        updateState(aireadorOn = aireadorOn)
    }

    private fun startFeederLoop() {
        feederJob?.cancel()
        feederJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (segundosRestantes > 0) {
                    delay(1000L)
                    segundosRestantes--
                    updateState()
                } else {
                    // Intento de alimentación
                    val aireadorOn = _sensorData.value.aireadorEncendido
                    if (aireadorOn) {
                        // Aireador está encendido: POSPONER y reintentar en 2s sin reiniciar ciclo de 15s
                        alimentadorPospuesto = true
                        updateState()
                        delay(2000L)
                        // Continúa en el siguiente ciclo sin alterar los segundos a 15 hasta que el aireador se apague
                    } else {
                        // Aireador apagado: dispensar comida
                        alimentadorPospuesto = false
                        triggerFeeding()
                        // Esperar duración de dispensado (2 segundos)
                        delay(2000L)
                        alimentadorActivo = false
                        segundosRestantes = 15
                        updateState()
                    }
                }
            }
        }
    }

    private fun triggerFeeding() {
        alimentadorActivo = true
        contadorAlimentaciones++
        updateState()
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(2000L) // Envía estado cada 2 segundos
                val current = _sensorData.value.copy(timestamp = System.currentTimeMillis())
                _sensorData.value = current
                onTelemetryTick(current)
            }
        }
    }

    private fun updateState(aireadorOn: Boolean? = null) {
        val currentAerator = aireadorOn ?: _sensorData.value.aireadorEncendido
        val newState = SensorData(
            temperatura = temperaturaActual,
            turbidez = turbidezActual,
            aireadorEncendido = currentAerator,
            modoManualAireador = modoManual,
            alimentadorActivo = alimentadorActivo,
            segundosRestantesAlimentacion = segundosRestantes,
            alimentadorPospuesto = alimentadorPospuesto,
            totalAlimentaciones = contadorAlimentaciones,
            timestamp = System.currentTimeMillis()
        )
        _sensorData.value = newState
    }

    fun stop() {
        feederJob?.cancel()
        telemetryJob?.cancel()
    }
}

package cl.inacap.piscicultura.domain

/**
 * Modos de control del aireador.
 * - AUTO: activado automáticamente si temperatura < 18 °C o > 26 °C, o turbidez > 60 %.
 * - ON: forzado encendido por el operador.
 * - OFF: forzado apagado por el operador.
 */
enum class ModoAireador {
    AUTO,
    ON,
    OFF
}

/**
 * Estado inmutable de la lógica del estanque de piscicultura.
 */
data class PiscicultureState(
    val temp: Float = 21.0f,
    val turbidez: Float = 25.0f,
    val modoAireador: ModoAireador = ModoAireador.AUTO,
    val aireadorActivo: Boolean = false
) {
    val modoManual: Boolean
        get() = modoAireador != ModoAireador.AUTO
}

/**
 * Lógica pura de negocio para el Nodo Sensor de Piscicultura (Salmonicultura IoT).
 * Completamente desacoplada de cualquier clase o dependencia de Android (android.*)
 * para permitir ejecución de pruebas unitarias puras y determinísticas.
 *
 * Constantes definidas:
 * - TEMP_MIN: 18.0 °C
 * - TEMP_MAX: 26.0 °C
 * - TURBIDEZ_MAX: 60.0 %
 * - CICLO: 2000 ms (2 s)
 *
 * Reglas de negocio:
 * 1. Aireador: En modo AUTO se activa si la temperatura está fuera del rango óptimo
 *    (temp < TEMP_MIN o temp > TEMP_MAX) O si la turbidez > TURBIDEZ_MAX.
 *    En modo ON/OFF manual se fuerza el estado según la decisión del operador.
 */
class PiscicultureLogic(
    @Suppress("UNUSED_PARAMETER") tiempoInicialMs: Long = 0L
) {
    companion object {
        const val TEMP_MIN: Float = 18.0f
        const val TEMP_MAX: Float = 26.0f
        const val TURBIDEZ_MAX: Float = 60.0f
        const val CICLO: Long = 2000L // 2 s
    }

    private var state: PiscicultureState

    init {
        val aireadorInicial = calcularAireador(21.0f, 25.0f, ModoAireador.AUTO)
        state = PiscicultureState(
            temp = 21.0f,
            turbidez = 25.0f,
            modoAireador = ModoAireador.AUTO,
            aireadorActivo = aireadorInicial
        )
    }

    @Synchronized
    fun getState(): PiscicultureState = state

    @Synchronized
    fun setTemperatura(temp: Float): PiscicultureState {
        val clamped = temp.coerceIn(0.0f, 40.0f)
        val aireador = calcularAireador(clamped, state.turbidez, state.modoAireador)
        state = state.copy(temp = clamped, aireadorActivo = aireador)
        return state
    }

    @Synchronized
    fun setTurbidez(turb: Float): PiscicultureState {
        val clamped = turb.coerceIn(0.0f, 100.0f)
        val aireador = calcularAireador(state.temp, clamped, state.modoAireador)
        state = state.copy(turbidez = clamped, aireadorActivo = aireador)
        return state
    }

    @Synchronized
    fun setModoAireador(modo: ModoAireador): PiscicultureState {
        val aireador = calcularAireador(state.temp, state.turbidez, modo)
        state = state.copy(modoAireador = modo, aireadorActivo = aireador)
        return state
    }

    @Synchronized
    fun setModoAireadorString(modoStr: String): Boolean {
        val modo = when (modoStr.trim().uppercase()) {
            "AUTO" -> ModoAireador.AUTO
            "ON" -> ModoAireador.ON
            "OFF" -> ModoAireador.OFF
            else -> return false
        }
        setModoAireador(modo)
        return true
    }

    @Synchronized
    fun toggleModoManualAireador(): PiscicultureState {
        val nuevoModo = if (state.modoAireador == ModoAireador.AUTO) {
            if (state.aireadorActivo) ModoAireador.ON else ModoAireador.OFF
        } else {
            ModoAireador.AUTO
        }
        return setModoAireador(nuevoModo)
    }

    @Synchronized
    fun toggleAireadorManualState(): PiscicultureState {
        val nuevoModo = if (state.aireadorActivo) ModoAireador.OFF else ModoAireador.ON
        return setModoAireador(nuevoModo)
    }

    /**
     * Evalúa el ciclo periódico del estanque con el timestamp [ahoraMs].
     */
    @Synchronized
    fun evaluarCiclo(@Suppress("UNUSED_PARAMETER") ahoraMs: Long): PiscicultureState {
        val aireador = calcularAireador(state.temp, state.turbidez, state.modoAireador)
        state = state.copy(aireadorActivo = aireador)
        return state
    }

    private fun calcularAireador(temp: Float, turbidez: Float, modo: ModoAireador): Boolean {
        return when (modo) {
            ModoAireador.ON -> true
            ModoAireador.OFF -> false
            ModoAireador.AUTO -> (temp < TEMP_MIN || temp > TEMP_MAX || turbidez > TURBIDEZ_MAX)
        }
    }
}

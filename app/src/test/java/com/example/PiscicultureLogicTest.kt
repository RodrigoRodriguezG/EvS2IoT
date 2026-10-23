package com.example

import cl.inacap.piscicultura.domain.ModoAireador
import cl.inacap.piscicultura.domain.PiscicultureLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de la lógica pura de negocio de piscicultura (Salmonicultura IoT).
 * Valida todas las constantes y reglas operativas OT requeridas para el aireador y sensores.
 */
class PiscicultureLogicTest {

    @Test
    fun testConstantesRequeridas() {
        assertEquals(18.0f, PiscicultureLogic.TEMP_MIN, 0.001f)
        assertEquals(26.0f, PiscicultureLogic.TEMP_MAX, 0.001f)
        assertEquals(60.0f, PiscicultureLogic.TURBIDEZ_MAX, 0.001f)
        assertEquals(2000L, PiscicultureLogic.CICLO)
    }

    @Test
    fun testReglaAireadorModoAutomatico() {
        val logic = PiscicultureLogic(tiempoInicialMs = 1000L)

        // Estado inicial normal (21 °C, 25 %) -> Aireador apagado
        assertFalse("Aireador debe iniciar apagado en condiciones óptimas", logic.getState().aireadorActivo)

        // Temperatura baja (< 18 °C) -> Aireador encendido
        logic.setTemperatura(17.9f)
        assertTrue("Aireador debe encenderse con temp < 18", logic.getState().aireadorActivo)

        // Volver a rango óptimo -> Aireador apagado
        logic.setTemperatura(22.0f)
        assertFalse("Aireador debe apagarse en rango óptimo", logic.getState().aireadorActivo)

        // Temperatura alta (> 26 °C) -> Aireador encendido
        logic.setTemperatura(26.5f)
        assertTrue("Aireador debe encenderse con temp > 26", logic.getState().aireadorActivo)

        // Volver a rango óptimo
        logic.setTemperatura(24.0f)
        assertFalse("Aireador debe apagarse", logic.getState().aireadorActivo)

        // Turbidez alta (> 60 %) -> Aireador encendido
        logic.setTurbidez(61.0f)
        assertTrue("Aireador debe encenderse con turbidez > 60", logic.getState().aireadorActivo)

        // Turbidez normal (<= 60 %)
        logic.setTurbidez(60.0f)
        assertFalse("Aireador debe apagarse con turbidez en límite normal 60%", logic.getState().aireadorActivo)
    }

    @Test
    fun testReglaAireadorModoManual() {
        val logic = PiscicultureLogic(tiempoInicialMs = 1000L)
        // Condiciones normales (21 °C, 25 %)
        assertFalse(logic.getState().aireadorActivo)

        // Forzar modo manual ON -> Aireador debe encenderse
        logic.setModoAireador(ModoAireador.ON)
        assertTrue(logic.getState().aireadorActivo)
        assertTrue(logic.getState().modoManual)

        // Forzar modo manual OFF aunque las condiciones requieran aireador
        logic.setTemperatura(10.0f) // muy frío
        logic.setModoAireador(ModoAireador.OFF)
        assertFalse("Modo manual OFF debe apagar el aireador aún con temp anormal", logic.getState().aireadorActivo)

        // Volver a AUTO -> Ahora sí debe encenderse por temp=10
        logic.setModoAireador(ModoAireador.AUTO)
        assertTrue("Modo AUTO debe reactivar aireador por temp baja", logic.getState().aireadorActivo)
        assertFalse(logic.getState().modoManual)
    }

    @Test
    fun testEvaluarCiclo() {
        val logic = PiscicultureLogic()
        logic.setTemperatura(22.0f)
        logic.setTurbidez(20.0f)

        val state = logic.evaluarCiclo(System.currentTimeMillis())
        assertFalse(state.aireadorActivo)

        logic.setTurbidez(75.0f)
        val stateUpdated = logic.evaluarCiclo(System.currentTimeMillis())
        assertTrue(stateUpdated.aireadorActivo)
    }
}

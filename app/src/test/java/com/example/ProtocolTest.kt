package com.example

import cl.inacap.piscicultura.domain.Protocol
import cl.inacap.piscicultura.domain.ProtocolMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtocolTest {

    @Test
    fun testDataMessageSerializationAndParsing() {
        val now = 1729600000000L
        val data = ProtocolMessage.Data(
            ts = now,
            temp = 22.5f,
            turb = 18.0f,
            aireador = true,
            manual = true
        )

        val wire = data.toWire()
        assertTrue("El mensaje debe terminar con '\\n'", wire.endsWith("\n"))
        assertEquals("Debe contener exactamente una línea terminada en newline", 1, wire.count { it == '\n' })
        assertFalse("No debe contener retorno de carro", wire.contains("\r"))

        val parsed = Protocol.parse(wire)
        assertNotNull(parsed)
        assertTrue(parsed is ProtocolMessage.Data)
        val parsedData = parsed as ProtocolMessage.Data
        assertEquals(now, parsedData.ts)
        assertEquals(22.5f, parsedData.temp, 0.01f)
        assertEquals(18.0f, parsedData.turb, 0.01f)
        assertTrue(parsedData.aireador)
        assertTrue(parsedData.manual)
    }

    @Test
    fun testAireadorCommandSerializationAndParsing() {
        val cmdOn = ProtocolMessage.Cmd.aireador("ON")
        val wireOn = cmdOn.toWire()
        assertEquals("{\"type\":\"CMD\",\"cmd\":\"AIREADOR\",\"value\":\"ON\"}\n", wireOn)

        val parsedOn = Protocol.parse(wireOn) as ProtocolMessage.Cmd
        assertEquals("AIREADOR", parsedOn.cmd)
        assertEquals("ON", parsedOn.value)

        val cmdAuto = ProtocolMessage.Cmd.aireador("AUTO")
        val parsedAuto = Protocol.parse(cmdAuto.toWire()) as ProtocolMessage.Cmd
        assertEquals("AUTO", parsedAuto.value)
    }

    @Test
    fun testHelloMessageSerializationAndParsing() {
        val helloNodo = ProtocolMessage.Hello(role = "NODO", user = "admin")
        val wire = helloNodo.toWire()
        assertEquals("{\"type\":\"HELLO\",\"role\":\"NODO\",\"user\":\"admin\"}\n", wire)

        val parsed = Protocol.parse(wire) as ProtocolMessage.Hello
        assertEquals("NODO", parsed.role)
        assertEquals("admin", parsed.user)
    }

    @Test
    fun testAckMessageSerializationAndParsing() {
        val ack = ProtocolMessage.Ack(ref = "AIREADOR", ok = true)
        val wire = ack.toWire()
        assertEquals("{\"type\":\"ACK\",\"ref\":\"AIREADOR\",\"ok\":true}\n", wire)

        val parsed = Protocol.parse(wire) as ProtocolMessage.Ack
        assertEquals("AIREADOR", parsed.ref)
        assertTrue(parsed.ok)
    }

    @Test
    fun testPingPongSerializationAndParsing() {
        val pingWire = ProtocolMessage.Ping.toWire()
        assertEquals("{\"type\":\"PING\"}\n", pingWire)
        val parsedPing = Protocol.parse(pingWire)
        assertEquals(ProtocolMessage.Ping, parsedPing)

        val pongWire = ProtocolMessage.Pong.toWire()
        assertEquals("{\"type\":\"PONG\"}\n", pongWire)
        val parsedPong = Protocol.parse(pongWire)
        assertEquals(ProtocolMessage.Pong, parsedPong)
    }

    @Test
    fun testSafeParsingIgnoresInvalidOrExcessiveLines() {
        assertNull(Protocol.parse(null))
        assertNull(Protocol.parse(""))
        assertNull(Protocol.parse("   \n"))
        assertNull(Protocol.parse("esto no es un json valido"))
        assertNull(Protocol.parse("{\"tipo\":\"DESCONOCIDO\"}"))
        assertNull(Protocol.parse("{\"type\":\"INVALID\"}"))

        // Línea mayor al límite de 2048 bytes
        val oversized = "{\"type\":\"PING\",\"padding\":\"" + "A".repeat(3000) + "\"}"
        assertNull("Líneas mayores a 2 KB deben descartarse", Protocol.parse(oversized))
    }
}

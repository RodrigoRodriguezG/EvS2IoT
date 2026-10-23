package cl.inacap.piscicultura.domain

import org.json.JSONException
import org.json.JSONObject

/**
 * Protocolo de comunicación IoT RFCOMM para Piscicultura.
 * Define los mensajes como data classes serializables a JSON de UNA línea terminada en "\n".
 */
sealed class ProtocolMessage(val type: String) {

    /**
     * Serializa el mensaje a un String JSON compacto de una sola línea, terminado en '\n'.
     */
    abstract fun toJson(): String

    /**
     * Garantiza formato estricto de UNA línea sin saltos de línea internos, terminada en '\n'.
     */
    fun toWire(): String {
        val clean = toJson().replace("\r", "").replace("\n", "").trim()
        return "$clean\n"
    }

    /**
     * 1) Telemetría periódica del Nodo Sensor hacia la Estación.
     */
    data class Data(
        val ts: Long,
        val temp: Float,
        val turb: Float,
        val aireador: Boolean,
        val manual: Boolean
    ) : ProtocolMessage("DATA") {
        override fun toJson(): String {
            val json = JSONObject()
            json.put("type", "DATA")
            json.put("ts", ts)
            json.put("temp", temp.toDouble())
            json.put("turb", turb.toDouble())
            json.put("aireador", aireador)
            json.put("manual", manual)
            return json.toString()
        }
    }

    /**
     * 2) Comandos de control enviados desde la Estación al Nodo.
     * - AIREADOR: controla el aireador ("ON", "OFF", "AUTO").
     */
    data class Cmd(
        val cmd: String,
        val value: String? = null
    ) : ProtocolMessage("CMD") {
        override fun toJson(): String {
            val json = JSONObject()
            json.put("type", "CMD")
            json.put("cmd", cmd)
            if (value != null) {
                json.put("value", value)
            }
            return json.toString()
        }

        companion object {
            const val CMD_AIREADOR = "AIREADOR"

            const val VALUE_ON = "ON"
            const val VALUE_OFF = "OFF"
            const val VALUE_AUTO = "AUTO"

            fun aireador(value: String): Cmd = Cmd(cmd = CMD_AIREADOR, value = value)
        }
    }

    /**
     * 3) Saludo y negociación inicial de rol al establecer la conexión.
     */
    data class Hello(
        val role: String,
        val user: String
    ) : ProtocolMessage("HELLO") {
        override fun toJson(): String {
            val json = JSONObject()
            json.put("type", "HELLO")
            json.put("role", role)
            json.put("user", user)
            return json.toString()
        }

        companion object {
            const val ROLE_NODO = "NODO"
            const val ROLE_ESTACION = "ESTACION"
        }
    }

    /**
     * 4) Confirmación de recepción o ejecución de un comando.
     */
    data class Ack(
        val ref: String,
        val ok: Boolean
    ) : ProtocolMessage("ACK") {
        override fun toJson(): String {
            val json = JSONObject()
            json.put("type", "ACK")
            json.put("ref", ref)
            json.put("ok", ok)
            return json.toString()
        }
    }

    /**
     * 5) Latido periódico cada 5 s para detectar caída de enlace.
     */
    object Ping : ProtocolMessage("PING") {
        override fun toJson(): String = "{\"type\":\"PING\"}"
    }

    /**
     * Respuesta inmediata al latido PING.
     */
    object Pong : ProtocolMessage("PONG") {
        override fun toJson(): String = "{\"type\":\"PONG\"}"
    }
}

/**
 * Parser seguro y utilitarios para el protocolo RFCOMM.
 * Ignora mensajes corruptos o no reconocidos sin lanzar excepciones.
 */
object Protocol {
    const val MAX_LINE_BYTES = 2048 // 2 KB máximo por línea

    /**
     * Parsea de forma segura una línea JSON recibida por el socket.
     * Retorna null si la línea es inválida, excede el tamaño o no cumple el esquema.
     */
    fun parse(line: String?): ProtocolMessage? {
        if (line.isNullOrBlank()) return null
        val trimmed = line.trim()
        if (trimmed.length > MAX_LINE_BYTES) return null

        return try {
            val json = JSONObject(trimmed)
            when (json.optString("type")) {
                "DATA" -> ProtocolMessage.Data(
                    ts = json.optLong("ts", System.currentTimeMillis()),
                    temp = json.optDouble("temp", 0.0).toFloat(),
                    turb = json.optDouble("turb", 0.0).toFloat(),
                    aireador = json.optBoolean("aireador", false),
                    manual = json.optBoolean("manual", false)
                )
                "CMD" -> ProtocolMessage.Cmd(
                    cmd = json.getString("cmd"),
                    value = if (json.has("value")) json.getString("value") else null
                )
                "HELLO" -> ProtocolMessage.Hello(
                    role = json.optString("role", "DESCONOCIDO"),
                    user = json.optString("user", "anonimo")
                )
                "ACK" -> ProtocolMessage.Ack(
                    ref = json.optString("ref", ""),
                    ok = json.optBoolean("ok", false)
                )
                "PING" -> ProtocolMessage.Ping
                "PONG" -> ProtocolMessage.Pong
                else -> null
            }
        } catch (_: JSONException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}

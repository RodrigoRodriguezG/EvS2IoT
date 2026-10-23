package cl.inacap.piscicultura.data.model

import com.google.gson.annotations.SerializedName

data class SensorData(
    @SerializedName("temperatura")
    val temperatura: Float = 20.0f,

    @SerializedName("turbidez")
    val turbidez: Float = 15.0f,

    @SerializedName("aireadorEncendido")
    val aireadorEncendido: Boolean = false,

    @SerializedName("modoManualAireador")
    val modoManualAireador: Boolean = false,

    @SerializedName("alimentadorActivo")
    val alimentadorActivo: Boolean = false,

    @SerializedName("segundosRestantesAlimentacion")
    val segundosRestantesAlimentacion: Int = 15,

    @SerializedName("alimentadorPospuesto")
    val alimentadorPospuesto: Boolean = false,

    @SerializedName("totalAlimentaciones")
    val totalAlimentaciones: Int = 0,

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)

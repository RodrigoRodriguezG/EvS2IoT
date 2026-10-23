package cl.inacap.piscicultura.data.model

import com.google.gson.annotations.SerializedName

data class CommandMessage(
    @SerializedName("action")
    val action: String,

    @SerializedName("payload")
    val payload: String = ""
) {
    companion object {
        const val ACTION_FORCE_FEED = "FORCE_FEED"
        const val ACTION_TOGGLE_AERATOR = "TOGGLE_AERATOR_MANUAL"
    }
}

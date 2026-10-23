package cl.inacap.piscicultura.bluetooth

import java.util.UUID

object BluetoothConstants {
    // UUID estándar para Serial Port Profile (SPP) RFCOMM
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val SERVICE_NAME = "PisciculturaIoT_RFCOMM"
}

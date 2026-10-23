package cl.inacap.piscicultura.bluetooth

import android.content.Context

/**
 * Delegado hacia PermissionHelper para compatibilidad de interfaces.
 */
object BluetoothPermissionHelper {

    fun getRequiredPermissions(): Array<String> = PermissionHelper.getRequiredPermissions()

    fun hasPermissions(context: Context): Boolean = PermissionHelper.hasPermissions(context)
}

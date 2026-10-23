package cl.inacap.piscicultura.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.databinding.ItemBluetoothDeviceBinding

class DeviceListAdapter(
    private val devices: List<BluetoothDevice>,
    private val onDeviceSelected: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    inner class DeviceViewHolder(val binding: ItemBluetoothDeviceBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemBluetoothDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DeviceViewHolder(binding)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        val name = try { device.name ?: "Dispositivo sin nombre" } catch (_: Exception) { "Dispositivo" }
        holder.binding.tvDeviceName.text = name
        holder.binding.tvDeviceAddress.text = device.address

        holder.binding.root.setOnClickListener {
            onDeviceSelected(device)
        }
    }

    override fun getItemCount(): Int = devices.size
}

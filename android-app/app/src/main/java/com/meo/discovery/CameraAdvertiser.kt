package com.meo.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.meo.protocol.Protocol

/**
 * Advertises the phone's control listener as `_meocam._tcp.` so a desktop can
 * find it without anyone typing an address.
 *
 * ## What is advertised, and what is not
 *
 * The device id and the protocol major version, and nothing else. Plan §6.2 is
 * explicit — *"Never advertise secrets"* — and mDNS is broadcast in the clear to
 * everyone on the network. The device id is safe to publish precisely because
 * [com.meo.pairing.DeviceTrust] generates it randomly rather than deriving it
 * from hardware: it identifies this installation to a desktop that already
 * paired with it, and means nothing to anyone else.
 *
 * The major version is there so a desktop too old or too new can say so, rather
 * than dialling and failing at the handshake.
 *
 * ## Discovery is a convenience, never a requirement
 *
 * Plan §5.1 makes manual address entry a first-class path, because receiving
 * mDNS on Windows may itself involve a firewall interaction — multicast
 * listening is not plain outbound traffic. If registration fails here, the
 * session still works for any desktop given the address directly, so a failure
 * is reported and not treated as fatal.
 */
class CameraAdvertiser(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    @Volatile
    var registeredName: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var listener: NsdManager.RegistrationListener? = null

    fun start(deviceId: String, displayName: String, port: Int) {
        if (listener != null) return
        val manager = nsdManager ?: run {
            lastError = "this device has no network service discovery"
            return
        }

        val info = NsdServiceInfo().apply {
            // The service name is what a person sees in a picker, so it is the
            // phone's name rather than its id.
            serviceName = displayName.take(MAX_NAME_LENGTH).ifBlank { "Meo Camera" }
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute(ATTRIBUTE_DEVICE_ID, deviceId)
            setAttribute(ATTRIBUTE_PROTOCOL, Protocol.MAJOR_VERSION.toString())
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // Android may rename on collision — two phones called "Pixel"
                // on one network — so the effective name is read back rather
                // than assumed.
                registeredName = serviceInfo.serviceName
                lastError = null
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registeredName = null
                lastError = "could not advertise on this network (code $errorCode)"
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registeredName = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registeredName = null
            }
        }

        listener = registration
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        } catch (error: Exception) {
            listener = null
            lastError = "could not advertise: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    fun stop() {
        val manager = nsdManager
        val current = listener ?: return
        listener = null
        registeredName = null
        try {
            manager?.unregisterService(current)
        } catch (_: Exception) {
            // Already unregistered, or the manager is shutting down with the
            // process. Nothing here is worth failing a stop over.
        }
    }

    companion object {
        const val SERVICE_TYPE = "_meocam._tcp."
        const val ATTRIBUTE_DEVICE_ID = "id"
        const val ATTRIBUTE_PROTOCOL = "v"
        private const val MAX_NAME_LENGTH = 63
    }
}

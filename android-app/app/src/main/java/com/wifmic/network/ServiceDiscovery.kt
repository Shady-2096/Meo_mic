package com.wifmic.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Discovers Meo Mic PC receivers on the local network using NSD (mDNS/Bonjour).
 * The PC app registers a service of type "_meomic._udp. that this discovers.
 */
class ServiceDiscovery(context: Context) {

    companion object {
        const val SERVICE_TYPE = "_meomic._udp."
        const val SERVICE_NAME = "MeoMic"
    }

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _discoveredServices = MutableStateFlow<List<DiscoveredPC>>(emptyList())
    val discoveredServices: StateFlow<List<DiscoveredPC>> = _discoveredServices

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val pendingResolves = mutableListOf<NsdServiceInfo>()

    data class DiscoveredPC(
        val name: String,
        val host: String,
        val port: Int
    )

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // Failed to resolve, try next pending if any
            resolveNextPending()
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val pc = DiscoveredPC(
                name = serviceInfo.serviceName,
                host = serviceInfo.host.hostAddress ?: "",
                port = serviceInfo.port
            )

            if (pc.host.isNotEmpty()) {
                val currentList = _discoveredServices.value.toMutableList()
                // Avoid duplicates
                if (currentList.none { it.host == pc.host }) {
                    currentList.add(pc)
                    _discoveredServices.value = currentList
                }
            }

            resolveNextPending()
        }
    }

    fun startDiscovery() {
        if (_isSearching.value) return

        _discoveredServices.value = emptyList()
        _isSearching.value = true

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _isSearching.value = true
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _isSearching.value = false
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == SERVICE_TYPE ||
                    serviceInfo.serviceName.contains("MeoMic", ignoreCase = true)) {
                    // Queue for resolution
                    synchronized(pendingResolves) {
                        pendingResolves.add(serviceInfo)
                        if (pendingResolves.size == 1) {
                            resolveNextPending()
                        }
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val currentList = _discoveredServices.value.toMutableList()
                currentList.removeAll { it.name == serviceInfo.serviceName }
                _discoveredServices.value = currentList
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isSearching.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isSearching.value = false
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            _isSearching.value = false
        }
    }

    private fun resolveNextPending() {
        synchronized(pendingResolves) {
            if (pendingResolves.isNotEmpty()) {
                val next = pendingResolves.removeAt(0)
                try {
                    nsdManager.resolveService(next, resolveListener)
                } catch (e: Exception) {
                    resolveNextPending()
                }
            }
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                // Already stopped
            }
        }
        discoveryListener = null
        _isSearching.value = false
        pendingResolves.clear()
    }

    fun clearDiscoveredServices() {
        _discoveredServices.value = emptyList()
    }
}

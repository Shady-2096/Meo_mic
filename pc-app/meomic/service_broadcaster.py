"""
mDNS/Zeroconf Service Broadcaster

Broadcasts the MeoMic service so Android devices can auto-discover the PC.
Uses the same service type as the Android NSD discovery: "_meomic._udp.local."
"""

import socket
import platform
from typing import Optional
from zeroconf import ServiceInfo, Zeroconf


class ServiceBroadcaster:
    SERVICE_TYPE = "_meomic._udp.local."
    SERVICE_NAME = "MeoMic"

    def __init__(self, port: int = 48888):
        self.port = port
        self.zeroconf: Optional[Zeroconf] = None
        self.service_info: Optional[ServiceInfo] = None
        self.local_ip: Optional[str] = None

    def start(self):
        """Start broadcasting the service."""
        self.local_ip = self._get_local_ip()
        if not self.local_ip:
            print("[Broadcaster] Warning: Could not determine local IP")
            return

        hostname = platform.node()
        service_name = f"{self.SERVICE_NAME} ({hostname}).{self.SERVICE_TYPE}"

        self.service_info = ServiceInfo(
            self.SERVICE_TYPE,
            service_name,
            addresses=[socket.inet_aton(self.local_ip)],
            port=self.port,
            properties={
                'version': '1',
                'platform': platform.system(),
                'hostname': hostname,
            },
            server=f"{hostname}.local.",
        )

        self.zeroconf = Zeroconf()
        self.zeroconf.register_service(self.service_info)

        print(f"[Broadcaster] Service registered: {service_name}")
        print(f"[Broadcaster] IP: {self.local_ip}, Port: {self.port}")

    def stop(self):
        """Stop broadcasting the service."""
        if self.zeroconf and self.service_info:
            self.zeroconf.unregister_service(self.service_info)
            self.zeroconf.close()
            self.zeroconf = None
            self.service_info = None
            print("[Broadcaster] Service unregistered")

    def _get_local_ip(self) -> Optional[str]:
        """Get the local IP address that's most likely on the LAN."""
        try:
            # Create a socket and connect to an external address
            # This doesn't actually send data, just determines the route
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(0.1)
            try:
                s.connect(('8.8.8.8', 80))
                ip = s.getsockname()[0]
            finally:
                s.close()
            return ip
        except Exception:
            pass

        # Fallback: try to get from hostname
        try:
            hostname = socket.gethostname()
            ip = socket.gethostbyname(hostname)
            if not ip.startswith('127.'):
                return ip
        except Exception:
            pass

        # Last resort: enumerate interfaces
        try:
            import psutil
            for iface, addrs in psutil.net_if_addrs().items():
                for addr in addrs:
                    if addr.family == socket.AF_INET:
                        ip = addr.address
                        if not ip.startswith('127.') and not ip.startswith('169.254.'):
                            return ip
        except Exception:
            pass

        return None

    def get_local_ip(self) -> Optional[str]:
        """Get the currently detected local IP."""
        return self.local_ip

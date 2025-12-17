"""
UDP Audio Receiver

Receives audio packets from the Android app and decodes them.
Packet format matches the Android UdpAudioStreamer:
- Header (8 bytes):
  - Magic bytes: "WM" (2 bytes)
  - Version: 1 byte
  - Packet type: 1 byte (0=audio, 1=keepalive, 2=disconnect)
  - Sequence number: 4 bytes
- Payload: PCM audio data (16-bit, 48kHz, mono)
"""

import socket
import struct
import threading
import time
from typing import Callable, Optional
from dataclasses import dataclass
from enum import IntEnum


class PacketType(IntEnum):
    AUDIO = 0
    KEEPALIVE = 1
    DISCONNECT = 2
    ACK = 3


@dataclass
class AudioPacket:
    sequence: int
    packet_type: PacketType
    audio_data: bytes


class UdpAudioReceiver:
    MAGIC = b'WM'
    HEADER_SIZE = 8
    DEFAULT_PORT = 48888
    BUFFER_SIZE = 65536

    def __init__(self, port: int = DEFAULT_PORT):
        self.port = port
        self.socket: Optional[socket.socket] = None
        self.running = False
        self.receive_thread: Optional[threading.Thread] = None

        # Callbacks
        self.on_audio_data: Optional[Callable[[bytes], None]] = None
        self.on_client_connected: Optional[Callable[[str], None]] = None
        self.on_client_disconnected: Optional[Callable[[], None]] = None

        # Connection state
        self.client_address: Optional[tuple] = None
        self.last_packet_time: float = 0
        self.last_ack_time: float = 0
        self.packets_received: int = 0
        self.last_sequence: int = -1
        self.packets_lost: int = 0
        self.ack_sequence: int = 0

    def start(self):
        """Start the UDP receiver."""
        if self.running:
            return

        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, self.BUFFER_SIZE)
        self.socket.settimeout(1.0)  # 1 second timeout for clean shutdown
        self.socket.bind(('0.0.0.0', self.port))

        self.running = True
        self.receive_thread = threading.Thread(target=self._receive_loop, daemon=True)
        self.receive_thread.start()

        print(f"[Receiver] Listening on port {self.port}")

    def stop(self):
        """Stop the UDP receiver."""
        self.running = False
        if self.receive_thread:
            self.receive_thread.join(timeout=2.0)
        if self.socket:
            self.socket.close()
            self.socket = None
        print("[Receiver] Stopped")

    def _receive_loop(self):
        """Main receive loop running in a separate thread."""
        while self.running:
            try:
                data, addr = self.socket.recvfrom(self.BUFFER_SIZE)
                self._handle_packet(data, addr)
            except socket.timeout:
                # Check for client timeout (no packets for 5 seconds)
                if self.client_address and time.time() - self.last_packet_time > 5.0:
                    self._handle_disconnect()
            except OSError:
                # Socket closed
                break
            except Exception as e:
                print(f"[Receiver] Error: {e}")

    def _handle_packet(self, data: bytes, addr: tuple):
        """Parse and handle an incoming packet."""
        if len(data) < self.HEADER_SIZE:
            return

        # Parse header
        magic = data[0:2]
        if magic != self.MAGIC:
            return

        version = data[2]
        packet_type = data[3]
        sequence = struct.unpack('>I', data[4:8])[0]

        # Track new client connection
        if self.client_address != addr:
            self.client_address = addr
            self.last_sequence = -1
            self.packets_lost = 0
            self.packets_received = 0
            print(f"[Receiver] Client connected: {addr[0]}:{addr[1]}")
            if self.on_client_connected:
                self.on_client_connected(addr[0])

        self.last_packet_time = time.time()
        self.packets_received += 1

        # Track packet loss
        if self.last_sequence >= 0:
            expected = (self.last_sequence + 1) & 0xFFFFFFFF
            if sequence != expected:
                lost = (sequence - expected) & 0xFFFFFFFF
                if lost < 1000:  # Sanity check
                    self.packets_lost += lost
        self.last_sequence = sequence

        # Handle by packet type
        if packet_type == PacketType.AUDIO:
            audio_data = data[self.HEADER_SIZE:]
            if audio_data and self.on_audio_data:
                self.on_audio_data(audio_data)

            # Send periodic ACKs during streaming (every 500ms)
            current_time = time.time()
            if current_time - self.last_ack_time > 0.5:
                self._send_ack(addr)
                self.last_ack_time = current_time

        elif packet_type == PacketType.KEEPALIVE:
            # Send ACK back for connection verification
            self._send_ack(addr)

        elif packet_type == PacketType.DISCONNECT:
            self._handle_disconnect()

    def _send_ack(self, addr: tuple):
        """Send acknowledgment packet back to client."""
        if self.socket:
            try:
                # Build proper ACK packet with header
                # Format: Magic (2) + Version (1) + Type (1) + Sequence (4)
                ack_packet = struct.pack(
                    '>2sBBI',
                    self.MAGIC,
                    1,  # Version
                    PacketType.ACK,
                    self.ack_sequence
                )
                self.ack_sequence = (self.ack_sequence + 1) & 0xFFFFFFFF
                self.socket.sendto(ack_packet, addr)
            except Exception:
                pass

    def _handle_disconnect(self):
        """Handle client disconnect."""
        if self.client_address:
            print(f"[Receiver] Client disconnected")
            self.client_address = None
            if self.on_client_disconnected:
                self.on_client_disconnected()

    def get_stats(self) -> dict:
        """Get receiver statistics."""
        return {
            'connected': self.client_address is not None,
            'client_ip': self.client_address[0] if self.client_address else None,
            'packets_received': self.packets_received,
            'packets_lost': self.packets_lost,
            'loss_rate': self.packets_lost / max(1, self.packets_received + self.packets_lost) * 100
        }

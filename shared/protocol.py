"""
AeroCast Native Protocol & Network Definitions
Shared packet schemas, magic headers, network ports, and checksum verification.
Compatible with Windows PyQt6 Host and Android Kotlin Client.
"""

import os
import sys
import json
import socket
import hashlib
from pathlib import Path
from typing import Dict, Any, Optional, Tuple

# Network Ports
UDP_BEACON_PORT = 42424    # UDP Subnet Broadcast Signaling
TCP_TRANSFER_PORT = 42425  # High-Speed Raw TCP File Streaming
IPC_LOCAL_PORT = 42426     # Windows Explorer Context Menu IPC
BT_DEFAULT_RFCOMM_PORT = 5 # Standard fallback RFCOMM channel on Windows/Android
BT_SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB" # Standard SerialPortServiceClass UUID

# Protocol Constants
MAGIC_HEADER = b"AERO_CAST_V1"      # 12-byte standard magic header
MAGIC_HEADER_V1 = b"AERO_CAST_V1"
AERO_CAST_V1 = b"AERO_CAST_V1"
MAGIC_HEADER_LEGACY = b"AEROCAST\x02"
MAGIC_HEADER_LEN = 12

CHUNK_SIZE = 64 * 1024     # 64 KB streaming buffers
BROADCAST_ADDR = "255.255.255.255"

# Binary Frame Message Types (1 Byte)
MSG_TYPE_DISCOVERY = 0x01
MSG_TYPE_DISCOVERY_ACK = 0x02
MSG_TYPE_STAGE_ARMED = 0x03
MSG_TYPE_DROP_CONFIRMED = 0x04
MSG_TYPE_FILE_HEADER = 0x05
MSG_TYPE_FILE_DATA = 0x06
MSG_TYPE_FILE_COMPLETE = 0x07
MSG_TYPE_CANCEL = 0x08
MSG_TYPE_PULL = 0x09
MSG_TYPE_PEER_INFO = 0x0A

# String aliases for JSON beacons
MSG_DISCOVERY_BEACON = "DISCOVERY"
MSG_DISCOVERY_ACK = "DISCOVERY_ACK"
MSG_STAGE_ARMED = "ARMED_DROP"
MSG_DROP_CONFIRMED = "DROP_CONFIRMED"
MSG_CANCEL = "CANCEL"
MSG_FILE_HEADER = "FILE_HEADER"
MSG_FILE_DATA = "FILE_DATA"
MSG_FILE_COMPLETE = "FILE_COMPLETE"
MSG_PEER_INFO = "PEER_INFO"

# Beacon Event Aliases (for backwards compatibility)
EVENT_DISCOVERY = MSG_DISCOVERY_BEACON
EVENT_ARMED_DROP = MSG_STAGE_ARMED
EVENT_DROP_CONFIRMED = MSG_DROP_CONFIRMED
EVENT_CANCEL = MSG_CANCEL
EVENT_HEARTBEAT = "HEARTBEAT"

# Transfer Senders / Directions
SENDER_MOBILE = "MOBILE"
SENDER_LAPTOP = "LAPTOP"
DIR_PHONE_TO_PC = "PHONE_TO_PC"
DIR_PC_TO_PHONE = "PC_TO_PHONE"


def encode_frame(msg_type: int, payload: bytes) -> bytes:
    """
    Encodes standard AeroCast frame:
    [12B Header] + [1B MsgType] + [4B PayloadLen (Big-Endian)] + [Payload]
    """
    return MAGIC_HEADER + bytes([msg_type]) + len(payload).to_bytes(4, byteorder="big") + payload


def decode_frame_header(data: bytes) -> Optional[Tuple[int, int]]:
    """
    Decodes a 17-byte frame header:
    Returns (msg_type, payload_length) or None if invalid.
    """
    if len(data) < 17:
        return None
    magic = data[:12]
    if magic != MAGIC_HEADER and magic != MAGIC_HEADER_V1:
        return None
    msg_type = data[12]
    payload_len = int.from_bytes(data[13:17], byteorder="big")
    return (msg_type, payload_len)


def get_local_ip() -> str:
    """Discovers host LAN IP address via UDP socket with strict timeout and hostname fallback."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.3)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127."):
            return ip
    except Exception:
        pass

    try:
        hostname = socket.gethostname()
        ip = socket.gethostbyname(hostname)
        if ip and not ip.startswith("127."):
            return ip
    except Exception:
        pass

    return "127.0.0.1"


def get_broadcast_ip() -> str:
    """Returns the broadcast IP for the local subnet."""
    local_ip = get_local_ip()
    if local_ip.startswith("192.168.") or local_ip.startswith("10.") or local_ip.startswith("172."):
        parts = local_ip.split(".")
        return f"{parts[0]}.{parts[1]}.{parts[2]}.255"
    return BROADCAST_ADDR


def get_default_download_dir() -> Path:
    """Returns native Download/AeroCast path based on OS."""
    if sys.platform == "win32":
        path = Path.home() / "Downloads" / "AeroCast"
    elif "android" in sys.platform.lower() or os.path.exists("/sdcard"):
        path = Path("/sdcard/Download/AeroCast")
    else:
        path = Path.home() / "Downloads" / "AeroCast"
    path.mkdir(parents=True, exist_ok=True)
    return path


def compute_file_sha256(filepath: str, chunk_size: int = CHUNK_SIZE) -> str:
    """Computes SHA-256 checksum for end-to-end file integrity validation."""
    hasher = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(chunk_size):
            hasher.update(chunk)
    return hasher.hexdigest()


def make_beacon(
    event: str,
    device_name: str,
    sender: str = SENDER_LAPTOP,
    filename: Optional[str] = None,
    size: Optional[int] = None,
    file_hash: Optional[str] = None,
    tcp_port: int = TCP_TRANSFER_PORT,
    rfcomm_port: int = BT_DEFAULT_RFCOMM_PORT,
    extra: Optional[Dict[str, Any]] = None
) -> bytes:
    """Encodes a JSON UDP beacon packet adhering to AeroCast specification."""
    payload: Dict[str, Any] = {
        "proto": "AEROCAST_V2_NATIVE",
        "proto_v1": "AERO_CAST_V1",
        "event": event,
        "sender": sender,
        "device_name": device_name,
        "sender_ip": get_local_ip(),
        "tcp_port": tcp_port,
        "rfcomm_port": rfcomm_port,
        "rfcomm_uuid": BT_SPP_UUID
    }
    if filename:
        payload["filename"] = filename
    if size is not None:
        payload["size"] = size
        payload["filesize"] = size
    if file_hash:
        payload["sha256"] = file_hash
        payload["file_hash"] = file_hash
    if extra:
        payload.update(extra)

    return json.dumps(payload).encode("utf-8")


def parse_beacon(data: bytes) -> Optional[Dict[str, Any]]:
    """Decodes and validates a UDP beacon packet, normalizing schema fields."""
    try:
        decoded = json.loads(data.decode("utf-8"))
        proto = decoded.get("proto") or decoded.get("proto_v1")
        if proto in ("AEROCAST_V2_NATIVE", "AERO_CAST_V1", "AEROCAST_V1"):
            # Normalize size fields
            if "size" not in decoded and "filesize" in decoded:
                decoded["size"] = decoded["filesize"]
            elif "filesize" not in decoded and "size" in decoded:
                decoded["filesize"] = decoded["size"]

            # Normalize hash fields
            if "sha256" not in decoded and "file_hash" in decoded:
                decoded["sha256"] = decoded["file_hash"]
            elif "file_hash" not in decoded and "sha256" in decoded:
                decoded["file_hash"] = decoded["sha256"]

            # Normalize event aliases
            event = decoded.get("event")
            if event == "MSG_STAGE_ARMED":
                decoded["event"] = MSG_STAGE_ARMED
            elif event == "MSG_DISCOVERY_BEACON":
                decoded["event"] = MSG_DISCOVERY_BEACON
            elif event == "MSG_DISCOVERY_ACK":
                decoded["event"] = MSG_DISCOVERY_ACK
            elif event == "MSG_DROP_CONFIRMED":
                decoded["event"] = MSG_DROP_CONFIRMED

            return decoded
        return None
    except Exception:
        return None

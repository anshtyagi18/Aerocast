"""
AeroCast Hybrid Network Service (Wi-Fi TCP + Bluetooth RFCOMM Fallback)
Simultaneous discovery and transfer engine:
1. Wi-Fi: UDP subnet broadcast (255.255.255.255:42424) + High-Speed Raw TCP (0.0.0.0:42425).
2. Bluetooth RFCOMM: Zero-config fallback listener (UUID 00001101-0000-1000-8000-00805F9B34FB)
   and transmitter to bypass router Wi-Fi AP isolation and Windows Firewall blocks.
3. Live SHA-256 checksum verification, chunk streaming, and b"OK" handshake.
"""

import os
import sys
import time
import json
import socket
import hashlib
import threading
from pathlib import Path
from typing import Optional, Callable, Dict, Any, Tuple, List

SHARED_DIR = Path(__file__).resolve().parent.parent.parent / "shared"
if str(SHARED_DIR) not in sys.path:
    sys.path.insert(0, str(SHARED_DIR))

from protocol import (
    UDP_BEACON_PORT,
    TCP_TRANSFER_PORT,
    IPC_LOCAL_PORT,
    BT_DEFAULT_RFCOMM_PORT,
    BT_SPP_UUID,
    MAGIC_HEADER,
    MAGIC_HEADER_V1,
    MAGIC_HEADER_LEGACY,
    CHUNK_SIZE,
    MSG_TYPE_DISCOVERY,
    MSG_TYPE_DISCOVERY_ACK,
    MSG_TYPE_STAGE_ARMED,
    MSG_TYPE_DROP_CONFIRMED,
    MSG_TYPE_FILE_HEADER,
    MSG_TYPE_FILE_DATA,
    MSG_TYPE_FILE_COMPLETE,
    MSG_TYPE_CANCEL,
    MSG_TYPE_PULL,
    MSG_TYPE_PEER_INFO,
    MSG_DISCOVERY_BEACON,
    MSG_DISCOVERY_ACK,
    MSG_STAGE_ARMED,
    MSG_DROP_CONFIRMED,
    MSG_FILE_HEADER,
    MSG_FILE_DATA,
    MSG_FILE_COMPLETE,
    MSG_CANCEL,
    EVENT_ARMED_DROP,
    EVENT_DROP_CONFIRMED,
    EVENT_CANCEL,
    EVENT_DISCOVERY,
    SENDER_MOBILE,
    SENDER_LAPTOP,
    get_local_ip,
    get_broadcast_ip,
    get_default_download_dir,
    compute_file_sha256,
    make_beacon,
    parse_beacon,
    encode_frame,
    decode_frame_header
)


def get_paired_bluetooth_devices() -> List[Tuple[str, str]]:
    """
    Discovers paired Bluetooth devices from Windows registry.
    Returns list of (DeviceName, MAC_Address) tuples formatted as 'AA:BB:CC:DD:EE:FF'.
    """
    devices = []
    if sys.platform != "win32":
        return devices

    try:
        import winreg
        key_path = r"SYSTEM\CurrentControlSet\Services\BTHPORT\Parameters\Devices"
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, key_path) as key:
            num_subkeys, _, _ = winreg.QueryInfoKey(key)
            for i in range(num_subkeys):
                sub_name = winreg.EnumKey(key, i)
                if len(sub_name) != 12:
                    continue
                dev_name = "Bluetooth Device"
                try:
                    with winreg.OpenKey(key, sub_name) as sub_key:
                        raw_name, _ = winreg.QueryValueEx(sub_key, "Name")
                        if isinstance(raw_name, bytes):
                            dev_name = raw_name.decode("utf-8", errors="ignore").rstrip("\x00")
                        elif isinstance(raw_name, str):
                            dev_name = raw_name.rstrip("\x00")
                except Exception:
                    pass

                # Format mac address
                mac = ":".join(sub_name[j:j+2] for j in range(0, 12, 2)).upper()
                devices.append((dev_name, mac))
    except Exception as e:
        print(f"[AeroCast Net] Bluetooth registry enumeration: {e}", file=sys.stderr)

    return devices


class NetworkService:
    """
    Coordinates UDP signaling, TCP high-speed chunk streaming, Bluetooth RFCOMM fallback,
    and Explorer context menu IPC.
    """
    def __init__(
        self,
        on_armed_drop_received: Optional[Callable[[Dict[str, Any]], None]] = None,
        on_drop_confirmed_received: Optional[Callable[[Dict[str, Any]], None]] = None,
        on_transfer_progress: Optional[Callable[[str, float, float], None]] = None, # filename, pct, speed_mbps
        on_transfer_complete: Optional[Callable[[str, Path], None]] = None,
        on_stage_file_requested: Optional[Callable[[str], None]] = None,
        on_discovery_received: Optional[Callable[[Dict[str, Any], Tuple[str, int]], None]] = None,
        on_discovery_ack: Optional[Callable[[Dict[str, Any], Tuple[str, int]], None]] = None,
        file_received: Optional[Callable[[str, Path], None]] = None,
        incoming_transfer_staged: Optional[Callable[[str, int, str], None]] = None
    ):
        self.on_armed_drop_received = on_armed_drop_received
        self.on_drop_confirmed_received = on_drop_confirmed_received
        self.on_transfer_progress = on_transfer_progress
        self.on_transfer_complete = on_transfer_complete
        self.on_stage_file_requested = on_stage_file_requested
        self.on_discovery_received = on_discovery_received
        self.on_discovery_ack = on_discovery_ack
        self.file_received = file_received or on_transfer_complete
        self.incoming_transfer_staged = incoming_transfer_staged

        self._running = False
        self._udp_sock: Optional[socket.socket] = None
        self._tcp_server: Optional[socket.socket] = None
        self._bt_server: Optional[socket.socket] = None
        self._ipc_server: Optional[socket.socket] = None

        self._udp_thread: Optional[threading.Thread] = None
        self._tcp_thread: Optional[threading.Thread] = None
        self._bt_thread: Optional[threading.Thread] = None
        self._ipc_thread: Optional[threading.Thread] = None

        # Staged file info (Laptop -> Mobile)
        self._staged_file: Optional[Path] = None
        self._staged_hash: Optional[str] = None
        self._staged_lock = threading.Lock()
        self._stage_ack_received = threading.Event()

        # Last incoming transfer metadata (Mobile -> Laptop)
        self._incoming_meta: Optional[Dict[str, Any]] = None

        # Discovered peers {ip: packet}
        self.discovered_peers: Dict[str, Dict[str, Any]] = {}
        self.rfcomm_port = BT_DEFAULT_RFCOMM_PORT

    def start(self):
        """Starts all network listeners simultaneously."""
        self._running = True
        self._start_udp_listener()
        self._start_tcp_server()
        self._start_bluetooth_server()
        self._start_ipc_server()
        self._start_discovery_loop()

    def _start_discovery_loop(self):
        """Periodically announces presence on LAN so both devices constantly know each other's IP."""
        def worker():
            while self._running:
                try:
                    self.broadcast_discovery()
                except Exception:
                    pass
                time.sleep(3.5)
        threading.Thread(target=worker, daemon=True, name="AeroCastDiscoveryLoop").start()

    def stop(self):
        """Stops all background listeners cleanly."""
        self._running = False
        self.unstage_file()
        for s in (self._udp_sock, self._tcp_server, self._bt_server, self._ipc_server):
            if s:
                try:
                    s.close()
                except Exception:
                    pass

    # ================= 1. UDP BEACON SIGNALING =================
    def _start_udp_listener(self):
        def worker():
            try:
                self._udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                self._udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                self._udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                self._udp_sock.bind(("0.0.0.0", UDP_BEACON_PORT))
                self._udp_sock.settimeout(1.0)
            except Exception as e:
                print(f"[AeroCast Net] UDP Bind error: {e}", file=sys.stderr)
                return

            while self._running:
                try:
                    data, addr = self._udp_sock.recvfrom(4096)
                    self._process_udp(data, addr)
                except socket.timeout:
                    continue
                except Exception:
                    if self._running:
                        time.sleep(0.05)

        self._udp_thread = threading.Thread(target=worker, daemon=True, name="AeroCastUDP")
        self._udp_thread.start()

    def _process_udp(self, data: bytes, addr: Tuple[str, int]):
        """Processes incoming UDP beacons."""
        local_ip = get_local_ip()
        if addr[0] == local_ip:
            return

        packet = parse_beacon(data)
        if not packet:
            return

        event = packet.get("event")
        sender = packet.get("sender")

        # 1. DISCOVERY BEACON
        if event in (MSG_DISCOVERY_BEACON, "DISCOVERY"):
            self.discovered_peers[addr[0]] = packet
            self._send_udp_ack(addr[0])
            if self.on_discovery_received:
                self.on_discovery_received(packet, addr)

        # 2. DISCOVERY ACK
        elif event in (MSG_DISCOVERY_ACK, "DISCOVERY_ACK"):
            self.discovered_peers[addr[0]] = packet
            self._stage_ack_received.set()
            if self.on_discovery_ack:
                self.on_discovery_ack(packet, addr)

        # 3. STAGE ARMED (File casted into air from phone)
        elif event in (MSG_STAGE_ARMED, EVENT_ARMED_DROP, "MSG_STAGE_ARMED"):
            if sender != SENDER_LAPTOP:
                self._incoming_meta = packet
                filename = packet.get("filename", "Unknown File")
                size = int(packet.get("size", packet.get("filesize", 0)))
                sender_name = packet.get("device_name", "Mobile Device")

                # High-priority incoming transfer staged callback
                if self.incoming_transfer_staged:
                    self.incoming_transfer_staged(filename, size, sender_name)
                if self.on_armed_drop_received:
                    self.on_armed_drop_received(packet)

        # 4. DROP CONFIRMED (Receiver confirmed palm gesture)
        elif event in (MSG_DROP_CONFIRMED, EVENT_DROP_CONFIRMED, "MSG_DROP_CONFIRMED"):
            self._stage_ack_received.set()
            if self.on_drop_confirmed_received:
                self.on_drop_confirmed_received(packet)

    def _send_udp_ack(self, target_ip: str):
        """Sends MSG_DISCOVERY_ACK directly to peer."""
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            ack_data = make_beacon(
                event=MSG_DISCOVERY_ACK,
                device_name=socket.gethostname(),
                sender=SENDER_LAPTOP,
                tcp_port=TCP_TRANSFER_PORT,
                rfcomm_port=self.rfcomm_port
            )
            sock.sendto(ack_data, (target_ip, UDP_BEACON_PORT))
            sock.close()
        except Exception as e:
            print(f"[AeroCast Net] Error sending discovery ACK: {e}", file=sys.stderr)

    def broadcast_beacon(
        self,
        event: str,
        filename: Optional[str] = None,
        size: Optional[int] = None,
        file_hash: Optional[str] = None,
        extra: Optional[Dict[str, Any]] = None
    ):
        """Broadcasts a UDP beacon to the subnet and directly to known peer IPs."""
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            bcast_ip = get_broadcast_ip()

            data = make_beacon(
                event=event,
                device_name=socket.gethostname(),
                sender=SENDER_LAPTOP,
                filename=filename,
                size=size,
                file_hash=file_hash,
                tcp_port=TCP_TRANSFER_PORT,
                rfcomm_port=self.rfcomm_port,
                extra=extra
            )
            # 1. Subnet broadcast and generic 255.255.255.255
            sock.sendto(data, (bcast_ip, UDP_BEACON_PORT))
            sock.sendto(data, ("255.255.255.255", UDP_BEACON_PORT))

            # 2. Direct Unicast to all discovered peer IPs (bypasses router AP isolation completely)
            for peer_ip in list(self.discovered_peers.keys()):
                try:
                    sock.sendto(data, (peer_ip, UDP_BEACON_PORT))
                except Exception:
                    pass
            sock.close()
        except Exception as e:
            print(f"[AeroCast Net] Broadcast error: {e}", file=sys.stderr)

    def broadcast_discovery(self):
        """Broadcasts discovery beacon to locate peers on LAN."""
        self.broadcast_beacon(event=MSG_DISCOVERY_BEACON)

    def stage_file(self, filepath: str) -> bool:
        """
        Stages a local file and launches continuous background beaconing.
        Alerts Mobile repeatedly every 1s until file transfer completes or is cancelled.
        """
        path = Path(filepath)
        if not path.is_file():
            return False

        filesize = path.stat().st_size
        file_hash = compute_file_sha256(str(path))

        with self._staged_lock:
            self._staged_file = path
            self._staged_hash = file_hash

        self._stage_ack_received.clear()

        def beacon_worker():
            print(f"[AeroCast Net] Staged '{path.name}' ({filesize} B). Starting continuous broadcast...")
            # Trigger Bluetooth fallback check once in background
            threading.Thread(
                target=self._bluetooth_fallback_check,
                args=(path.name, filesize, file_hash),
                daemon=True,
                name="AeroCastBTFallback"
            ).start()

            while self._running:
                with self._staged_lock:
                    if self._staged_file != path:
                        break
                self.broadcast_beacon(
                    event=MSG_STAGE_ARMED,
                    filename=path.name,
                    size=filesize,
                    file_hash=file_hash
                )
                time.sleep(1.0)

        self._beacon_thread = threading.Thread(target=beacon_worker, daemon=True, name="AeroCastBeaconRepeat")
        self._beacon_thread.start()
        return True

    def unstage_file(self):
        """Cancels active staged file and stops repeating beacon broadcasts."""
        with self._staged_lock:
            self._staged_file = None
            self._staged_hash = None

    def broadcast_armed_drop_laptop(self, filepath: str) -> bool:
        """Legacy alias - stages file and starts continuous beaconing."""
        return self.stage_file(filepath)

    def _bluetooth_fallback_check(self, filename: str, filesize: int, file_hash: str):
        """
        Checks if UDP beacon received an ACK within 1.5 seconds.
        If blocked by router AP isolation / Windows Firewall, immediately connects via Bluetooth RFCOMM.
        """
        # Wait 1.5 seconds for UDP response
        if self._stage_ack_received.wait(timeout=1.5):
            print("[AeroCast Net] Primary Wi-Fi UDP beacon acknowledged.")
            return

        print("[AeroCast Net] No UDP ACK within 1.5s. Activating Bluetooth RFCOMM fallback...")
        paired_devices = get_paired_bluetooth_devices()
        if not paired_devices:
            print("[AeroCast Net] No paired Bluetooth devices found for fallback.")
            return

        payload_bytes = json.dumps({
            "proto": "AEROCAST_V2_NATIVE",
            "event": MSG_STAGE_ARMED,
            "sender": SENDER_LAPTOP,
            "device_name": socket.gethostname(),
            "sender_ip": get_local_ip(),
            "tcp_port": TCP_TRANSFER_PORT,
            "filename": filename,
            "size": filesize,
            "sha256": file_hash
        }).encode("utf-8")
        frame = encode_frame(MSG_TYPE_STAGE_ARMED, payload_bytes)

        for dev_name, mac in paired_devices:
            for channel in (1, 2, 3, 4, 5):
                try:
                    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
                    s.settimeout(2.0)
                    s.connect((mac, channel))
                    s.sendall(frame)
                    s.close()
                    print(f"[AeroCast Net] Dispatched stage beacon to {dev_name} ({mac}) on RFCOMM ch {channel}")
                    return
                except Exception:
                    try:
                        s.close()
                    except Exception:
                        pass

    def broadcast_drop_confirmed(self, target_ip: Optional[str] = None):
        """Sends MSG_DROP_CONFIRMED to signal peer to push/pull stream."""
        self.broadcast_beacon(event=MSG_DROP_CONFIRMED)
        if target_ip:
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                sock.sendto(
                    make_beacon(
                        event=MSG_DROP_CONFIRMED,
                        device_name=socket.gethostname(),
                        sender=SENDER_LAPTOP
                    ),
                    (target_ip, UDP_BEACON_PORT)
                )
                sock.close()
            except Exception:
                pass

    # ================= 2. BLUETOOTH RFCOMM SERVER =================
    def _start_bluetooth_server(self):
        """Starts background Bluetooth RFCOMM listener for zero-config fallback transfers."""
        def worker():
            if not hasattr(socket, "AF_BLUETOOTH") or not hasattr(socket, "BTPROTO_RFCOMM"):
                return

            for ch in (5, 4, 3, 2, 1, 6, 7, 8, 9, 10, 0):
                try:
                    self._bt_server = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
                    self._bt_server.bind(("00:00:00:00:00:00", ch))
                    self._bt_server.listen(2)
                    self._bt_server.settimeout(1.0)
                    actual_addr, actual_port = self._bt_server.getsockname()
                    self.rfcomm_port = actual_port
                    print(f"[AeroCast Net] Bluetooth RFCOMM listener active on channel {self.rfcomm_port}")
                    break
                except Exception:
                    if self._bt_server:
                        try: self._bt_server.close()
                        except Exception: pass
                    self._bt_server = None

            if not self._bt_server:
                print("[AeroCast Net] Could not bind Bluetooth RFCOMM listener", file=sys.stderr)
                return

            while self._running:
                try:
                    conn, addr = self._bt_server.accept()
                    threading.Thread(
                        target=self._handle_rfcomm_client,
                        args=(conn, addr),
                        daemon=True
                    ).start()
                except socket.timeout:
                    continue
                except Exception:
                    if self._running:
                        time.sleep(0.05)

        self._bt_thread = threading.Thread(target=worker, daemon=True, name="AeroCastBTServer")
        self._bt_thread.start()

    def _handle_rfcomm_client(self, conn: socket.socket, addr: Any):
        """Handles incoming Bluetooth RFCOMM socket connection from Mobile."""
        conn.settimeout(20.0)
        try:
            # Check for standard frame: [12B Header] + [1B MsgType] + [4B PayloadLen]
            header_bytes = self._recv_all(conn, 17)
            if not header_bytes:
                return

            frame_info = decode_frame_header(header_bytes)
            if not frame_info:
                # Fallback to legacy length prefix
                magic = header_bytes[:12]
                if magic in (MAGIC_HEADER, MAGIC_HEADER_V1, MAGIC_HEADER_LEGACY):
                    meta_len = int.from_bytes(header_bytes[12:16], byteorder="big")
                    msg_type = MSG_TYPE_FILE_HEADER
                else:
                    return
            else:
                msg_type, meta_len = frame_info

            meta_data = self._recv_all(conn, meta_len)
            if not meta_data:
                return

            meta = json.loads(meta_data.decode("utf-8"))
            action = meta.get("action", "")

            # 1. Incoming Staged File via Bluetooth
            if msg_type == MSG_TYPE_STAGE_ARMED or meta.get("event") == MSG_STAGE_ARMED:
                self._incoming_meta = meta
                filename = meta.get("filename", "Unknown File")
                size = int(meta.get("size", meta.get("filesize", 0)))
                sender_name = meta.get("device_name", "Mobile Bluetooth")

                if self.incoming_transfer_staged:
                    self.incoming_transfer_staged(filename, size, sender_name)
                if self.on_armed_drop_received:
                    self.on_armed_drop_received(meta)

            # 2. Pull Request over Bluetooth
            elif action == "PULL":
                with self._staged_lock:
                    staged_path = self._staged_file
                if staged_path and staged_path.exists():
                    self._send_file_stream(conn, staged_path, is_bluetooth=True)

            # 3. Direct File Push over Bluetooth
            else:
                self._receive_file_stream(conn, meta, is_bluetooth=True)

        except Exception as e:
            print(f"[AeroCast Net] Bluetooth RFCOMM client error: {e}", file=sys.stderr)
        finally:
            try: conn.close()
            except Exception: pass

    # ================= 3. TCP STREAMING (RECEIVER & TRANSMITTER) =================
    @staticmethod
    def _recv_all(sock: socket.socket, num_bytes: int) -> Optional[bytes]:
        """Reads exactly num_bytes from stream, preventing partial packets."""
        data = bytearray()
        while len(data) < num_bytes:
            packet = sock.recv(num_bytes - len(data))
            if not packet:
                return None
            data.extend(packet)
        return bytes(data)

    def _start_tcp_server(self):
        """Starts TCP server on 0.0.0.0:42425."""
        def worker():
            try:
                self._tcp_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                self._tcp_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                self._tcp_server.bind(("0.0.0.0", TCP_TRANSFER_PORT))
                self._tcp_server.listen(5)
                self._tcp_server.settimeout(1.0)
            except Exception as e:
                print(f"[AeroCast Net] TCP Server bind error: {e}", file=sys.stderr)
                return

            while self._running:
                try:
                    conn, addr = self._tcp_server.accept()
                    threading.Thread(
                        target=self._handle_tcp_client,
                        args=(conn, addr),
                        daemon=True
                    ).start()
                except socket.timeout:
                    continue
                except Exception:
                    if self._running:
                        time.sleep(0.05)

        self._tcp_thread = threading.Thread(target=worker, daemon=True, name="AeroCastTCPServer")
        self._tcp_thread.start()

    def _handle_tcp_client(self, conn: socket.socket, addr: Tuple[str, int]):
        """Handles incoming TCP connections."""
        conn.settimeout(25.0)
        try:
            # Read 17-byte standard frame header
            header = self._recv_all(conn, 17)
            if not header:
                conn.close()
                return

            magic = header[:12]
            if magic not in (MAGIC_HEADER, MAGIC_HEADER_V1, MAGIC_HEADER_LEGACY, b"AERO_CAST_V1"):
                conn.close()
                return

            msg_type = header[12]
            meta_len = int.from_bytes(header[13:17], byteorder="big")
            if meta_len > 1_000_000:
                # 16-byte fallback
                meta_len = int.from_bytes(header[12:16], byteorder="big")
                meta_bytes = header[16:17] + (self._recv_all(conn, meta_len - 1) or b"")
            else:
                meta_bytes = self._recv_all(conn, meta_len)

            if not meta_bytes:
                conn.close()
                return

            meta = json.loads(meta_bytes.decode("utf-8"))
            action = meta.get("action", "SEND")

            if action == "PULL":
                with self._staged_lock:
                    staged_path = self._staged_file
                if staged_path and staged_path.exists():
                    self._send_file_stream(conn, staged_path, is_bluetooth=False)
                    self.unstage_file()
                else:
                    print(f"[AeroCast Net] Received PULL from {addr} but no file staged", file=sys.stderr)
            else:
                self._receive_file_stream(conn, meta, is_bluetooth=False)

        except Exception as e:
            print(f"[AeroCast Net] TCP Client error from {addr}: {e}", file=sys.stderr)
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def _receive_file_stream(self, conn: socket.socket, meta: Dict[str, Any], is_bluetooth: bool = False):
        """
        Receives raw chunk stream (MSG_FILE_DATA), writes to Downloads/AeroCast/,
        validates SHA-256 and size, and emits completion.
        """
        filename = meta.get("filename", f"aerocast_{int(time.time())}.bin")
        total_size = int(meta.get("size", meta.get("filesize", 0)))
        expected_hash = meta.get("sha256", meta.get("file_hash"))

        dest_dir = get_default_download_dir()
        dest_path = dest_dir / filename

        received_bytes = 0
        hasher = hashlib.sha256()
        start_time = time.time()

        with open(dest_path, "wb") as f:
            while received_bytes < total_size:
                to_read = min(CHUNK_SIZE, total_size - received_bytes)
                chunk = conn.recv(to_read)
                if not chunk:
                    break
                f.write(chunk)
                hasher.update(chunk)
                received_bytes += len(chunk)

                if total_size > 0:
                    pct = (received_bytes / total_size) * 100.0
                    elapsed = max(0.001, time.time() - start_time)
                    speed_mbps = (received_bytes / elapsed) / (1024 * 1024)
                    self._emit_progress(filename, pct, speed_mbps)

        if total_size > 0 and received_bytes != total_size:
            raise IOError(
                f"File stream incomplete: received {received_bytes}/{total_size} bytes"
            )

        if expected_hash:
            calculated_hash = hasher.hexdigest()
            if calculated_hash.lower() != expected_hash.lower():
                print(f"[AeroCast Net] Warning: Hash mismatch for {filename}", file=sys.stderr)

        # Handshake ACK
        conn.sendall(b"OK")

        if self.file_received:
            self.file_received(filename, dest_path)
        elif self.on_transfer_complete:
            self.on_transfer_complete(filename, dest_path)

    def _send_file_stream(self, conn: socket.socket, filepath: Path, is_bluetooth: bool = False):
        """Streams staged file to peer over TCP or Bluetooth RFCOMM socket."""
        filename = filepath.name
        filesize = filepath.stat().st_size
        file_hash = compute_file_sha256(str(filepath))

        header_dict = {
            "type": MSG_FILE_HEADER,
            "action": "STREAM",
            "filename": filename,
            "size": filesize,
            "filesize": filesize,
            "sha256": file_hash
        }
        meta_bytes = json.dumps(header_dict).encode("utf-8")

        # Send standard frame: [12B Header] + [1B MsgType] + [4B PayloadLen] + [Payload]
        frame_header = encode_frame(MSG_TYPE_FILE_HEADER, meta_bytes)
        conn.sendall(frame_header)

        sent_bytes = 0
        start_time = time.time()

        with open(filepath, "rb") as f:
            while chunk := f.read(CHUNK_SIZE):
                conn.sendall(chunk)
                sent_bytes += len(chunk)
                if filesize > 0:
                    pct = (sent_bytes / filesize) * 100.0
                    elapsed = max(0.001, time.time() - start_time)
                    speed_mbps = (sent_bytes / elapsed) / (1024 * 1024)
                    self._emit_progress(filename, pct, speed_mbps)

        # Wait for receiver b"OK" ACK
        try:
            conn.recv(2)
        except Exception:
            pass

        if self.on_transfer_complete:
            self.on_transfer_complete(filename, filepath)

    def _emit_progress(self, filename: str, pct: float, speed_mbps: float):
        """Emits progress signal supporting both 2-arg and 3-arg listeners."""
        if not self.on_transfer_progress:
            return
        try:
            self.on_transfer_progress(filename, pct, speed_mbps)
        except TypeError:
            try:
                self.on_transfer_progress(filename, pct)
            except Exception:
                pass

    def transmit_file(
        self,
        target_ip: str,
        filepath: str,
        port: int = TCP_TRANSFER_PORT,
        max_retries: int = 3,
        retry_delay: float = 0.8,
        callback_progress: Optional[Callable[[str, float], None]] = None,
        callback_complete: Optional[Callable[[str, Path], None]] = None,
        callback_error: Optional[Callable[[str], None]] = None
    ) -> bool:
        """High-speed raw TCP chunk transmitter with retry loop and speed calculation."""
        path = Path(filepath)
        if not path.is_file():
            err = f"File not found: {filepath}"
            if callback_error: callback_error(err)
            return False

        filesize = path.stat().st_size
        filename = path.name
        file_hash = compute_file_sha256(str(path))

        sock = None
        connected = False

        for attempt in range(1, max_retries + 1):
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.settimeout(6.0)
                sock.connect((target_ip, port))
                connected = True
                break
            except Exception as e:
                if sock:
                    try: sock.close()
                    except Exception: pass
                if attempt < max_retries:
                    time.sleep(retry_delay * attempt)

        if not connected or sock is None:
            err = f"Failed to connect to {target_ip}:{port} after {max_retries} attempts"
            if callback_error: callback_error(err)
            return False

        try:
            sock.settimeout(20.0)

            # Send Header Frame
            header_meta = json.dumps({
                "type": MSG_FILE_HEADER,
                "action": "SEND",
                "filename": filename,
                "size": filesize,
                "filesize": filesize,
                "sha256": file_hash
            }).encode("utf-8")

            sock.sendall(encode_frame(MSG_TYPE_FILE_HEADER, header_meta))

            sent_bytes = 0
            start_time = time.time()

            with open(path, "rb") as f:
                while True:
                    chunk = f.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    sock.sendall(chunk)
                    sent_bytes += len(chunk)
                    if filesize > 0:
                        pct = (sent_bytes / filesize) * 100.0
                        elapsed = max(0.001, time.time() - start_time)
                        speed_mbps = (sent_bytes / elapsed) / (1024 * 1024)
                        if callback_progress:
                            callback_progress(filename, pct)
                        self._emit_progress(filename, pct, speed_mbps)

            sock.settimeout(15.0)
            ack = sock.recv(2)
            if ack != b"OK":
                raise IOError(f"Did not receive OK acknowledgment (got {ack})")

            if callback_complete:
                callback_complete(filename, path)
            if self.on_transfer_complete:
                self.on_transfer_complete(filename, path)

            return True

        except Exception as e:
            err = f"Transmission error to {target_ip}: {e}"
            if callback_error: callback_error(err)
            return False
        finally:
            try: sock.close()
            except Exception: pass

    def transmit_file_async(
        self,
        target_ip: str,
        filepath: str,
        port: int = TCP_TRANSFER_PORT,
        callback_progress: Optional[Callable[[str, float], None]] = None,
        callback_complete: Optional[Callable[[str, Path], None]] = None,
        callback_error: Optional[Callable[[str], None]] = None
    ):
        """Asynchronously transmits file to peer."""
        threading.Thread(
            target=self.transmit_file,
            args=(target_ip, filepath, port, 3, 0.8, callback_progress, callback_complete, callback_error),
            daemon=True,
            name="AeroCastTransmitWorker"
        ).start()

    def pull_file_from_sender(
        self,
        sender_ip: str,
        port: int,
        filename: str,
        expected_size: int,
        expected_hash: Optional[str] = None
    ):
        """Connects to sender to pull staged file after Open Palm gesture."""
        def worker():
            sock = None
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.settimeout(8.0)
                sock.connect((sender_ip, port))

                # Send PULL Frame
                pull_meta = json.dumps({"action": "PULL", "filename": filename}).encode("utf-8")
                sock.sendall(encode_frame(MSG_TYPE_PULL, pull_meta))

                # Receive header
                header_bytes = self._recv_all(sock, 17)
                if not header_bytes:
                    raise IOError("Failed to receive stream header")

                frame_info = decode_frame_header(header_bytes)
                if frame_info:
                    _, meta_len = frame_info
                else:
                    meta_len = int.from_bytes(header_bytes[12:16], byteorder="big")

                meta_data = self._recv_all(sock, meta_len)
                if not meta_data:
                    raise IOError("Missing metadata from sender")
                meta = json.loads(meta_data.decode("utf-8"))

                total_size = int(meta.get("size", meta.get("filesize", expected_size)))
                dest_dir = get_default_download_dir()
                dest_path = dest_dir / filename

                received_bytes = 0
                hasher = hashlib.sha256()
                start_time = time.time()

                with open(dest_path, "wb") as f:
                    while received_bytes < total_size:
                        to_read = min(CHUNK_SIZE, total_size - received_bytes)
                        chunk = sock.recv(to_read)
                        if not chunk:
                            break
                        f.write(chunk)
                        hasher.update(chunk)
                        received_bytes += len(chunk)
                        if total_size > 0:
                            pct = (received_bytes / total_size) * 100.0
                            elapsed = max(0.001, time.time() - start_time)
                            speed_mbps = (received_bytes / elapsed) / (1024 * 1024)
                            self._emit_progress(filename, pct, speed_mbps)

                if total_size > 0 and received_bytes != total_size:
                    raise IOError(f"Truncated stream: received {received_bytes}/{total_size}")

                sock.sendall(b"OK")

                if self.file_received:
                    self.file_received(filename, dest_path)
                elif self.on_transfer_complete:
                    self.on_transfer_complete(filename, dest_path)

            except Exception as e:
                print(f"[AeroCast Net] Pull file over Wi-Fi error: {e}. Trying Bluetooth fallback...", file=sys.stderr)
                self._pull_file_bluetooth_fallback(filename, expected_size)
            finally:
                if sock:
                    try: sock.close()
                    except Exception: pass

        threading.Thread(target=worker, daemon=True, name="AeroCastPullWorker").start()

    def _pull_file_bluetooth_fallback(self, filename: str, expected_size: int):
        """Fallback to pull file directly over Bluetooth RFCOMM socket."""
        paired_devices = get_paired_bluetooth_devices()
        for dev_name, mac in paired_devices:
            for channel in (1, 2, 3, 4, 5):
                s = None
                try:
                    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
                    s.settimeout(10.0)
                    s.connect((mac, channel))

                    pull_meta = json.dumps({"action": "PULL", "filename": filename}).encode("utf-8")
                    s.sendall(encode_frame(MSG_TYPE_PULL, pull_meta))

                    header_bytes = self._recv_all(s, 17)
                    if not header_bytes:
                        continue

                    frame_info = decode_frame_header(header_bytes)
                    meta_len = frame_info[1] if frame_info else int.from_bytes(header_bytes[12:16], byteorder="big")
                    meta_data = self._recv_all(s, meta_len)
                    if not meta_data:
                        continue
                    meta = json.loads(meta_data.decode("utf-8"))

                    total_size = int(meta.get("size", meta.get("filesize", expected_size)))
                    dest_dir = get_default_download_dir()
                    dest_path = dest_dir / filename

                    received_bytes = 0
                    hasher = hashlib.sha256()
                    start_time = time.time()

                    with open(dest_path, "wb") as f:
                        while received_bytes < total_size:
                            to_read = min(CHUNK_SIZE, total_size - received_bytes)
                            chunk = s.recv(to_read)
                            if not chunk:
                                break
                            f.write(chunk)
                            hasher.update(chunk)
                            received_bytes += len(chunk)
                            if total_size > 0:
                                pct = (received_bytes / total_size) * 100.0
                                elapsed = max(0.001, time.time() - start_time)
                                speed_mbps = (received_bytes / elapsed) / (1024 * 1024)
                                self._emit_progress(filename, pct, speed_mbps)

                    s.sendall(b"OK")
                    s.close()

                    if self.file_received:
                        self.file_received(filename, dest_path)
                    elif self.on_transfer_complete:
                        self.on_transfer_complete(filename, dest_path)
                    return
                except Exception:
                    if s:
                        try: s.close()
                        except Exception: pass

    # ================= 4. IPC SERVER =================
    def _start_ipc_server(self):
        """Local IPC socket for Windows Explorer right-click commands."""
        def worker():
            try:
                self._ipc_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                self._ipc_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                self._ipc_server.bind(("127.0.0.1", IPC_LOCAL_PORT))
                self._ipc_server.listen(5)
                self._ipc_server.settimeout(1.0)
            except Exception as e:
                print(f"[AeroCast Net] IPC bind error: {e}", file=sys.stderr)
                return

            while self._running:
                try:
                    conn, _ = self._ipc_server.accept()
                    data = conn.recv(4096)
                    conn.close()
                    if not data:
                        continue
                    cmd = json.loads(data.decode("utf-8"))
                    action = cmd.get("action")
                    if action in ("AIR_SEND", "STAGE"):
                        fpath = cmd.get("filepath", cmd.get("path"))
                        if fpath and self.on_stage_file_requested:
                            self.on_stage_file_requested(fpath)
                except socket.timeout:
                    continue
                except Exception:
                    if self._running:
                        time.sleep(0.05)

        self._ipc_thread = threading.Thread(target=worker, daemon=True, name="AeroCastIPCServer")
        self._ipc_thread.start()

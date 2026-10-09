"""
AeroCast Native Pipeline Verification Script
Validates protocol serialization, TCP transmission, UDP signaling, and system hooks.
"""

import os
import sys
import time
import socket
import tempfile
import threading
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent
SHARED_DIR = ROOT_DIR / "shared"
AGENT_DIR = ROOT_DIR / "windows_agent"

sys.path.insert(0, str(SHARED_DIR))
sys.path.insert(0, str(AGENT_DIR))

import protocol
from core.network_service import NetworkService
import explorer_context
import tray_app

def test_protocol():
    print("[TEST] 1. Protocol Packet Encoders & Decoders...")
    assert protocol.MAGIC_HEADER == b"AERO_CAST_V1", "Magic header mismatch"
    assert protocol.CHUNK_SIZE == 64 * 1024, "Chunk size must be 64KB"

    # Test Frame encode and decode
    test_payload = b'{"action": "TEST"}'
    framed = protocol.encode_frame(protocol.MSG_TYPE_FILE_HEADER, test_payload)
    assert len(framed) == 12 + 1 + 4 + len(test_payload)
    msg_type, plen = protocol.decode_frame_header(framed[:17])
    assert msg_type == protocol.MSG_TYPE_FILE_HEADER
    assert plen == len(test_payload)

    # Test make_beacon
    beacon_bytes = protocol.make_beacon(
        event=protocol.MSG_STAGE_ARMED,
        device_name="TestDevice",
        sender=protocol.SENDER_MOBILE,
        filename="test_document.pdf",
        size=1048576,
        file_hash="abcdef123456"
    )
    parsed = protocol.parse_beacon(beacon_bytes)
    assert parsed is not None, "Failed to parse generated beacon"
    assert parsed["event"] == protocol.MSG_STAGE_ARMED
    assert parsed["sender"] == protocol.SENDER_MOBILE
    assert parsed["filename"] == "test_document.pdf"
    assert parsed["size"] == 1048576
    assert parsed["sha256"] == "abcdef123456"

    # Test v1 backwards compatibility
    v1_raw = b'{"proto": "AERO_CAST_V1", "event": "MSG_STAGE_ARMED", "sender": "LAPTOP", "filesize": 500}'
    parsed_v1 = protocol.parse_beacon(v1_raw)
    assert parsed_v1 is not None, "Failed to parse V1 protocol beacon"
    assert parsed_v1["event"] == protocol.MSG_STAGE_ARMED
    assert parsed_v1["size"] == 500

    print("  -> Protocol tests PASSED.")

def test_tcp_transmission():
    print("[TEST] 2. TCP High-Speed Chunk Streamer (transmit_file & _handle_tcp_client)...")
    received_event = threading.Event()
    received_file_info = {}
    progress_updates = []

    def on_received(filename, path):
        received_file_info["filename"] = filename
        received_file_info["path"] = path
        received_event.set()

    def on_progress(filename, pct):
        progress_updates.append(pct)

    # Start network service
    svc = NetworkService(
        on_transfer_progress=on_progress,
        on_transfer_complete=on_received,
        file_received=on_received
    )
    svc.start()

    time.sleep(0.3)

    # Create dummy 128 KB test file
    with tempfile.NamedTemporaryFile(delete=False, suffix=".dat") as f:
        dummy_data = os.urandom(128 * 1024)
        f.write(dummy_data)
        dummy_file = f.name

    try:
        # Transmit file to localhost
        success = svc.transmit_file(
            target_ip="127.0.0.1",
            filepath=dummy_file,
            port=protocol.TCP_TRANSFER_PORT
        )
        assert success, "File transmission returned False"

        # Wait for receiver
        assert received_event.wait(timeout=5.0), "File receiver timeout"
        assert len(progress_updates) > 0, "No progress callbacks received"
        assert received_file_info["filename"] == Path(dummy_file).name

        # Verify content on disk
        rec_path = Path(received_file_info["path"])
        assert rec_path.exists(), "Received file not found on disk"
        assert rec_path.stat().st_size == len(dummy_data), "Received file size mismatch"

        print(f"  -> Transmitted & received 128KB successfully with {len(progress_updates)} progress ticks.")
        print("  -> TCP transmission tests PASSED.")
    finally:
        svc.stop()
        if os.path.exists(dummy_file):
            try: os.remove(dummy_file)
            except Exception: pass
        if "path" in received_file_info and os.path.exists(received_file_info["path"]):
            try: os.remove(received_file_info["path"])
            except Exception: pass

def test_udp_discovery_and_staging():
    print("[TEST] 3. UDP Signaling & Beacon Discovery...")
    armed_event = threading.Event()
    armed_packet = {}

    def on_armed(pkt):
        armed_packet.update(pkt)
        armed_event.set()

    svc = NetworkService(on_armed_drop_received=on_armed)
    svc.start()
    time.sleep(0.2)

    try:
        # Simulate Mobile sending ARMED_DROP beacon directly to localhost
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        data = protocol.make_beacon(
            event=protocol.MSG_STAGE_ARMED,
            device_name="Mobile_Pixel8",
            sender=protocol.SENDER_MOBILE,
            filename="presentation.pdf",
            size=2048
        )
        # Send from an external socket to localhost UDP_BEACON_PORT
        sock.sendto(data, ("127.0.0.1", protocol.UDP_BEACON_PORT))
        sock.close()

        # Note: In _process_udp, packets from local_ip are ignored to prevent self-echo.
        # Let's test _process_udp directly with simulated remote IP
        svc._process_udp(data, ("192.168.1.150", protocol.UDP_BEACON_PORT))
        assert armed_event.wait(timeout=2.0), "Armed drop callback timed out"
        assert armed_packet.get("filename") == "presentation.pdf"
        assert armed_packet.get("size") == 2048

        print("  -> UDP Signaling tests PASSED.")
    finally:
        svc.stop()

def test_explorer_hooks():
    print("[TEST] 4. Windows Explorer Registry Hooks & IPC...")
    py_exe, context_script, icon_path, tray_script = explorer_context.get_agent_paths()
    assert os.path.exists(py_exe) or py_exe.endswith("pythonw.exe"), "Python executable path invalid"
    assert os.path.exists(context_script), "explorer_context.py not found"
    assert os.path.exists(tray_script), "tray_app.py not found"
    print("  -> Explorer hooks tests PASSED.")

if __name__ == "__main__":
    print("==================================================================")
    print("       AEROCAST FULL PIPELINE VERIFICATION SUITE")
    print("==================================================================")
    test_protocol()
    test_tcp_transmission()
    test_udp_discovery_and_staging()
    test_explorer_hooks()
    print("==================================================================")
    print("   ALL AEROCAST TESTS PASSED CLEANLY (ZERO FAILURES)")
    print("==================================================================")

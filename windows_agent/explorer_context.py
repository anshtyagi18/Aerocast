"""
AeroCast Windows Explorer Context Menu Integration
Adds "⚡ Air Send with AeroCast" to Windows Explorer right-click context menu (HKCU).
Handles forwarding staged files to running background agent via local IPC socket.
"""

import os
import sys
import json
import socket
import winreg
import subprocess
from pathlib import Path

SHARED_DIR = Path(__file__).resolve().parent.parent / "shared"
if str(SHARED_DIR) not in sys.path:
    sys.path.insert(0, str(SHARED_DIR))

from protocol import IPC_LOCAL_PORT

REG_KEY_PATH = r"Software\Classes\*\shell\AeroCast"
DIR_REG_KEY_PATH = r"Software\Classes\Directory\shell\AeroCast"


def get_agent_paths():
    """Resolves absolute paths for pythonw executable, context script, icon, and tray daemon."""
    base_dir = Path(__file__).resolve().parent
    root_dir = base_dir.parent
    tray_script = base_dir / "tray_app.py"
    icon_path = base_dir / "assets" / "tray_icon.ico"

    # Prefer pythonw.exe inside virtualenv if present
    venv_pythonw = root_dir / "venv" / "Scripts" / "pythonw.exe"
    if venv_pythonw.exists():
        py_exe = str(venv_pythonw)
    else:
        py_exe = sys.executable.replace("python.exe", "pythonw.exe")

    return py_exe, str(base_dir / "explorer_context.py"), str(icon_path), str(tray_script)


AUTOSTART_REG_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def install_autostart() -> bool:
    """
    Registers AeroCast background tray agent into Windows Startup (HKCU Run key).
    Causes AeroCast to start automatically & silently upon user login.
    """
    try:
        py_exe, _, _, tray_script = get_agent_paths()
        command_str = f'"{py_exe}" "{tray_script}"'
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, AUTOSTART_REG_KEY) as key:
            winreg.SetValueEx(key, "AeroCast", 0, winreg.REG_SZ, command_str)
        print("[AeroCast] Windows startup auto-launch registered successfully.")
        return True
    except Exception as e:
        print(f"[AeroCast] Error registering autostart: {e}", file=sys.stderr)
        return False


def uninstall_autostart() -> bool:
    """Removes AeroCast from Windows Startup."""
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, AUTOSTART_REG_KEY, 0, winreg.KEY_SET_VALUE) as key:
            winreg.DeleteValue(key, "AeroCast")
        print("[AeroCast] Windows startup auto-launch unregistered.")
        return True
    except FileNotFoundError:
        return True
    except Exception as e:
        print(f"[AeroCast] Error removing autostart: {e}", file=sys.stderr)
        return False


def install_context_menu() -> bool:
    """
    Registers 'Air Send with AeroCast' into the Windows Registry under HKCU
    and registers AeroCast to run permanently at Windows startup.
    Does NOT require administrator privileges.
    """
    success = True
    try:
        py_exe, context_script, icon_path, _ = get_agent_paths()
        command_str = f'"{py_exe}" "{context_script}" "%1"'

        # 1. Register for all files: Software\Classes\*\shell\AeroCast
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, REG_KEY_PATH) as key:
            winreg.SetValueEx(key, "", 0, winreg.REG_SZ, "⚡ Air Send with AeroCast")
            if os.path.exists(icon_path):
                winreg.SetValueEx(key, "Icon", 0, winreg.REG_SZ, icon_path)

        cmd_path = f"{REG_KEY_PATH}\\command"
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, cmd_path) as cmd_key:
            winreg.SetValueEx(cmd_key, "", 0, winreg.REG_SZ, command_str)

        # 2. Also register for folders: Software\Classes\Directory\shell\AeroCast
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, DIR_REG_KEY_PATH) as dir_key:
            winreg.SetValueEx(dir_key, "", 0, winreg.REG_SZ, "⚡ Air Send with AeroCast")
            if os.path.exists(icon_path):
                winreg.SetValueEx(dir_key, "Icon", 0, winreg.REG_SZ, icon_path)

        dir_cmd_path = f"{DIR_REG_KEY_PATH}\\command"
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, dir_cmd_path) as dir_cmd_key:
            winreg.SetValueEx(dir_cmd_key, "", 0, winreg.REG_SZ, command_str)

        print("[AeroCast] Explorer context menu registered successfully.")
    except Exception as e:
        print(f"[AeroCast] Error installing context menu: {e}", file=sys.stderr)
        success = False

    # Also install Windows Startup autostart
    if not install_autostart():
        success = False

    return success


def uninstall_context_menu() -> bool:
    """Removes 'Air Send with AeroCast' and startup autostart from Windows Registry."""
    uninstall_autostart()
    try:
        # Delete file context menu keys
        try:
            winreg.DeleteKey(winreg.HKEY_CURRENT_USER, f"{REG_KEY_PATH}\\command")
        except FileNotFoundError:
            pass
        try:
            winreg.DeleteKey(winreg.HKEY_CURRENT_USER, REG_KEY_PATH)
        except FileNotFoundError:
            pass

        # Delete directory context menu keys
        try:
            winreg.DeleteKey(winreg.HKEY_CURRENT_USER, f"{DIR_REG_KEY_PATH}\\command")
        except FileNotFoundError:
            pass
        try:
            winreg.DeleteKey(winreg.HKEY_CURRENT_USER, DIR_REG_KEY_PATH)
        except FileNotFoundError:
            pass

        print("[AeroCast] Context menu unregistered successfully.")
        return True
    except Exception as e:
        print(f"[AeroCast] Error uninstalling context menu: {e}", file=sys.stderr)
        return False


def send_ipc_file(filepath: str) -> bool:
    """Sends file staging request to running background agent via localhost IPC."""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(1.2)
        sock.connect(("127.0.0.1", IPC_LOCAL_PORT))
        cmd = json.dumps({"action": "AIR_SEND", "filepath": filepath}).encode("utf-8")
        sock.sendall(cmd)
        sock.close()
        return True
    except Exception:
        return False


def launch_agent_with_file(filepath: str):
    """If agent daemon is not running, launches tray app in background with staged file."""
    py_exe, _, _, tray_script = get_agent_paths()
    DETACHED_PROCESS = 0x00000008
    CREATE_NO_WINDOW = 0x08000000
    creation_flags = DETACHED_PROCESS | CREATE_NO_WINDOW

    try:
        subprocess.Popen(
            [py_exe, tray_script, "--stage", filepath],
            creationflags=creation_flags,
            close_fds=True
        )
    except Exception as e:
        print(f"[AeroCast] Failed to launch background tray app: {e}", file=sys.stderr)


def main():
    if len(sys.argv) < 2:
        print("Usage: explorer_context.py [--install | --uninstall | <filepath>]")
        sys.exit(0)

    arg = sys.argv[1]
    if arg == "--install":
        sys.exit(0 if install_context_menu() else 1)
    elif arg == "--uninstall":
        sys.exit(0 if uninstall_context_menu() else 1)
    else:
        target_path = os.path.abspath(arg)
        if not os.path.exists(target_path):
            print(f"[AeroCast] File not found: {target_path}", file=sys.stderr)
            sys.exit(1)

        # Try IPC to existing agent first
        if not send_ipc_file(target_path):
            # Agent is not running, launch background agent with staged file
            launch_agent_with_file(target_path)


if __name__ == "__main__":
    main()

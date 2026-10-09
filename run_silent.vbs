Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = "C:\Users\ansht\Desktop\Aerocast\AeroCast_Native"
WshShell.Run "pythonw windows_agent\tray_app.py", 0, False
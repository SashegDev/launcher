# view_logs.py - полезный скрипт для просмотра логов
#!/usr/bin/env python3
import sys
from pathlib import Path
from datetime import datetime

LOG_DIR = Path("logs")

def list_logs():
    """List all available log files"""
    log_files = sorted(LOG_DIR.glob("*.log"), key=lambda p: p.stat().st_mtime, reverse=True)
    
    print("Available logs:")
    print("-" * 50)
    for i, log_file in enumerate(log_files, 1):
        size = log_file.stat().st_size / 1024  # KB
        modified = datetime.fromtimestamp(log_file.stat().st_mtime).strftime("%Y-%m-%d %H:%M:%S")
        current = " (current)" if log_file.name == "latest.log" else ""
        print(f"{i}. {log_file.name}{current} - {size:.1f} KB - {modified}")
    
    return log_files

def view_log(log_file):
    """View a log file"""
    if not log_file.exists():
        print(f"Log file not found: {log_file}")
        return
    
    print(f"\n=== {log_file.name} ===\n")
    with open(log_file, 'r') as f:
        # Show last 50 lines by default
        lines = f.readlines()
        if len(lines) > 50:
            print(f"... (showing last 50 of {len(lines)} lines) ...\n")
            lines = lines[-50:]
        
        for line in lines:
            print(line.rstrip())

if __name__ == "__main__":
    if len(sys.argv) > 1:
        if sys.argv[1] == "list":
            list_logs()
        elif sys.argv[1].isdigit():
            logs = list_logs()
            idx = int(sys.argv[1]) - 1
            if 0 <= idx < len(logs):
                view_log(logs[idx])
            else:
                print("Invalid log number")
        else:
            # Try to open as filename
            view_log(LOG_DIR / sys.argv[1])
    else:
        # Show latest.log by default
        view_log(LOG_DIR / "latest.log")
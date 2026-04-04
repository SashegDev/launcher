# log_manager.py
import logging
import structlog
from pathlib import Path
import sys
import shutil
from datetime import datetime
import os

LOG_DIR = Path("logs")
LOG_DIR.mkdir(exist_ok=True)

# Путь к latest.log
LATEST_LOG = LOG_DIR / "latest.log"

def rotate_logs():
    """Rotate logs without compression (like Minecraft)"""
    log_files = sorted(LOG_DIR.glob("*.log"), key=lambda p: p.stat().st_mtime, reverse=True)
    
    for old_log in log_files[10:]:
        if old_log.name != "latest.log":
            try:
                old_log.unlink()
            except Exception:
                pass
    
    if LATEST_LOG.exists() and LATEST_LOG.stat().st_size > 0:
        timestamp = datetime.fromtimestamp(LATEST_LOG.stat().st_mtime).strftime("%Y-%m-%d_%H-%M-%S")
        backup_name = LOG_DIR / f"{timestamp}.log"
        shutil.move(str(LATEST_LOG), str(backup_name))

def _add_location(logger, method_name, event_dict):
    """Add location to event dict"""
    module = event_dict.pop("module", "unknown")
    func_name = event_dict.pop("func_name", "")
    
    # Store location
    if func_name and func_name != "<module>":
        event_dict["_location"] = f"{module}.{func_name}"
    else:
        event_dict["_location"] = module
    
    return event_dict

class HumanConsoleRenderer:
    """Render logs in human-readable format with colors"""
    
    def __init__(self):
        self.colors = {
            'debug': '\033[36m',
            'info': '\033[32m',
            'warning': '\033[33m',
            'error': '\033[31m',
            'critical': '\033[35m',
            'reset': '\033[0m'
        }
    
    def __call__(self, logger, name, event_dict):
        level = event_dict.get('level', 'info')
        timestamp = event_dict.get('timestamp', datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3])
        event = event_dict.get('event', '')
        location = event_dict.get('_location', 'unknown')
        
        # Format event with location
        formatted_event = f"{event} [{location}]"
        
        # Colorize level
        color = self.colors.get(level, self.colors['reset'])
        colored_level = f"{color}{level:<8}{self.colors['reset']}"
        
        return f"{timestamp} [{colored_level}] {formatted_event}"

class HumanFileRenderer:
    """Render logs in human-readable format without colors for files"""
    
    def __call__(self, logger, name, event_dict):
        level = event_dict.get('level', 'info')
        timestamp = event_dict.get('timestamp', datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3])
        event = event_dict.get('event', '')
        location = event_dict.get('_location', 'unknown')
        
        # Format event with location
        formatted_event = f"{event} [{location}]"
        
        return f"{timestamp} [{level:<8}] {formatted_event}"

def setup_logging():
    """Setup human-readable logging"""
    
    # Rotate logs on startup
    rotate_logs()
    
    # Configure structlog processors
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.stdlib.add_logger_name,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="%Y-%m-%d %H:%M:%S.%f", utc=False),
            structlog.processors.CallsiteParameterAdder(
                {
                    structlog.processors.CallsiteParameter.MODULE,
                    structlog.processors.CallsiteParameter.FUNC_NAME,
                }
            ),
            _add_location,
        ],
        logger_factory=structlog.stdlib.LoggerFactory(),
        wrapper_class=structlog.stdlib.BoundLogger,
        cache_logger_on_first_use=True,
    )
    
    # Configure standard logging
    root_logger = logging.getLogger()
    root_logger.setLevel(logging.DEBUG)
    root_logger.handlers.clear()
    
    # Create formatters
    class StructlogConsoleHandler(logging.StreamHandler):
        def emit(self, record):
            try:
                # Convert record to event dict
                event_dict = {
                    'level': record.levelname.lower(),
                    'timestamp': datetime.fromtimestamp(record.created).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
                    'event': record.getMessage(),
                    '_location': getattr(record, 'module', 'unknown')
                }
                
                # Add function name if available
                if hasattr(record, 'funcName') and record.funcName:
                    event_dict['_location'] = f"{record.module}.{record.funcName}"
                
                # Render
                renderer = HumanConsoleRenderer()
                output = renderer(None, None, event_dict)
                
                # Write to console
                self.stream.write(output + '\n')
                self.flush()
                
            except Exception:
                self.handleError(record)
    
    class StructlogFileHandler(logging.FileHandler):
        def emit(self, record):
            try:
                # Convert record to event dict
                event_dict = {
                    'level': record.levelname.lower(),
                    'timestamp': datetime.fromtimestamp(record.created).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
                    'event': record.getMessage(),
                    '_location': getattr(record, 'module', 'unknown')
                }
                
                # Add function name if available
                if hasattr(record, 'funcName') and record.funcName:
                    event_dict['_location'] = f"{record.module}.{record.funcName}"
                
                # Render
                renderer = HumanFileRenderer()
                output = renderer(None, None, event_dict)
                
                # Write to file
                self.stream.write(output + '\n')
                self.flush()
                
            except Exception:
                self.handleError(record)
    
    # Add handlers
    console_handler = StructlogConsoleHandler(sys.stdout)
    console_handler.setLevel(logging.DEBUG)
    
    file_handler = StructlogFileHandler(LATEST_LOG, encoding='utf-8')
    file_handler.setLevel(logging.DEBUG)
    
    root_logger.addHandler(console_handler)
    root_logger.addHandler(file_handler)
    
    # Reduce noise
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
    logging.getLogger("uvicorn.error").setLevel(logging.INFO)
    logging.getLogger("fastapi").setLevel(logging.INFO)
    
    return structlog.get_logger()

def get_logger(name):
    """Get a logger instance"""
    return logging.getLogger(name)

# Global logger instance
_logger = None

def init_logging():
    """Initialize logging system"""
    global _logger
    if _logger is None:
        _logger = setup_logging()
        # Use standard logging for the initial message
        logging.info(f"Logging initialized (log_file: {LATEST_LOG})")
    return _logger
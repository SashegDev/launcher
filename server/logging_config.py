# logging_config.py
import sys
from rich.traceback import install as install_rich_traceback
from log_manager import init_logging, get_logger, LATEST_LOG
import logging

install_rich_traceback(show_locals=False)

def setup_logging() -> None:
    """Setup human-readable logging"""
    
    # Initialize the logger
    logger_manager = init_logging()
    
    # Determine mode
    mode = "development"
    if "--test" in sys.argv:
        mode = "test"
    elif "--prod" in sys.argv:
        mode = "production"
    elif "--dev" in sys.argv:
        mode = "development"
    
    # Log startup using standard logging
    logger = get_logger(__name__)
    logger.info(f"Server starting in {mode} mode")
    
    return logger
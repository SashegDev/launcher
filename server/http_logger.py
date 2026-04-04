# http_logger.py
import logging
from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware
import time
import uuid

logger = logging.getLogger("uvicorn.error")

class HTTPLogger:
    """Custom HTTP logger to catch invalid requests"""
    
    @staticmethod
    def log_invalid_request(data: bytes, client_addr: tuple):
        """Log invalid HTTP requests"""
        try:
            # Try to decode as much as possible
            request_str = data.decode('utf-8', errors='replace')[:500]
            logger.warning(
                f"Invalid HTTP request received\n"
                f"Client: {client_addr[0]}:{client_addr[1]}\n"
                f"Data: {request_str}"
            )
        except Exception as e:
            logger.warning(f"Invalid HTTP request from {client_addr}, could not decode: {e}")
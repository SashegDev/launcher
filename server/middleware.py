# middleware.py
from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware
import logging
import time
import uuid
import traceback

logger = logging.getLogger(__name__)

class LoggingMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        # Generate request ID
        request_id = str(uuid.uuid4())[:8]
        
        # Get client IP
        client_ip = request.client.host if request.client else "unknown"
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            client_ip = forwarded.split(",")[0].strip()
        
        # Log incoming request
        logger.info(f"→ {request.method} {request.url.path} (IP: {client_ip}, ID: {request_id})")
        
        # Start timer
        start_time = time.time()
        
        try:
            response = await call_next(request)
            
            # Calculate duration
            duration = (time.time() - start_time) * 1000
            
            # Log response
            logger.info(f"← {request.method} {request.url.path} → {response.status_code} ({duration:.0f}ms) [ID: {request_id}]")
            
            # Add request ID to response headers
            response.headers["X-Request-ID"] = request_id
            
            return response
            
        except Exception as e:
            duration = (time.time() - start_time) * 1000
            # Log full traceback
            error_traceback = traceback.format_exc()
            logger.error(f"✗ {request.method} {request.url.path} → ERROR: {str(e)} (ID: {request_id})\n{error_traceback}")
            raise
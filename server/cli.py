# cli.py
import argparse
import sys
import asyncio
from pathlib import Path
import structlog

logger = structlog.get_logger(__name__)

def parse_args():
    parser = argparse.ArgumentParser(description="ZernMC Launcher Server")
    
    # Mode selection (mutually exclusive)
    mode_group = parser.add_mutually_exclusive_group()
    mode_group.add_argument("--dev", action="store_true", help="Development mode with auto-reload")
    mode_group.add_argument("--prod", action="store_true", help="Production mode with 4 workers")
    mode_group.add_argument("--test", action="store_true", help="Test mode - validate builds and generate manifests")
    
    # Additional options
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind to (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=1582, help="Port to bind to (default: 1582)")
    parser.add_argument("--workers", type=int, default=4, help="Number of workers for production mode")
    parser.add_argument("--reload", action="store_true", help="Enable auto-reload (development)")
    
    return parser.parse_args()

async def run_test_mode():
    """Validate all packs and generate/update manifests"""
    logger.info("Running in TEST mode - validating builds and generating manifests")
    
    from pack_manager import scan_pack, PACKS_DIR
    
    pack_count = 0
    error_count = 0
    
    for pack_dir in PACKS_DIR.iterdir():
        if pack_dir.is_dir():
            try:
                logger.info(f"Validating pack: {pack_dir.name}")
                meta = await scan_pack(pack_dir.name)
                pack_count += 1
                logger.info(f"✓ Pack validated: {pack_dir.name} v{meta.version}, {len(meta.files)} files")
            except Exception as e:
                error_count += 1
                logger.error(f"✗ Failed to validate pack {pack_dir.name}", error=str(e), exc_info=True)
    
    logger.info(f"Test mode completed: {pack_count} packs validated, {error_count} errors")
    
    if error_count > 0:
        logger.error("Some packs failed validation")
        sys.exit(1)
    else:
        logger.info("All packs validated successfully")
        sys.exit(0)

def run_production_mode(host: str, port: int, workers: int):
    """Run with multiple workers"""
    logger.info(f"Starting in PRODUCTION mode with {workers} workers on {host}:{port}")
    
    import uvicorn
    uvicorn.run(
        "main:app",
        host=host,
        port=port,
        workers=workers,
        log_config=None,
        access_log=False  # We have our own logging middleware
    )

def run_development_mode(host: str, port: int, reload: bool = True):
    """Run with auto-reload for development"""
    logger.info(f"Starting in DEVELOPMENT mode with reload={reload} on {host}:{port}")
    
    import uvicorn
    uvicorn.run(
        "main:app",
        host=host,
        port=port,
        reload=reload,
        log_config=None,
        access_log=False
    )
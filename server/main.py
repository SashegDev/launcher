import os

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from contextlib import asynccontextmanager
from pathlib import Path
import json
import structlog
from cachetools import TTLCache
import asyncio
import logging
from datetime import datetime
from uvicorn.protocols.http.httptools_impl import HttpToolsProtocol

# Import local modules
from pack_manager import DATA_DIR, get_fabric_versions, get_forge_versions, scan_pack, get_cached_manifest, PACKS_DIR
from models import PackMeta
from logging_config import setup_logging
from middleware import LoggingMiddleware
from cli import parse_args, run_test_mode, run_production_mode, run_development_mode
from log_manager import init_logging, get_logger

from models import MinecraftVersion
from pack_manager import get_minecraft_versions, download_minecraft_version

logger = structlog.get_logger(__name__)

# Cache for manifests - expires after 5 minutes
manifest_cache = TTLCache(maxsize=100, ttl=300)

@asynccontextmanager
async def lifespan(app: FastAPI):
    args = parse_args()
    
    # Initialize logging
    init_logging()
    logger = logging.getLogger(__name__)
    
    # Determine environment
    if args.test:
        env = "test"
    elif args.dev:
        env = "development"
    else:
        env = "production"
    
    logger.info(f"Starting ZernMC Launcher Server (environment: {env})")
    
    if args.test:
        await run_test_mode()
        yield
        return
    
    logger.info("Scanning packs on startup...")
    
    pack_dirs = [p for p in PACKS_DIR.iterdir() if p.is_dir()]
    
    if not pack_dirs:
        logger.warning(f"No packs found in directory: {PACKS_DIR}")
    else:
        for pack_dir in pack_dirs:
            try:
                meta = await scan_pack(pack_dir.name)
                logger.info(f"Pack scanned successfully: {pack_dir.name} v{meta.version} ({len(meta.files)} files)")
            except Exception as e:
                logger.error(f"Failed to scan pack: {pack_dir.name} - {e}", exc_info=True)
    
    logger.info("All packs ready. Server is running.")
    yield
    logger.info("Server shutting down...")

# Create app with lifespan
app = FastAPI(title="ZernMC Launcher Server", lifespan=lifespan)

# Add logging middleware
app.add_middleware(LoggingMiddleware)


# Monkey patch to catch invalid HTTP requests
original_data_received = HttpToolsProtocol.data_received

def patched_data_received(self, data):
    try:
        return original_data_received(self, data)
    except Exception as e:
        client = self.transport.get_extra_info('peername')
        logger = logging.getLogger(__name__)
        logger.warning(f"Invalid HTTP request from {client}: {str(e)[:200]}")
        # Log raw data if possible
        try:
            raw_data = data[:500].decode('utf-8', errors='replace')
            logger.debug(f"Raw request data: {raw_data}")
        except:
            pass
        raise

HttpToolsProtocol.data_received = patched_data_received

@app.get("/")
async def root():
    """Root endpoint"""
    logger = logging.getLogger(__name__)
    logger.info("Root endpoint accessed")
    return {
        "status": "ok",
        "message": "ZernMC Launcher Server is running",
        "docs": "/docs",
        "redoc": "/redoc"
    }

@app.get("/health")
async def health():
    """Health check endpoint"""
    return {"status": "healthy", "timestamp": datetime.utcnow().isoformat()}


@app.get("/packs")
async def list_packs():
    """List all available packs"""
    logger = logging.getLogger(__name__)
    packs = []
    
    for pack_dir in PACKS_DIR.iterdir():
        if pack_dir.is_dir():
            meta_path = DATA_DIR / f"{pack_dir.name}.meta"
            if meta_path.exists():
                try:
                    async with open(meta_path, 'r', encoding='utf-8') as f:
                        meta = json.load(f)
                        packs.append({
                            "name": pack_dir.name,
                            "version": meta.get("version", 1),
                            "files_count": len(meta.get("files", {})),
                            "updated_at": meta.get("updated_at")
                        })
                except Exception as e:
                    logger.error(f"Failed to load pack meta for {pack_dir.name}: {e}")
                    packs.append({
                        "name": pack_dir.name,
                        "error": str(e)
                    })
            else:
                packs.append({
                    "name": pack_dir.name,
                    "status": "not_scanned"
                })
    
    return {"packs": packs}

# ------------------- DIFF ENDPOINT -------------------

@app.post("/pack/{pack_name}/diff")
async def get_pack_diff(pack_name: str, client_files: dict[str, str], request: Request):
    """
    Client sends: { "mods/jei.jar": "sha256_hash", ... }
    Server returns diff information
    """
    client_ip = request.client.host if request.client else "unknown"
    logger.info("Received diff request", 
                pack=pack_name, 
                client_files_count=len(client_files),
                client_ip=client_ip)

    try:
        # Use cached manifest if available
        meta = get_cached_manifest(pack_name)
        if not meta:
            meta = await scan_pack(pack_name)
    except FileNotFoundError:
        logger.warning("Pack not found", pack=pack_name, client_ip=client_ip)
        raise HTTPException(404, "Pack not found")
    except Exception as e:
        logger.error("Error loading pack meta", pack=pack_name, error=str(e), exc_info=True)
        raise HTTPException(500, "Internal server error")

    to_download = []
    to_delete = []
    to_update = []

    server_files = meta.files

    # Calculate what needs to be downloaded or updated
    for path, entry in server_files.items():
        client_hash = client_files.get(path)
        if client_hash is None or client_hash != entry.hash:
            url = f"/pack/{pack_name}/file/{path}"
            to_download.append({
                "path": path,
                "url": url,
                "size": entry.size,
                "hash": entry.hash
            })
            if client_hash is not None:
                to_update.append(path)

    # Calculate what needs to be deleted
    for path in client_files:
        if path not in server_files:
            to_delete.append(path)

    logger.info("Diff calculated", 
                pack=pack_name, 
                version=meta.version,
                to_download=len(to_download),
                to_delete=len(to_delete),
                to_update=len(to_update),
                client_ip=client_ip)

    return {
        "version": meta.version,
        "to_download": to_download,
        "to_delete": to_delete,
        "to_update": to_update
    }

@app.get("/minecraft/versions")
async def list_minecraft_versions():
    """List available Minecraft versions"""
    try:
        versions = await get_minecraft_versions()
        return {"versions": [v.model_dump() for v in versions]}
    except Exception as e:
        logger.error(f"Failed to fetch Minecraft versions: {e}")
        raise HTTPException(500, "Failed to fetch versions")

@app.get("/minecraft/version/{version}")
async def get_version_details(version: str):
    """Get details for specific Minecraft version"""
    # This would fetch version JSON from Mojang
    return {"version": version, "status": "available"}

@app.post("/minecraft/download/{version}")
async def download_version(version: str, request: Request):
    """Download Minecraft version"""
    client_ip = request.client.host if request.client else "unknown"
    logger.info(f"Download request for Minecraft {version}", client_ip=client_ip)
    
    version_path = Path("versions") / version
    version_path.mkdir(parents=True, exist_ok=True)
    
    success = await download_minecraft_version(version, version_path)
    if success:
        return {"status": "success", "version": version}
    raise HTTPException(404, "Version not found")

@app.get("/modloaders/{loader_type}")
async def get_modloaders(loader_type: str, minecraft_version: str = None):
    """Get available mod loaders"""
    if loader_type == "fabric":
        versions = await get_fabric_versions(minecraft_version) if minecraft_version else []
        return {"loader": "fabric", "versions": versions}
    elif loader_type == "forge":
        versions = await get_forge_versions(minecraft_version) if minecraft_version else []
        return {"loader": "forge", "versions": versions}
    elif loader_type == "vanilla":
        return {"loader": "vanilla", "versions": ["vanilla"]}
    raise HTTPException(400, "Invalid loader type")

@app.get("/pack/{pack_name}")
async def get_pack_manifest(pack_name: str, request: Request):
    """Get pack manifest with caching"""
    client_ip = request.client.host if request.client else "unknown"
    
    # Check cache first
    cached_meta = get_cached_manifest(pack_name)
    if cached_meta:
        logger.debug("Manifest served from cache", 
                    pack=pack_name, 
                    version=cached_meta.version,
                    client_ip=client_ip)
        return JSONResponse(
            content=cached_meta.model_dump(),
            headers={"X-Pack-Version": str(cached_meta.version), "X-Cached": "true"}
        )
    
    # Load from disk if not in cache
    meta_path = Path("data") / f"{pack_name}.meta"
    if not meta_path.exists():
        logger.warning("Manifest requested but pack not found", pack=pack_name, client_ip=client_ip)
        raise HTTPException(404, "Pack not found")
    
    async with open(meta_path, 'r', encoding='utf-8') as f:
        meta_dict = json.load(f)
        meta = PackMeta.model_validate(meta_dict)
        # Update cache
        manifest_cache[pack_name] = meta
    
    logger.debug("Manifest served from disk", 
                pack=pack_name, 
                version=meta.version,
                client_ip=client_ip)
    
    return JSONResponse(
        content=meta_dict,
        headers={"X-Pack-Version": str(meta.version), "X-Cached": "false"}
    )

@app.get("/pack/{pack_name}/file/{file_path:path}")
async def get_pack_file(pack_name: str, file_path: str, request: Request):
    full_path = PACKS_DIR / pack_name / file_path
    client_ip = request.client.host if request.client else None
    
    if not full_path.exists() or not full_path.is_file():
        logger.warning("File not found", 
                      pack=pack_name, 
                      file=file_path, 
                      client_ip=client_ip)
        raise HTTPException(404, "File not found")
    
    logger.info("Serving file", 
                pack=pack_name, 
                file=file_path, 
                size=full_path.stat().st_size,
                client_ip=client_ip)
    
    return FileResponse(full_path)


@app.get("/launcher/version")
async def get_launcher_version():
    """Возвращает информацию о текущей версии лаунчера"""
    version_file = Path("builds/build.version")
    
    version = "1.0.0"
    if version_file.exists():
        version = version_file.read_text().strip()

    return {
        "version": version,
        "download_jar": "/launcher/download?type=jar",
        "download_exe": "/launcher/download?type=exe",
        "updated_at": datetime.utcnow().isoformat()
    }


@app.get("/launcher/download")
async def download_launcher(type: str = "exe"):
    """Отдаёт файл лаунчера"""
    if type == "exe":
        file_path = Path("builds/ZernMCLauncher.exe")
        filename = "ZernMCLauncher.exe"
    else:
        file_path = Path("builds/ZernMCLauncher.jar")
        filename = "ZernMCLauncher.jar"

    if not file_path.exists():
        raise HTTPException(404, "Launcher file not found")

    return FileResponse(
        path=file_path,
        filename=filename,
        media_type="application/octet-stream"
    )

if __name__ == "__main__":
    args = parse_args()
    
    if args.test:
        # Test mode runs within lifespan
        import asyncio
        asyncio.run(run_test_mode())
    elif args.dev:
        run_development_mode(args.host, args.port, args.reload)
    else:
        # Default to production
        run_production_mode(args.host, args.port, args.workers)
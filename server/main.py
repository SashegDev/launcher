from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from contextlib import asynccontextmanager
from pathlib import Path
import json
import structlog
from cachetools import TTLCache
import logging
from datetime import datetime
from uvicorn.protocols.http.httptools_impl import HttpToolsProtocol

# Import local modules
from pack_manager import DATA_DIR, scan_pack, get_cached_manifest, PACKS_DIR
from models import PackMeta
from middleware import LoggingMiddleware
from cli import parse_args, run_test_mode, run_production_mode, run_development_mode
from log_manager import init_logging

logger = structlog.get_logger(__name__)

# Cache for manifests - expires after 5 minutes
manifest_cache = TTLCache(maxsize=100, ttl=300)

BUILDS_DIR = Path("builds")


@asynccontextmanager
async def lifespan(app: FastAPI):
    args = parse_args()
    
    # Initialize logging
    init_logging()
    #logger = logging.getLogger(__name__)
    
    # Determine environment
    if args.test:
        env = "test"
    elif args.dev:
        env = "development"
    else:
        env = "production"
    
    logger.info(f"Starting ZernMC Launcher Server (environment: {env})")
    
    # Create directories if they don't exist
    BUILDS_DIR.mkdir(exist_ok=True)
    PACKS_DIR.mkdir(exist_ok=True)
    DATA_DIR.mkdir(exist_ok=True)
    
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
        
        # Показываем первые 200 байт запроса в HEX для диагностики
        hex_preview = data[:100].hex() if len(data) > 0 else "empty"
        
        logger.error(f"Invalid HTTP request from {client}")
        logger.error(f"Error: {str(e)}")
        logger.error(f"First 100 bytes (hex): {hex_preview}")
        
        try:
            raw_data = data[:500].decode('utf-8', errors='replace')
            logger.error(f"Raw request data: {repr(raw_data)}")
        except:
            pass
        
        # Не перевыбрасываем исключение, а возвращаем 400 ответ
        # Это важно! Иначе клиент не получит ответ
        try:
            response = b"HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nContent-Length: 21\r\n\r\nInvalid HTTP request"
            self.transport.write(response)
            self.transport.close()
        except:
            pass
        return

HttpToolsProtocol.data_received = patched_data_received


# ====================== ОСНОВНЫЕ ЭНДПОИНТЫ ======================

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


# ====================== ЭНДПОИНТЫ ДЛЯ ПАКОВ ======================

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
                    with open(meta_path, 'r', encoding='utf-8') as f:
                        meta = json.load(f)
                        # Исправлено: конвертируем updated_at в строку если это datetime
                        updated_at = meta.get("updated_at")
                        if updated_at and isinstance(updated_at, datetime):
                            updated_at = updated_at.isoformat()
                        
                        packs.append({
                            "name": pack_dir.name,
                            "version": meta.get("version", 1),
                            "files_count": len(meta.get("files", {})),
                            "updated_at": updated_at,
                            "minecraft_version": meta.get("minecraft_version", "unknown"),
                            "loader_type": meta.get("loader_type", "vanilla"),
                            "loader_version": meta.get("loader_version")
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


@app.post("/pack/{pack_name}/diff")
async def get_pack_diff(pack_name: str, request: Request):
    """
    Client sends: { "mods/jei.jar": "sha256_hash", ... }
    Server returns diff information
    """
    client_ip = request.client.host if request.client else "unknown"
    
    # Читаем тело запроса
    try:
        body = await request.json()
    except Exception as e:
        logger.error(f"Failed to parse JSON body: {e}")
        raise HTTPException(400, "Invalid JSON body")
    
    logger.info("Received diff request", 
                pack=pack_name, 
                client_files_count=len(body),
                client_ip=client_ip)

    try:
        meta = get_cached_manifest(pack_name)
        if not meta:
            meta = await scan_pack(pack_name)
    except FileNotFoundError:
        logger.warning("Pack not found", pack=pack_name, client_ip=client_ip)
        raise HTTPException(404, "Pack not found")
    except Exception as e:
        logger.error("Error loading pack meta", pack=pack_name, error=str(e), exc_info=True)
        raise HTTPException(500, f"Internal server error: {str(e)}")

    to_download = []
    to_delete = []
    to_update = []

    server_files = meta.files

    for path, entry in server_files.items():
        client_hash = body.get(path)
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

    for path in body:
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


@app.get("/pack/{pack_name}")
async def get_pack_manifest(pack_name: str, request: Request):
    """Get pack manifest with caching"""
    client_ip = request.client.host if request.client else "unknown"
    
    cached_meta = get_cached_manifest(pack_name)
    if cached_meta:
        logger.debug("Manifest served from cache", 
                    pack=pack_name, 
                    version=cached_meta.version,
                    client_ip=client_ip)
        
        # Исправлено: конвертируем datetime в строку при сериализации
        return JSONResponse(
            content=cached_meta.model_dump(mode='json'),
            headers={"X-Pack-Version": str(cached_meta.version), "X-Cached": "true"}
        )
    
    meta_path = Path("data") / f"{pack_name}.meta"
    if not meta_path.exists():
        logger.warning("Manifest requested but pack not found", pack=pack_name, client_ip=client_ip)
        raise HTTPException(404, "Pack not found")
    
    with open(meta_path, 'r', encoding='utf-8') as f:
        meta_dict = json.load(f)
        # Исправлено: используем model_validate для создания объекта
        meta = PackMeta.model_validate(meta_dict)
        manifest_cache[pack_name] = meta
    
    logger.debug("Manifest served from disk", 
                pack=pack_name, 
                version=meta.version,
                client_ip=client_ip)
    
    # Исправлено: конвертируем datetime в строку при сериализации
    return JSONResponse(
        content=meta.model_dump(mode='json'),
        headers={"X-Pack-Version": str(meta.version), "X-Cached": "false"}
    )


@app.get("/pack/{pack_name}/file/{file_path:path}")
async def get_pack_file(pack_name: str, file_path: str, request: Request):
    """Get a file from a pack"""
    full_path = PACKS_DIR / pack_name / file_path
    client_ip = request.client.host if request.client else None
    
    # Security: prevent path traversal
    if ".." in file_path:
        raise HTTPException(403, "Invalid file path")
    
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


# ====================== ЭНДПОИНТЫ ДЛЯ ЛАУНЧЕРА ======================

def get_current_launcher_version() -> str:
    """Get current launcher version from build.version file"""
    version_file = BUILDS_DIR / "build.version"
    if version_file.exists():
        return version_file.read_text().strip()
    return "1.0.0"


def get_available_zips() -> list:
    """Get list of available zip archives"""
    if not BUILDS_DIR.exists():
        return []
    
    zips = []
    for zip_file in BUILDS_DIR.glob("ZernMCLauncher-*.zip"):
        version = zip_file.stem.replace("ZernMCLauncher-", "")
        stat = zip_file.stat()
        zips.append({
            "version": version,
            "filename": zip_file.name,
            "size": stat.st_size,
            "modified": datetime.fromtimestamp(stat.st_mtime).isoformat()
        })
    
    zips.sort(key=lambda x: x["version"], reverse=True)
    return zips


@app.get("/launcher/version")
async def get_launcher_version():
    """Return launcher version information"""
    version = get_current_launcher_version()
    zips = get_available_zips()
    
    response = {
        "version": version,
        "updated_at": datetime.utcnow().isoformat()
    }
    
    jar_path = BUILDS_DIR / "ZernMCLauncher.jar"
    if jar_path.exists():
        response["download_jar"] = "/launcher/download/jar"
        response["jar_size"] = jar_path.stat().st_size
    
    exe_path = BUILDS_DIR / "ZernMCLauncher.exe"
    if exe_path.exists():
        response["download_exe"] = "/launcher/download/exe"
        response["exe_size"] = exe_path.stat().st_size
    
    if zips:
        response["download_zip"] = f"/launcher/download/zip/{zips[0]['filename']}"
        response["zip_version"] = zips[0]["version"]
        response["zip_size"] = zips[0]["size"]
        response["all_zips"] = zips
    
    return response


@app.get("/launcher/download/jar")
async def download_launcher_jar():
    """Download launcher JAR file"""
    file_path = BUILDS_DIR / "ZernMCLauncher.jar"
    
    if not file_path.exists():
        raise HTTPException(404, "JAR file not found")
    
    return FileResponse(
        path=file_path,
        filename="ZernMCLauncher.jar",
        media_type="application/java-archive"
    )


@app.get("/launcher/download/exe")
async def download_launcher_exe():
    """Download launcher EXE file (Windows)"""
    file_path = BUILDS_DIR / "ZernMCLauncher.exe"
    
    if not file_path.exists():
        raise HTTPException(404, "EXE file not found")
    
    return FileResponse(
        path=file_path,
        filename="ZernMCLauncher.exe",
        media_type="application/vnd.microsoft.portable-executable"
    )


@app.get("/launcher/download/zip/{filename}")
async def download_launcher_zip(filename: str):
    """Download specific launcher ZIP archive"""
    if ".." in filename or not filename.startswith("ZernMCLauncher-") or not filename.endswith(".zip"):
        raise HTTPException(400, "Invalid filename")
    
    file_path = BUILDS_DIR / filename
    
    if not file_path.exists():
        raise HTTPException(404, "ZIP file not found")
    
    return FileResponse(
        path=file_path,
        filename=filename,
        media_type="application/zip"
    )


@app.get("/launcher/download/latest")
async def download_latest_launcher():
    """Download the latest launcher (prefer ZIP if available, fallback to JAR)"""
    zips = get_available_zips()
    
    if zips:
        latest_zip = zips[0]["filename"]
        return await download_launcher_zip(latest_zip)
    
    jar_path = BUILDS_DIR / "ZernMCLauncher.jar"
    if jar_path.exists():
        return await download_launcher_jar()
    
    raise HTTPException(404, "No launcher files available")


@app.get("/launcher/info")
async def get_launcher_full_info():
    """Full launcher information with all available files"""
    version = get_current_launcher_version()
    zips = get_available_zips()
    
    info = {
        "current_version": version,
        "updated_at": datetime.utcnow().isoformat(),
        "files": {
            "jar": None,
            "exe": None,
            "zips": zips
        },
        "recommended": "zip" if zips else ("exe" if (BUILDS_DIR / "ZernMCLauncher.exe").exists() else "jar")
    }
    
    jar_path = BUILDS_DIR / "ZernMCLauncher.jar"
    if jar_path.exists():
        info["files"]["jar"] = {
            "size": jar_path.stat().st_size,
            "download_url": "/launcher/download/jar"
        }
    
    exe_path = BUILDS_DIR / "ZernMCLauncher.exe"
    if exe_path.exists():
        info["files"]["exe"] = {
            "size": exe_path.stat().st_size,
            "download_url": "/launcher/download/exe"
        }
    
    if zips:
        info["files"]["latest_zip"] = zips[0]
        info["files"]["download_latest"] = "/launcher/download/latest"
    
    return info


# ====================== ЗАПУСК ======================

if __name__ == "__main__":
    args = parse_args()
    
    if args.test:
        import asyncio
        asyncio.run(run_test_mode())
    elif args.dev:
        run_development_mode(args.host, args.port, args.reload)
    else:
        run_production_mode(args.host, args.port, args.workers)
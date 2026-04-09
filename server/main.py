from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from contextlib import asynccontextmanager
from pathlib import Path
import json
import structlog
from cachetools import TTLCache
import logging
from datetime import datetime
from uvicorn.protocols.http.httptools_impl import HttpToolsProtocol

from cachetools import TTLCache
from urllib.parse import urlparse

from pack_manager import DATA_DIR, scan_pack, get_cached_manifest, PACKS_DIR
from models import PackMeta
from middleware import LoggingMiddleware
from cli import parse_args, run_test_mode, run_production_mode, run_development_mode
from log_manager import init_logging

import re

import httpx
import base64
from fastapi.responses import StreamingResponse

from auth import get_current_user, router as auth_router, init_db, verify_jwt
from roles import Permissions, has_permission

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

    BLOCKED_HOSTS = []

    init_db()

    app.include_router(auth_router)
    
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
async def list_packs(current_user: dict = Depends(get_current_user)):
    """List all available packs - требует проходку для просмотра"""
    
    # Проверяем, есть ли право на просмотр сборок
    if not has_permission(current_user["role"], Permissions.VIEW_PACKS):
        raise HTTPException(
            status_code=403, 
            detail="Для просмотра сборок требуется активная проходка"
        )
    
    packs = []
    
    for pack_dir in PACKS_DIR.iterdir():
        if pack_dir.is_dir():
            meta_path = DATA_DIR / f"{pack_dir.name}.meta"
            if meta_path.exists():
                try:
                    with open(meta_path, 'r', encoding='utf-8') as f:
                        meta = json.load(f)
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
async def get_pack_diff(
    pack_name: str, 
    request: Request,
    current_user: dict = Depends(get_current_user)  # Добавляем зависимость
):
    """Client sends: { "mods/jei.jar": "sha256_hash", ... }
    Server returns diff information
    ТРЕБУЕТ ПРОХОДКУ ДЛЯ СКАЧИВАНИЯ"""
    
    # Проверяем наличие проходки
    if not has_permission(current_user["role"], Permissions.DOWNLOAD_PACK):
        raise HTTPException(
            status_code=403, 
            detail="Для скачивания сборок требуется активная проходка. Обратитесь к администратору."
        )
    
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


# ====================== ПРОКСИ ЭНДПОИНТЫ ======================
# Эти эндпоинты позволяют клиентам с сетевыми проблемами
# скачивать файлы через сервер Zern

# Создаем HTTP клиент для прокси
proxy_client = httpx.AsyncClient(timeout=60.0, follow_redirects=True)

# Кэш для часто запрашиваемых данных (5 минут)
proxy_cache = TTLCache(maxsize=50, ttl=300)

@app.get("/proxy/fabric/versions/loader")
async def proxy_fabric_versions(request: Request):
    """Прокси для Fabric Meta API - список версий загрузчика"""
    client_ip = request.client.host if request.client else "unknown"
    logger.info(f"Proxy request: Fabric versions from {client_ip}")
    
    url = "https://meta.fabricmc.net/v2/versions/loader"
    
    # Проверяем кэш
    if url in proxy_cache:
        logger.debug(f"Proxy cache hit for {url}")
        return JSONResponse(content=proxy_cache[url])
    
    try:
        response = await proxy_client.get(url)
        response.raise_for_status()
        data = response.json()
        
        # Кэшируем
        proxy_cache[url] = data
        
        logger.info(f"Proxy success: Fabric versions ({len(data)} items)")
        return JSONResponse(content=data)
        
    except httpx.HTTPError as e:
        logger.error(f"Proxy error for Fabric versions: {e}")
        raise HTTPException(502, f"Bad Gateway: {str(e)}")
    except Exception as e:
        logger.error(f"Proxy unexpected error: {e}")
        raise HTTPException(500, f"Internal error: {str(e)}")


@app.get("/proxy/fabric/installer/latest")
async def proxy_fabric_installer_latest(request: Request):
    """Получить последнюю версию Fabric Installer"""
    client_ip = request.client.host if request.client else "unknown"
    logger.info(f"Proxy request: Fabric installer latest from {client_ip}")
    
    url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/maven-metadata.xml"
    
    try:
        response = await proxy_client.get(url)
        response.raise_for_status()
        xml = response.text
        
        # Парсим последнюю версию из XML
        match = re.search(r'<latest>([^<]+)</latest>', xml)
        if match:
            version = match.group(1)
            logger.info(f"Proxy success: Latest Fabric installer version = {version}")
            return JSONResponse(content={"version": version})
        else:
            raise HTTPException(500, "Could not parse latest version")
            
    except httpx.HTTPError as e:
        logger.error(f"Proxy error for Fabric installer latest: {e}")
        raise HTTPException(502, f"Bad Gateway: {str(e)}")


@app.get("/proxy/fabric/installer/{version}")
async def proxy_fabric_installer_url(version: str, request: Request):
    """Получить URL для скачивания Fabric Installer определенной версии"""
    client_ip = request.client.host if request.client else "unknown"
    logger.info(f"Proxy request: Fabric installer URL for v{version} from {client_ip}")
    
    url = f"https://maven.fabricmc.net/net/fabricmc/fabric-installer/{version}/fabric-installer-{version}.jar"
    
    return JSONResponse(content={"url": url, "version": version})


@app.get("/proxy/fabric/maven/{path:path}")
async def proxy_fabric_maven(path: str, request: Request):
    """Прокси для Fabric Maven файлов"""
    client_ip = request.client.host if request.client else "unknown"
    logger.info(f"Proxy request: Fabric Maven {path} from {client_ip}")
    
    full_url = f"https://maven.fabricmc.net/{path}"
    
    try:
        response = await proxy_client.get(full_url)
        response.raise_for_status()
        
        # Определяем content-type
        content_type = "application/octet-stream"
        if path.endswith(".jar"):
            content_type = "application/java-archive"
        elif path.endswith(".pom"):
            content_type = "application/xml"
        
        return Response(
            content=response.content,
            media_type=content_type,
            headers={"X-Proxied-By": "ZernMC"}
        )
        
    except httpx.HTTPError as e:
        logger.error(f"Proxy error for Fabric Maven {path}: {e}")
        raise HTTPException(502, f"Bad Gateway: {str(e)}")


@app.get("/proxy/mojang/version_manifest")
async def proxy_mojang_manifest(request: Request):
    """Прокси для Mojang version manifest"""
    client_ip = request.client.host if request.client else "unknown"
    logger.info(f"Proxy request: Mojang manifest from {client_ip}")
    
    url = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    
    if url in proxy_cache:
        return JSONResponse(content=proxy_cache[url])
    
    try:
        response = await proxy_client.get(url)
        response.raise_for_status()
        data = response.json()
        
        proxy_cache[url] = data
        logger.info("Proxy success: Mojang manifest")
        return JSONResponse(content=data)
        
    except httpx.HTTPError as e:
        logger.error(f"Proxy error for Mojang manifest: {e}")
        raise HTTPException(502, f"Bad Gateway: {str(e)}")


@app.get("/proxy/mojang/version/{version_id}")
async def proxy_mojang_version(version_id: str, request: Request):
    """Прокси для конкретной версии Mojang"""
    client_ip = request.client.host if request.client else "unknown"
    logger.info(f"Proxy request: Mojang version {version_id} from {client_ip}")
    
    # Сначала получаем манифест, чтобы найти URL версии
    manifest_url = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    
    cache_key = f"version_url_{version_id}"
    version_url = proxy_cache.get(cache_key)
    
    if not version_url:
        try:
            response = await proxy_client.get(manifest_url)
            response.raise_for_status()
            manifest = response.json()
            
            for version in manifest.get("versions", []):
                if version.get("id") == version_id:
                    version_url = version.get("url")
                    proxy_cache[cache_key] = version_url
                    break
                    
            if not version_url:
                raise HTTPException(404, f"Version {version_id} not found")
                
        except httpx.HTTPError as e:
            logger.error(f"Proxy error getting version URL: {e}")
            raise HTTPException(502, f"Bad Gateway: {str(e)}")
    
    try:
        response = await proxy_client.get(version_url)
        response.raise_for_status()
        data = response.json()
        
        logger.info(f"Proxy success: Mojang version {version_id}")
        return JSONResponse(content=data)
        
    except httpx.HTTPError as e:
        logger.error(f"Proxy error for Mojang version {version_id}: {e}")
        raise HTTPException(502, f"Bad Gateway: {str(e)}")


@app.get("/proxy/forge/versions")
async def proxy_forge_versions(request: Request):
    """Прокси для списка версий Forge"""
    client_ip = request.client.host if request.client else "unknown"
    logger.info(f"Proxy request: Forge versions from {client_ip}")
    
    url = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
    
    try:
        response = await proxy_client.get(url)
        response.raise_for_status()
        
        # Возвращаем XML как есть
        return Response(
            content=response.content,
            media_type="application/xml",
            headers={"X-Proxied-By": "ZernMC"}
        )
        
    except httpx.HTTPError as e:
        logger.error(f"Proxy error for Forge versions: {e}")
        raise HTTPException(502, f"Bad Gateway: {str(e)}")


@app.get("/proxy/forge/maven/{path:path}")
async def proxy_forge_maven(path: str, request: Request):
    """Прокси для Forge Maven файлов"""
    client_ip = request.client.host if request.client else "unknown"
    logger.info(f"Proxy request: Forge Maven {path} from {client_ip}")
    
    full_url = f"https://maven.minecraftforge.net/{path}"
    
    try:
        response = await proxy_client.get(full_url)
        response.raise_for_status()
        
        content_type = "application/octet-stream"
        if path.endswith(".jar"):
            content_type = "application/java-archive"
        elif path.endswith(".pom"):
            content_type = "application/xml"
        
        return Response(
            content=response.content,
            media_type=content_type,
            headers={"X-Proxied-By": "ZernMC"}
        )
        
    except httpx.HTTPError as e:
        logger.error(f"Proxy error for Forge Maven {path}: {e}")
        raise HTTPException(502, f"Bad Gateway: {str(e)}")


@app.get("/proxy/download")
async def proxy_download(request: Request):
    """Универсальный прокси для скачивания файлов"""
    client_ip = request.client.host if request.client else "unknown"
    url = request.query_params.get("url")
    
    if not url:
        raise HTTPException(400, "Missing 'url' parameter")
    
    # Безопасность: проверяем URL
    allowed_domains = [
        "maven.fabricmc.net",
        "meta.fabricmc.net",
        "piston-meta.mojang.com",
        "launchermeta.mojang.com",
        "resources.download.minecraft.net",
        "maven.minecraftforge.net",
        "files.minecraftforge.net"
    ]
    
    # Проверяем, что URL ведет на разрешенный домен
    parsed = urlparse(url)
    domain = parsed.netloc.lower()
    
    # Убираем порт если есть
    domain = domain.split(':')[0]
    
    if domain not in allowed_domains and not any(domain.endswith(f".{d}") for d in allowed_domains):
        logger.warning(f"Proxy blocked: {domain} not in allowed list (client: {client_ip}, url: {url[:100]})")
        raise HTTPException(403, f"Domain {domain} not allowed")
    
    logger.info(f"Proxy download: {url[:100]}... from {client_ip}")
    
    try:
        # Используем streaming response для больших файлов
        response = await proxy_client.get(url)
        response.raise_for_status()
        
        # Определяем content-type из ответа или по расширению
        content_type = response.headers.get("content-type", "application/octet-stream")
        
        return Response(
            content=response.content,
            media_type=content_type,
            headers={
                "X-Proxied-By": "ZernMC",
                "X-Original-Url": url[:100]  # только для отладки
            }
        )
        
    except httpx.HTTPError as e:
        logger.error(f"Proxy download error for {url[:100]}: {e}")
        raise HTTPException(502, f"Bad Gateway: {str(e)}")


@app.get("/proxy/asset/{hash}")
async def proxy_asset(hash: str, request: Request):
    """Прокси для Minecraft ассетов по хешу"""
    client_ip = request.client.host if request.client else "unknown"
    
    if len(hash) < 2:
        raise HTTPException(400, "Invalid hash")
    
    url = f"https://resources.download.minecraft.net/{hash[:2]}/{hash}"
    logger.info(f"Proxy asset: {hash} from {client_ip}")
    
    try:
        response = await proxy_client.get(url)
        response.raise_for_status()
        
        return Response(
            content=response.content,
            media_type="application/octet-stream",
            headers={"X-Proxied-By": "ZernMC"}
        )
        
    except httpx.HTTPError as e:
        logger.error(f"Proxy asset error for {hash}: {e}")
        raise HTTPException(502, f"Bad Gateway: {str(e)}")


@app.get("/proxy/status")
async def proxy_status():
    """Проверка статуса прокси сервера"""
    return {
        "status": "ok",
        "cached_items": len(proxy_cache),
        "allowed_domains": [
            "maven.fabricmc.net",
            "meta.fabricmc.net", 
            "piston-meta.mojang.com",
            "launchermeta.mojang.com",
            "resources.download.minecraft.net",
            "maven.minecraftforge.net"
        ],
        "note": "Use this proxy if you have network issues connecting to Fabric/Mojang/Forge"
    }


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.error("Unhandled exception", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"detail": "Internal Server Error", "type": type(exc).__name__}
    )


# Cleanup on shutdown
@app.on_event("shutdown")
async def shutdown_proxy():
    await proxy_client.close()


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
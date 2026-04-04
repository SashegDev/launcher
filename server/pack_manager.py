# pack_manager.py (updated)
import hashlib
import os
from datetime import datetime
from pathlib import Path
import json
import aiofiles
from typing import Optional, Dict
import structlog

from models import PackMeta, FileEntry

import aiohttp
from typing import List, Optional

logger = structlog.get_logger(__name__)

PACKS_DIR = Path("packs")
DATA_DIR = Path("data")
DATA_DIR.mkdir(exist_ok=True)

MINECRAFT_VERSION_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json"
FABRIC_META_URL = "https://meta.fabricmc.net/v2/versions"
FORGE_META_URL = "https://files.minecraftforge.net/net/minecraftforge/forge/"
MinecraftVersion=[]

# Cache for loaded manifests
_manifest_cache: Dict[str, PackMeta] = {}

def get_cached_manifest(pack_name: str) -> Optional[PackMeta]:
    """Get manifest from memory cache if available"""
    return _manifest_cache.get(pack_name)

def get_meta_path(pack_name: str) -> Path:
    return DATA_DIR / f"{pack_name}.meta"

async def calculate_sha256(file_path: Path) -> str:
    hash_sha = hashlib.sha256()
    async with aiofiles.open(file_path, 'rb') as f:
        while chunk := await f.read(8192):
            hash_sha.update(chunk)
    return hash_sha.hexdigest()

async def get_minecraft_versions() -> List[MinecraftVersion]:
    """Fetch available Minecraft versions from Mojang"""
    async with aiohttp.ClientSession() as session:
        async with session.get(MINECRAFT_VERSION_MANIFEST_URL) as response:
            data = await response.json()
            versions = []
            for v in data.get("versions", []):
                versions.append(MinecraftVersion(
                    version=v["id"],
                    type=v["type"],
                    release_time=datetime.fromisoformat(v["releaseTime"].replace('Z', '+00:00')),
                    url=v["url"]
                ))
            return versions

async def get_fabric_versions(minecraft_version: str) -> List[str]:
    """Get available Fabric versions for specific Minecraft version"""
    async with aiohttp.ClientSession() as session:
        async with session.get(f"{FABRIC_META_URL}/loader") as response:
            data = await response.json()
            fabric_versions = []
            for loader in data:
                if loader.get("stable", True):
                    fabric_versions.append(loader["version"])
            return fabric_versions

async def get_forge_versions(minecraft_version: str) -> List[str]:
    """Get available Forge versions for specific Minecraft version"""
    async with aiohttp.ClientSession() as session:
        async with session.get(FORGE_META_URL) as response:
            # Forge API is more complex, simplified for now
            # You might want to use a proper forge API
            return []  # Placeholder

async def download_minecraft_version(version: str, target_path: Path) -> bool:
    """Download Minecraft version JSON and client jar"""
    # Get version manifest
    async with aiohttp.ClientSession() as session:
        async with session.get(f"https://piston-meta.mojang.com/mc/game/{version}/{version}.json") as response:
            if response.status == 200:
                version_data = await response.json()
                # Save version JSON
                async with aiofiles.open(target_path / f"{version}.json", 'w') as f:
                    await f.write(json.dumps(version_data, indent=2))
                
                # Download client jar
                downloads = version_data.get("downloads", {})
                client_info = downloads.get("client", {})
                if client_info:
                    client_url = client_info.get("url")
                    async with session.get(client_url) as client_response:
                        if client_response.status == 200:
                            async with aiofiles.open(target_path / f"{version}.jar", 'wb') as f:
                                async for chunk in client_response.content.iter_chunked(8192):
                                    await f.write(chunk)
                            return True
    return False


async def scan_pack(pack_name: str, force_rescan: bool = False) -> PackMeta:
    """Scan pack directory and update manifest if needed"""
    pack_path = PACKS_DIR / pack_name
    if not pack_path.exists() or not pack_path.is_dir():
        raise FileNotFoundError(f"Pack {pack_name} not found")

    meta_path = get_meta_path(pack_name)
    current_meta: Optional[PackMeta] = None

    # Check if we have cached version and force_rescan is False
    if not force_rescan and pack_name in _manifest_cache:
        return _manifest_cache[pack_name]

    if meta_path.exists():
        async with aiofiles.open(meta_path, 'r', encoding='utf-8') as f:
            data = json.loads(await f.read())
            current_meta = PackMeta.model_validate(data)

    new_files: Dict[str, FileEntry] = {}
    changed = False

    for root, dirs, files in os.walk(pack_path):
        # Filter ignored directories
        ignored = current_meta.ignored_dirs if current_meta else []
        dirs[:] = [d for d in dirs if d not in ignored]

        for file in files:
            file_path = Path(root) / file
            rel_path = file_path.relative_to(pack_path).as_posix()

            # Skip files in ignored directories
            if any(ignored_dir in rel_path.split('/') for ignored_dir in ignored):
                continue

            stat = file_path.stat()
            file_hash = await calculate_sha256(file_path)

            entry = FileEntry(
                path=rel_path,
                hash=file_hash,
                size=stat.st_size,
                added_at=datetime.utcfromtimestamp(stat.st_ctime),
                modified_at=datetime.utcfromtimestamp(stat.st_mtime)
            )

            new_files[rel_path] = entry

            if current_meta and (rel_path not in current_meta.files or 
                                 current_meta.files[rel_path].hash != file_hash):
                changed = True

    # Update manifest if changes detected or new pack
    if not current_meta or changed or len(new_files) != len(current_meta.files if current_meta else 0):
        version = (current_meta.version + 1) if current_meta else 1
        new_meta = PackMeta(
            pack_name=pack_name,
            version=version,
            files=new_files,
            updated_at=datetime.utcnow(),
            ignored_dirs=current_meta.ignored_dirs if current_meta else []
        )
        
        async with aiofiles.open(meta_path, 'w', encoding='utf-8') as f:
            await f.write(new_meta.model_dump_json(indent=2))
        
        # Update memory cache
        _manifest_cache[pack_name] = new_meta
        
        logger.info("Pack updated", pack=pack_name, new_version=version, files_count=len(new_files))
        return new_meta
    
    # Update cache with existing manifest
    if current_meta:
        _manifest_cache[pack_name] = current_meta
    
    return current_meta
import hashlib
import os
from datetime import datetime
from pathlib import Path
import json
from typing import Optional, Dict
import structlog

from models import PackMeta, FileEntry

logger = structlog.get_logger(__name__)

PACKS_DIR = Path("packs")
DATA_DIR = Path("data")
DATA_DIR.mkdir(exist_ok=True)

# Cache for loaded manifests
_manifest_cache: Dict[str, PackMeta] = {}

def get_cached_manifest(pack_name: str) -> Optional[PackMeta]:
    """Get manifest from memory cache if available"""
    return _manifest_cache.get(pack_name)

def get_meta_path(pack_name: str) -> Path:
    return DATA_DIR / f"{pack_name}.meta"

def calculate_sha256_sync(file_path: Path) -> str:
    """Calculate SHA256 hash of a file (synchronous version)"""
    hash_sha = hashlib.sha256()
    with open(file_path, 'rb') as f:
        while chunk := f.read(8192):
            hash_sha.update(chunk)
    return hash_sha.hexdigest()

async def calculate_sha256(file_path: Path) -> str:
    """Calculate SHA256 hash of a file (async wrapper)"""
    # Используем синхронную версию для простоты
    return calculate_sha256_sync(file_path)

async def scan_pack(pack_name: str, force_rescan: bool = False) -> PackMeta:
    """Scan pack directory and update manifest if needed"""
    pack_path = PACKS_DIR / pack_name
    
    if not pack_path.exists() or not pack_path.is_dir():
        raise FileNotFoundError(f"Pack {pack_name} not found")

    meta_path = get_meta_path(pack_name)
    current_meta: Optional[PackMeta] = None

    # Check cache first
    if not force_rescan and pack_name in _manifest_cache:
        return _manifest_cache[pack_name]

    # Load existing meta if available (синхронно)
    if meta_path.exists():
        try:
            with open(meta_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
                current_meta = PackMeta.model_validate(data)
        except Exception as e:
            logger.warning(f"Failed to load existing meta for pack {pack_name}: {e}")
            current_meta = None

    new_files: Dict[str, FileEntry] = {}
    changed = False

    # Get ignored directories
    ignored_dirs = current_meta.ignored_dirs if current_meta else [
        "resourcepacks", "shaderpacks", "saves", "logs",
        "crash-reports", "screenshots", "journeymap", "config"
    ]

    # Walk through pack directory
    for root, dirs, files in os.walk(pack_path):
        # Filter ignored directories
        dirs[:] = [d for d in dirs if d not in ignored_dirs]

        for file in files:
            file_path = Path(root) / file
            rel_path = file_path.relative_to(pack_path).as_posix()

            # Skip if in ignored directory
            if any(ignored_dir in rel_path.split('/') for ignored_dir in ignored_dirs):
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

            # Check if file changed
            if current_meta and (rel_path not in current_meta.files or 
                                 current_meta.files[rel_path].hash != file_hash):
                changed = True

    # Check if we need to update
    if not current_meta or changed or len(new_files) != len(current_meta.files if current_meta else 0):
        version = (current_meta.version + 1) if current_meta else 1
        
        # Load instance.json for pack metadata
        minecraft_version = "1.20.4"
        loader_type = "vanilla"
        loader_version = None
        
        pack_config_path = pack_path / "instance.json"
        if pack_config_path.exists():
            try:
                # Синхронное чтение конфига
                with open(pack_config_path, 'r', encoding='utf-8') as f:
                    config = json.load(f)
                    minecraft_version = config.get("minecraftVersion", minecraft_version)
                    loader_type = config.get("loaderType", loader_type)
                    loader_version = config.get("loaderVersion")
            except Exception as e:
                logger.warning(f"Failed to load instance.json for {pack_name}: {e}")
        
        # Create new manifest
        new_meta = PackMeta(
            pack_name=pack_name,
            version=version,
            files=new_files,
            updated_at=datetime.utcnow(),
            ignored_dirs=ignored_dirs,
            minecraft_version=minecraft_version,
            loader_type=loader_type,
            loader_version=loader_version
        )
        
        # Save to disk (синхронно)
        with open(meta_path, 'w', encoding='utf-8') as f:
            f.write(new_meta.model_dump_json(indent=2))
        
        # Update cache
        _manifest_cache[pack_name] = new_meta
        
        logger.info(f"Pack updated: {pack_name} v{version}, {len(new_files)} files")
        return new_meta
    
    # No changes, use existing
    if current_meta:
        _manifest_cache[pack_name] = current_meta
        return current_meta
    
    # Should not happen
    raise Exception(f"Failed to scan pack {pack_name}")
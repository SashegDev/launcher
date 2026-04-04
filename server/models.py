from pydantic import BaseModel, Field
from typing import Dict, List, Optional
from datetime import datetime
import structlog

logger = structlog.get_logger(__name__)

class FileEntry(BaseModel):
    path: str                    # относительный путь (mods/jei.jar)
    hash: str                    # sha256
    size: int
    added_at: datetime
    modified_at: datetime


class LaunchConfig(BaseModel):
    mainClass: str
    classpath: List[str] = Field(default_factory=list)   # относительные пути от gameDir
    jvmArgs: List[str] = Field(default_factory=list)
    gameArgs: List[str] = Field(default_factory=list)
    nativesPath: Optional[str] = None                    # например: "natives"
    assetIndex: str = "1.20"                             # или актуальная версия
    minecraftVersion: str
    loaderType: str                                      # "vanilla" | "fabric" | "forge" | "neoforge" | "quilt"
    loaderVersion: Optional[str] = None
    gameDirectory: str = "."                             # "." = корень инсталляции пака (рекомендую)


class PackMeta(BaseModel):
    pack_name: str
    version: int = 1
    updated_at: datetime = Field(default_factory=datetime.utcnow)

    files: Dict[str, FileEntry] = Field(default_factory=dict)
    ignored_dirs: List[str] = Field(
        default_factory=lambda: [
            "resourcepacks", "shaderpacks", "saves", "logs",
            "crash-reports", "screenshots", "journeymap", "config"
        ]
    )

    # Основные поля (один раз!)
    minecraft_version: str
    loader_type: str
    loader_version: Optional[str] = None

    # Конфигурация запуска (обязательна)
    launch: LaunchConfig


class MinecraftVersion(BaseModel):
    version: str
    type: str  # release, snapshot, old_alpha, old_beta
    release_time: datetime
    url: Optional[str] = None


class ModLoader(BaseModel):
    type: str
    version: str
    minecraft_version: str
    installer_url: Optional[str] = None
    libraries: List[str] = Field(default_factory=list)
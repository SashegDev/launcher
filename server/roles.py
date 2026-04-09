# roles.py
from enum import IntEnum
from typing import Dict, Set

class UserRole(IntEnum):
    USER = 0           # Обычный пользователь
    PASS_HOLDER = 1    # Пользователь с проходкой
    MODERATOR = 2      # Модератор
    ELDER = 3          # Elder Moderator
    CREATOR = 4        # Создатель

ROLE_NAMES: Dict[int, str] = {
    UserRole.USER: "Игрок",
    UserRole.PASS_HOLDER: "Игрок [Проходка]",
    UserRole.MODERATOR: "Модератор",
    UserRole.ELDER: "Elder Moderator",
    UserRole.CREATOR: "Создатель"
}

# Права доступа
class Permissions:
    # Базовые права
    DOWNLOAD_PACK = "download_pack"           # Скачивание сборок
    VIEW_PACKS = "view_packs"                 # Просмотр списка сборок
    
    # Права модератора
    REQUEST_PASS = "request_pass"             # Запрос проходки для игрока
    VIEW_USER_LIST = "view_user_list"         # Просмотр списка пользователей
    
    # Права Elder Moderator
    APPROVE_PASS = "approve_pass"             # Одобрение проходок
    REJECT_PASS = "reject_pass"               # Отклонение проходок
    VIEW_PASS_REQUESTS = "view_pass_requests" # Просмотр запросов проходок
    MANAGE_MODERATORS = "manage_moderators"   # Управление модераторами
    
    # Права создателя
    DIRECT_PASS = "direct_pass"               # Прямая выдача проходки
    MANAGE_ELDER = "manage_elder"             # Управление Elder
    MANAGE_SERVER = "manage_server"           # Управление сервером
    VIEW_AUDIT_LOG = "view_audit_log"         # Просмотр логов

# Маппинг ролей на права
ROLE_PERMISSIONS: Dict[int, Set[str]] = {
    UserRole.USER: {
        # Обычный игрок НЕ может даже смотреть сборки!
        # Только авторизоваться и смотреть свой профиль
    },
    UserRole.PASS_HOLDER: {
        Permissions.VIEW_PACKS,      # Может видеть список сборок
        Permissions.DOWNLOAD_PACK,   # Может скачивать сборки
    },
    UserRole.MODERATOR: {
        Permissions.VIEW_PACKS,
        Permissions.DOWNLOAD_PACK,
        Permissions.REQUEST_PASS,     # Может запрашивать проходки для игроков
        Permissions.VIEW_USER_LIST,   # Может видеть список пользователей
    },
    UserRole.ELDER: {
        Permissions.VIEW_PACKS,
        Permissions.DOWNLOAD_PACK,
        Permissions.REQUEST_PASS,
        Permissions.VIEW_USER_LIST,
        Permissions.APPROVE_PASS,     # Может одобрять проходки
        Permissions.REJECT_PASS,      # Может отклонять проходки
        Permissions.VIEW_PASS_REQUESTS,
        Permissions.MANAGE_MODERATORS, # Может управлять модераторами
    },
    UserRole.CREATOR: {
        Permissions.VIEW_PACKS,
        Permissions.DOWNLOAD_PACK,
        Permissions.REQUEST_PASS,
        Permissions.VIEW_USER_LIST,
        Permissions.APPROVE_PASS,
        Permissions.REJECT_PASS,
        Permissions.VIEW_PASS_REQUESTS,
        Permissions.MANAGE_MODERATORS,
        Permissions.DIRECT_PASS,       # Прямая выдача проходки
        Permissions.MANAGE_ELDER,      # Управление Elder
        Permissions.MANAGE_SERVER,     # Управление сервером
        Permissions.VIEW_AUDIT_LOG,    # Просмотр логов
    }
}

def has_permission(role: int, permission: str) -> bool:
    """Проверка наличия права у роли"""
    return permission in ROLE_PERMISSIONS.get(role, set())

def require_permission(permission: str):
    """Декоратор для проверки права"""
    from functools import wraps
    from fastapi import HTTPException, Depends
    from auth import get_current_user
    
    def decorator(func):
        @wraps(func)
        async def wrapper(*args, current_user: dict = Depends(get_current_user), **kwargs):
            if not has_permission(current_user["role"], permission):
                raise HTTPException(403, f"Недостаточно прав. Требуется право: {permission}")
            return await func(*args, current_user=current_user, **kwargs)
        return wrapper
    return decorator
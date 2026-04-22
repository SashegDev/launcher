# admin_router.py
from fastapi import APIRouter, HTTPException, Depends, Request, status
from pydantic import BaseModel, Field
from typing import Optional, List
import structlog
import time
import secrets
from datetime import datetime

from auth import get_db, require_role, log_audit, get_current_user
from roles import (
    ROLE_PERMISSIONS, UserRole, ROLE_NAMES, has_permission, Permissions,
    ROLE_USER, ROLE_PASS_HOLDER, ROLE_MODERATOR, ROLE_ELDER, ROLE_CREATOR
)

logger = structlog.get_logger(__name__)

router = APIRouter(prefix="/admin", tags=["admin"])

# ====================== МОДЕЛИ ======================
class UpdateRoleRequest(BaseModel):
    user_id: int
    role: int = Field(..., ge=0, le=4)

class PassRequest(BaseModel):
    username: str
    reason: Optional[str] = None

class PassDecision(BaseModel):
    request_id: int
    approved: bool
    reason: Optional[str] = None

class CreatePassDirectRequest(BaseModel):
    username: str
    expires_days: Optional[int] = Field(None, ge=1, le=365)
    max_uses: int = Field(1, ge=1, le=10)

class BanUserRequest(BaseModel):
    user_id: int
    days: int = Field(..., ge=1, le=365)
    reason: str

# ====================== ЭНДПОИНТЫ ======================

@router.get("/users")
async def list_users(
    current_user: dict = Depends(require_role(ROLE_MODERATOR)),
    search: Optional[str] = None
):
    """Список пользователей (модераторы видят всех, но без sensitive данных)"""
    with get_db() as conn:
        query = "SELECT id, username, uuid, role, created_at, last_login, is_active"
        params = []
        
        if current_user["role"] < ROLE_ELDER:
            # Модераторы не видят забаненных
            query += " FROM users WHERE is_active = 1"
        else:
            query += " FROM users"
        
        if search:
            query += " AND (username LIKE ? OR email LIKE ?)"
            params.extend([f"%{search}%", f"%{search}%"])
        
        query += " ORDER BY role DESC, username"
        
        rows = conn.execute(query, params).fetchall()
        
        users = []
        for row in rows:
            user_data = {
                "id": row["id"],
                "username": row["username"],
                "uuid": row["uuid"],
                "role": row["role"],
                "role_name": ROLE_NAMES.get(row["role"], "Неизвестно"),
                "created_at": row["created_at"],
                "last_login": row["last_login"],
            }
            
            # Elder и Creator видят больше информации
            if current_user["role"] >= ROLE_ELDER:
                user_data["is_active"] = row["is_active"]
                # Получаем информацию о проходке
                pass_info = conn.execute("""
                    SELECT code, expires_at, activated_at
                    FROM user_passes up
                    JOIN passes p ON up.pass_code = p.code
                    WHERE up.user_id = ? AND (p.expires_at IS NULL OR p.expires_at > ?)
                    LIMIT 1
                """, (row["id"], time.time())).fetchone()
                
                if pass_info:
                    user_data["has_pass"] = True
                    user_data["pass_expires"] = pass_info["expires_at"]
            
            users.append(user_data)
        
        return {"users": users, "total": len(users)}


@router.get("/users/{user_id}")
async def get_user_detail(
    user_id: int,
    current_user: dict = Depends(require_role(ROLE_MODERATOR))
):
    """Детальная информация о пользователе"""
    with get_db() as conn:
        row = conn.execute("""
            SELECT id, username, email, uuid, role, created_at, last_login, is_active, banned_until
            FROM users WHERE id = ?
        """, (user_id,)).fetchone()
        
        if not row:
            raise HTTPException(404, "Пользователь не найден")
        
        # Модераторы не видят email обычных пользователей
        if current_user["role"] < ROLE_ELDER and row["role"] < ROLE_MODERATOR:
            email = None
        else:
            email = row["email"]
        
        # Получаем активную проходку
        pass_info = None
        if row["role"] >= ROLE_PASS_HOLDER or current_user["role"] >= ROLE_ELDER:
            pass_row = conn.execute("""
                SELECT p.code, p.expires_at, up.activated_at
                FROM user_passes up
                JOIN passes p ON up.pass_code = p.code
                WHERE up.user_id = ? AND (p.expires_at IS NULL OR p.expires_at > ?)
                LIMIT 1
            """, (user_id, time.time())).fetchone()
            
            if pass_row:
                pass_info = {
                    "code": pass_row["code"][:8] + "..." if current_user["role"] < ROLE_ELDER else pass_row["code"],
                    "expires_at": pass_row["expires_at"],
                    "activated_at": pass_row["activated_at"]
                }
        
        # Логи действий (только для Elder+)
        actions = []
        if current_user["role"] >= ROLE_ELDER:
            action_rows = conn.execute("""
                SELECT action, details, timestamp FROM audit_log
                WHERE user_id = ? ORDER BY timestamp DESC LIMIT 20
            """, (user_id,)).fetchall()
            actions = [dict(row) for row in action_rows]
        
        return {
            "id": row["id"],
            "username": row["username"],
            "email": email,
            "uuid": row["uuid"],
            "role": row["role"],
            "role_name": ROLE_NAMES.get(row["role"], "Неизвестно"),
            "created_at": row["created_at"],
            "last_login": row["last_login"],
            "is_active": row["is_active"],
            "banned_until": row["banned_until"],
            "has_pass": pass_info is not None,
            "pass_info": pass_info,
            "recent_actions": actions if current_user["role"] >= ROLE_ELDER else None
        }


@router.put("/users/{user_id}/role")
async def update_user_role(
    user_id: int,
    body: UpdateRoleRequest,
    current_user: dict = Depends(require_role(ROLE_ELDER)),
    request: Request = None
):
    """Изменение роли пользователя"""
    ip = request.client.host if request.client else "unknown"
    
    with get_db() as conn:
        target = conn.execute(
            "SELECT id, username, role FROM users WHERE id = ?",
            (user_id,)
        ).fetchone()
        
        if not target:
            raise HTTPException(404, "Пользователь не найден")
        
        # Проверки прав
        if target["role"] == ROLE_CREATOR and current_user["role"] != ROLE_CREATOR:
            raise HTTPException(403, "Нельзя изменить роль создателя")
        
        if target["role"] >= current_user["role"] and current_user["role"] != ROLE_CREATOR:
            raise HTTPException(403, "Нельзя изменять роль пользователя с равным или высшим уровнем")
        
        if body.role > current_user["role"] and current_user["role"] != ROLE_CREATOR:
            raise HTTPException(403, f"Нельзя назначить роль выше своей ({ROLE_NAMES[current_user['role']]})")
        
        # Elder не может создавать других Elder (только Creator)
        if body.role == ROLE_ELDER and current_user["role"] != ROLE_CREATOR:
            raise HTTPException(403, "Только создатель может назначать Elder Moderator")
        
        # Проверяем, нужно ли выдать/отозвать проходку
        old_role = target["role"]
        new_role = body.role
        
        conn.execute(
            "UPDATE users SET role = ? WHERE id = ?",
            (new_role, user_id)
        )
        
        # Управление проходками при изменении роли
        now = time.time()
        
        if new_role >= ROLE_PASS_HOLDER and old_role < ROLE_PASS_HOLDER:
            # Выдаем проходку если её нет
            existing = conn.execute("""
                SELECT 1 FROM user_passes up
                JOIN passes p ON up.pass_code = p.code
                WHERE up.user_id = ? AND (p.expires_at IS NULL OR p.expires_at > ?)
            """, (user_id, now)).fetchone()
            
            if not existing:
                # Создаем автоматическую проходку
                pass_code = f"AUTO_{secrets.token_hex(8).upper()}"
                conn.execute("""
                    INSERT INTO passes (code, owner, expires_at, max_uses, is_active)
                    VALUES (?, ?, NULL, 1, 1)
                """, (pass_code, target["username"]))
                
                conn.execute("""
                    INSERT INTO user_passes (user_id, pass_code, activated_at)
                    VALUES (?, ?, ?)
                """, (user_id, pass_code, now))
                
                logger.info("Auto-pass issued", user=target["username"], role=new_role)
        
        elif new_role < ROLE_PASS_HOLDER and old_role >= ROLE_PASS_HOLDER:
            # Отзываем проходку
            conn.execute("""
                UPDATE passes SET is_active = 0
                WHERE code IN (SELECT pass_code FROM user_passes WHERE user_id = ?)
            """, (user_id,))
            
            logger.info("Auto-pass revoked", user=target["username"])
        
        conn.commit()
        
        log_audit(
            current_user["id"], 
            "role_change", 
            f"Changed role of {target['username']} from {old_role} to {new_role}", 
            ip
        )
        
        logger.info("Role updated", admin=current_user["username"], target=target["username"], new_role=new_role)
        
        return {
            "success": True,
            "user_id": user_id,
            "username": target["username"],
            "old_role": old_role,
            "old_role_name": ROLE_NAMES.get(old_role, "Неизвестно"),
            "new_role": new_role,
            "new_role_name": ROLE_NAMES.get(new_role, "Неизвестно")
        }


@router.post("/pass/grant")
async def grant_pass(
    body: CreatePassDirectRequest,
    current_user: dict = Depends(require_role(ROLE_ELDER)),
    request: Request = None
):
    """Выдача проходки пользователю (Elder+ могут выдавать)"""
    ip = request.client.host if request.client else "unknown"
    
    # Проверяем право на прямую выдачу
    if current_user["role"] < ROLE_CREATOR and not has_permission(current_user["role"], Permissions.APPROVE_PASS):
        raise HTTPException(403, "Недостаточно прав для выдачи проходки")
    
    with get_db() as conn:
        target = conn.execute(
            "SELECT id, username, role FROM users WHERE username = ? COLLATE NOCASE",
            (body.username,)
        ).fetchone()
        
        if not target:
            raise HTTPException(404, f"Пользователь {body.username} не найден")
        
        # Проверяем, есть ли уже активная проходка
        existing = conn.execute("""
            SELECT p.code FROM user_passes up
            JOIN passes p ON up.pass_code = p.code
            WHERE up.user_id = ? AND (p.expires_at IS NULL OR p.expires_at > ?)
        """, (target["id"], time.time())).fetchone()
        
        if existing:
            raise HTTPException(409, f"У пользователя {body.username} уже есть активная проходка")
        
        # Создаем проходку
        pass_code = secrets.token_hex(12).upper()
        now = time.time()
        expires_at = now + (body.expires_days * 86400) if body.expires_days else None
        
        conn.execute("""
            INSERT INTO passes (code, owner, expires_at, max_uses, is_active)
            VALUES (?, ?, ?, ?, 1)
        """, (pass_code, target["username"], expires_at, body.max_uses))
        
        conn.execute("""
            INSERT INTO user_passes (user_id, pass_code, activated_at)
            VALUES (?, ?, ?)
        """, (target["id"], pass_code, now))
        
        # Обновляем роль если нужно
        if target["role"] < ROLE_PASS_HOLDER:
            conn.execute(
                "UPDATE users SET role = ? WHERE id = ?",
                (ROLE_PASS_HOLDER, target["id"])
            )
        
        conn.commit()
        
        log_audit(
            current_user["id"],
            "grant_pass",
            f"Granted pass to {target['username']} (expires: {body.expires_days}d, max_uses: {body.max_uses})",
            ip
        )
        
        logger.info("Pass granted", admin=current_user["username"], target=target["username"], code=pass_code)
        
        return {
            "success": True,
            "pass_code": pass_code,
            "username": target["username"],
            "expires_at": expires_at,
            "expires_days": body.expires_days,
            "max_uses": body.max_uses
        }


@router.delete("/pass/revoke/{username}")
async def revoke_pass(
    username: str,
    current_user: dict = Depends(require_role(ROLE_ELDER)),
    request: Request = None
):
    """Отзыв проходки у пользователя"""
    ip = request.client.host if request.client else "unknown"
    
    with get_db() as conn:
        target = conn.execute(
            "SELECT id, username, role FROM users WHERE username = ? COLLATE NOCASE",
            (username,)
        ).fetchone()
        
        if not target:
            raise HTTPException(404, f"Пользователь {username} не найден")
        
        # Отзываем проходку
        conn.execute("""
            UPDATE passes SET is_active = 0
            WHERE code IN (SELECT pass_code FROM user_passes WHERE user_id = ?)
        """, (target["id"],))
        
        # Понижаем роль если она была только из-за проходки
        if target["role"] == ROLE_PASS_HOLDER:
            conn.execute(
                "UPDATE users SET role = ? WHERE id = ?",
                (ROLE_USER, target["id"])
            )
        
        conn.commit()
        
        log_audit(current_user["id"], "revoke_pass", f"Revoked pass from {username}", ip)
        logger.info("Pass revoked", admin=current_user["username"], target=username)
        
        return {"success": True, "message": f"Проходка {username} отозвана"}


@router.post("/user/ban")
async def ban_user(
    body: BanUserRequest,
    current_user: dict = Depends(require_role(ROLE_ELDER)),
    request: Request = None
):
    """Бан пользователя (Elder+ могут банить)"""
    ip = request.client.host if request.client else "unknown"
    
    with get_db() as conn:
        target = conn.execute(
            "SELECT id, username, role FROM users WHERE id = ?",
            (body.user_id,)
        ).fetchone()
        
        if not target:
            raise HTTPException(404, "Пользователь не найден")
        
        # Нельзя забанить создателя
        if target["role"] == ROLE_CREATOR:
            raise HTTPException(403, "Нельзя забанить создателя")
        
        # Elder не может банить других Elder
        if target["role"] >= ROLE_ELDER and current_user["role"] != ROLE_CREATOR:
            raise HTTPException(403, "Недостаточно прав для бана этого пользователя")
        
        banned_until = time.time() + (body.days * 86400)
        
        conn.execute(
            "UPDATE users SET is_active = 0, banned_until = ? WHERE id = ?",
            (banned_until, target["id"])
        )
        
        # Отзываем проходку при бане
        conn.execute("""
            UPDATE passes SET is_active = 0
            WHERE code IN (SELECT pass_code FROM user_passes WHERE user_id = ?)
        """, (target["id"],))
        
        conn.commit()
        
        log_audit(
            current_user["id"],
            "ban_user",
            f"Banned {target['username']} for {body.days} days. Reason: {body.reason}",
            ip
        )
        
        logger.info("User banned", admin=current_user["username"], target=target["username"], days=body.days)
        
        return {
            "success": True,
            "username": target["username"],
            "banned_until": banned_until,
            "days": body.days
        }


@router.post("/user/unban/{user_id}")
async def unban_user(
    user_id: int,
    current_user: dict = Depends(require_role(ROLE_ELDER)),
    request: Request = None
):
    """Разбан пользователя"""
    ip = request.client.host if request.client else "unknown"
    
    with get_db() as conn:
        target = conn.execute(
            "SELECT id, username FROM users WHERE id = ?",
            (user_id,)
        ).fetchone()
        
        if not target:
            raise HTTPException(404, "Пользователь не найден")
        
        conn.execute(
            "UPDATE users SET is_active = 1, banned_until = NULL WHERE id = ?",
            (user_id,)
        )
        
        conn.commit()
        
        log_audit(current_user["id"], "unban_user", f"Unbanned {target['username']}", ip)
        logger.info("User unbanned", admin=current_user["username"], target=target["username"])
        
        return {"success": True, "username": target["username"]}


@router.get("/audit")
async def get_audit_log(
    current_user: dict = Depends(require_role(ROLE_ELDER)),
    limit: int = 50,
    offset: int = 0,
    user_id: Optional[int] = None
):
    """Просмотр логов аудита (только Elder+)"""
    with get_db() as conn:
        query = """
            SELECT al.*, u.username
            FROM audit_log al
            LEFT JOIN users u ON al.user_id = u.id
        """
        params = []
        
        if user_id:
            query += " WHERE al.user_id = ?"
            params.append(user_id)
        
        query += " ORDER BY al.timestamp DESC LIMIT ? OFFSET ?"
        params.extend([limit, offset])
        
        rows = conn.execute(query, params).fetchall()
        
        total = conn.execute("SELECT COUNT(*) as count FROM audit_log").fetchone()["count"]
        
        return {
            "logs": [dict(row) for row in rows],
            "total": total,
            "limit": limit,
            "offset": offset
        }


@router.get("/stats")
async def get_admin_stats(
    current_user: dict = Depends(require_role(ROLE_MODERATOR))
):
    """Статистика для админов"""
    with get_db() as conn:
        # Общая статистика
        total_users = conn.execute("SELECT COUNT(*) as count FROM users").fetchone()["count"]
        
        # Статистика по ролям
        role_stats = conn.execute("""
            SELECT role, COUNT(*) as count 
            FROM users 
            GROUP BY role 
            ORDER BY role DESC
        """).fetchall()
        
        # Активные проходки
        active_passes = conn.execute("""
            SELECT COUNT(*) as count FROM user_passes up
            JOIN passes p ON up.pass_code = p.code
            WHERE p.expires_at IS NULL OR p.expires_at > ?
        """, (time.time(),)).fetchone()["count"]
        
        # Забаненные пользователи
        banned_users = conn.execute("""
            SELECT COUNT(*) as count FROM users 
            WHERE is_active = 0 AND banned_until > ?
        """, (time.time(),)).fetchone()["count"]
        
        # Недавние регистрации (последние 7 дней)
        week_ago = time.time() - (7 * 86400)
        recent_registrations = conn.execute("""
            SELECT COUNT(*) as count FROM users WHERE created_at > ?
        """, (week_ago,)).fetchone()["count"]
        
        return {
            "total_users": total_users,
            "active_passes": active_passes,
            "banned_users": banned_users,
            "recent_registrations_7d": recent_registrations,
            "roles_distribution": [
                {"role": r["role"], "role_name": ROLE_NAMES.get(r["role"], "Неизвестно"), "count": r["count"]}
                for r in role_stats
            ],
            "my_info": {
                "role": current_user["role"],
                "role_name": ROLE_NAMES.get(current_user["role"], "Неизвестно"),
                "username": current_user["username"]
            }
        }


@router.get("/me")
async def get_my_info(current_user: dict = Depends(get_current_user)):
    """Информация о текущем пользователе с правами"""
    with get_db() as conn:
        row = conn.execute("""
            SELECT id, username, email, uuid, role, created_at, last_login
            FROM users WHERE id = ?
        """, (current_user["id"],)).fetchone()
        
        # Проверяем наличие активной проходки
        has_pass = False
        if row["role"] >= ROLE_PASS_HOLDER:
            pass_row = conn.execute("""
                SELECT 1 FROM user_passes up
                JOIN passes p ON up.pass_code = p.code
                WHERE up.user_id = ? AND (p.expires_at IS NULL OR p.expires_at > ?)
            """, (current_user["id"], time.time())).fetchone()
            has_pass = pass_row is not None
        
        permissions = list(ROLE_PERMISSIONS.get(row["role"], set()))
        
        return {
            "id": row["id"],
            "username": row["username"],
            "email": row["email"],
            "uuid": row["uuid"],
            "role": row["role"],
            "role_name": ROLE_NAMES.get(row["role"], "Неизвестно"),
            "created_at": row["created_at"],
            "last_login": row["last_login"],
            "has_pass": has_pass,
            "permissions": permissions
        }
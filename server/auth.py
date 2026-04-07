# auth.py
import base64
import json
import sqlite3
import hashlib
import hmac
import secrets
import time
from datetime import datetime
from pathlib import Path
from typing import Optional

import structlog
from fastapi import APIRouter, HTTPException, Request, Depends
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from pydantic import BaseModel, Field

logger = structlog.get_logger(__name__)

# ====================== КОНФИГ ======================
AUTH_DB = Path("data/auth.db")
AUTH_DB.parent.mkdir(exist_ok=True)

SECRET_KEY = Path("data/.secret_key")

ACCESS_TOKEN_EXPIRE_SECONDS = 24 * 3600      # 24 часа
REFRESH_TOKEN_EXPIRE_SECONDS = 30 * 86400    # 30 дней

# ====================== СЕКРЕТНЫЙ КЛЮЧ ======================
def _get_secret() -> bytes:
    if SECRET_KEY.exists():
        return SECRET_KEY.read_bytes()
    key = secrets.token_bytes(64)
    SECRET_KEY.write_bytes(key)
    SECRET_KEY.chmod(0o600)
    return key

_SECRET = _get_secret()

def create_jwt(payload: dict) -> str:
    import base64, json
    header = base64.urlsafe_b64encode(json.dumps({"alg": "HS256", "typ": "JWT"}).encode()).rstrip(b'=').decode()
    body = base64.urlsafe_b64encode(json.dumps(payload).encode()).rstrip(b'=').decode()
    msg = f"{header}.{body}".encode()
    sig = hmac.new(_SECRET, msg, hashlib.sha256).digest()
    return f"{header}.{body}.{base64.urlsafe_b64encode(sig).rstrip(b'=').decode()}"

def verify_jwt(token: str) -> Optional[dict]:
    try:
        parts = token.split(".")
        if len(parts) != 3:
            return None
        header, body, sig = parts
        msg = f"{header}.{body}".encode()
        expected = hmac.new(_SECRET, msg, hashlib.sha256).digest()
        if not hmac.compare_digest(base64.urlsafe_b64decode(sig + '==='[:3]), expected):
            return None
        payload = json.loads(base64.urlsafe_b64decode(body + '==='[:3]))
        if payload.get("exp", 0) < time.time():
            return None
        return payload
    except:
        return None

# ====================== БАЗА ДАННЫХ ======================
def get_db():
    conn = sqlite3.connect(str(AUTH_DB), check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db()
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY,
            username TEXT UNIQUE COLLATE NOCASE,
            password_hash TEXT NOT NULL,
            uuid TEXT UNIQUE NOT NULL,
            created_at REAL NOT NULL,
            last_login REAL
        );

        CREATE TABLE IF NOT EXISTS passes (                  -- НОВАЯ ТАБЛИЦА
            code TEXT PRIMARY KEY,                           -- ZERN-XXXXXX
            is_used BOOLEAN DEFAULT 0,
            activated_by INTEGER REFERENCES users(id),
            activated_at REAL,
            expires_at REAL,                                 -- NULL = без срока
            max_uses INTEGER DEFAULT 1,                      -- пока 1, можно больше
            uses INTEGER DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS user_passes (             -- Связь пользователь-пасс
            user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
            pass_code TEXT REFERENCES passes(code),
            activated_at REAL NOT NULL,
            PRIMARY KEY (user_id, pass_code)
        );
    """)
    conn.commit()
    conn.close()
    logger.info("Auth database initialized")

# ====================== ХЕЛПЕРЫ ======================
def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    hash_obj = hashlib.pbkdf2_hmac('sha256', password.encode(), salt.encode(), 300000)
    return f"{salt}${hash_obj.hex()}"

def verify_password(password: str, stored: str) -> bool:
    try:
        salt, stored_hash = stored.split('$')
        hash_obj = hashlib.pbkdf2_hmac('sha256', password.encode(), salt.encode(), 300000)
        return hmac.compare_digest(hash_obj.hex(), stored_hash)
    except:
        return False

def generate_uuid() -> str:
    return f"{secrets.token_hex(4)}-{secrets.token_hex(2)}-{secrets.token_hex(2)}-{secrets.token_hex(2)}-{secrets.token_hex(6)}"

# ====================== МОДЕЛИ ======================
class LoginRequest(BaseModel):
    username: str
    password: str

class RegisterRequest(BaseModel):
    username: str = Field(..., min_length=3, max_length=16, pattern=r"^[a-zA-Z0-9_]+$")
    password: str = Field(..., min_length=6, max_length=128)

class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    expires_in: int
    username: str
    uuid: str

# ====================== ROUTER ======================
router = APIRouter(prefix="/auth", tags=["auth"])
bearer = HTTPBearer(auto_error=False)

@router.post("/register", response_model=TokenResponse)
async def register(body: RegisterRequest, request: Request):
    conn = get_db()
    try:
        if conn.execute("SELECT 1 FROM users WHERE username = ? COLLATE NOCASE", (body.username,)).fetchone():
            raise HTTPException(409, "Имя пользователя уже занято")

        uuid = generate_uuid()
        pw_hash = hash_password(body.password)
        now = time.time()

        conn.execute(
            "INSERT INTO users (username, password_hash, uuid, created_at) VALUES (?, ?, ?, ?)",
            (body.username, pw_hash, uuid, now)
        )
        user_id = conn.lastrowid
        conn.commit()

        return _issue_tokens(conn, user_id, body.username, uuid)
    finally:
        conn.close()


@router.post("/login", response_model=TokenResponse)
async def login(body: LoginRequest, request: Request):
    conn = get_db()
    try:
        row = conn.execute(
            "SELECT id, username, password_hash, uuid FROM users WHERE username = ? COLLATE NOCASE",
            (body.username,)
        ).fetchone()

        if not row or not verify_password(body.password, row["password_hash"]):
            raise HTTPException(401, "Неверное имя пользователя или пароль")

        conn.execute("UPDATE users SET last_login = ? WHERE id = ?", (time.time(), row["id"]))
        conn.commit()

        return _issue_tokens(conn, row["id"], row["username"], row["uuid"])
    finally:
        conn.close()


def _issue_tokens(conn, user_id: int, username: str, uuid: str) -> TokenResponse:
    now = time.time()

    access_token = create_jwt({
        "sub": user_id,
        "username": username,
        "uuid": uuid,
        "type": "access",
        "exp": now + ACCESS_TOKEN_EXPIRE_SECONDS
    })

    refresh_token = create_jwt({
        "sub": user_id,
        "type": "refresh",
        "exp": now + REFRESH_TOKEN_EXPIRE_SECONDS
    })

    token_hash = hashlib.sha256(refresh_token.encode()).hexdigest()

    # Удаляем старые refresh-токены пользователя
    conn.execute("DELETE FROM refresh_tokens WHERE user_id = ?", (user_id,))
    conn.execute(
        "INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
        (user_id, token_hash, now + REFRESH_TOKEN_EXPIRE_SECONDS)
    )
    conn.commit()

    return TokenResponse(
        access_token=access_token,
        refresh_token=refresh_token,
        expires_in=ACCESS_TOKEN_EXPIRE_SECONDS,
        username=username,
        uuid=uuid
    )


# Добавь этот эндпоинт, он используется в tryRefresh()
@router.post("/refresh")
async def refresh(body: dict):
    refresh_token = body.get("refresh_token")
    if not refresh_token:
        raise HTTPException(400, "refresh_token обязателен")

    payload = verify_jwt(refresh_token)
    if not payload or payload.get("type") != "refresh":
        raise HTTPException(401, "Недействительный refresh token")

    conn = get_db()
    try:
        token_hash = hashlib.sha256(refresh_token.encode()).hexdigest()
        row = conn.execute(
            "SELECT user_id FROM refresh_tokens WHERE token_hash = ? AND expires_at > ?",
            (token_hash, time.time())
        ).fetchone()

        if not row:
            raise HTTPException(401, "Refresh token истёк или недействителен")

        user_row = conn.execute(
            "SELECT id, username, uuid FROM users WHERE id = ?", (row["user_id"],)
        ).fetchone()

        if not user_row:
            raise HTTPException(401, "Пользователь не найден")

        return _issue_tokens(conn, user_row["id"], user_row["username"], user_row["uuid"])
    finally:
        conn.close()

class ActivatePassRequest(BaseModel):
    pass_code: str = Field(..., min_length=8, max_length=20, pattern=r"^ZERN-[A-Z0-9]+$")

class PassInfo(BaseModel):
    code: str
    expires_at: Optional[float] = None
    is_active: bool

@router.post("/pass/activate")
async def activate_pass(body: ActivatePassRequest, credentials: HTTPAuthorizationCredentials = Depends(bearer)):
    token = credentials.credentials if credentials else None
    if not token:
        raise HTTPException(401, "Требуется авторизация")

    payload = verify_jwt(token)
    if not payload or payload.get("type") != "access":
        raise HTTPException(401, "Недействительный токен")

    user_id = payload["sub"]

    conn = get_db()
    try:
        # Проверяем существование и доступность пасса
        pass_row = conn.execute(
            "SELECT code, expires_at, uses, max_uses FROM passes WHERE code = ?",
            (body.pass_code.upper(),)
        ).fetchone()

        if not pass_row:
            raise HTTPException(404, "Проходка не найдена")

        if pass_row["expires_at"] and pass_row["expires_at"] < time.time():
            raise HTTPException(410, "Проходка истекла")

        if pass_row["uses"] >= pass_row["max_uses"]:
            raise HTTPException(410, "Проходка уже использована")

        # Проверяем, не активировал ли уже этот пользователь
        already = conn.execute(
            "SELECT 1 FROM user_passes WHERE user_id = ? AND pass_code = ?",
            (user_id, body.pass_code.upper())
        ).fetchone()

        if already:
            raise HTTPException(409, "Эта проходка уже активирована на вашем аккаунте")

        now = time.time()

        # Активируем
        conn.execute(
            "INSERT INTO user_passes (user_id, pass_code, activated_at) VALUES (?, ?, ?)",
            (user_id, body.pass_code.upper(), now)
        )

        # Увеличиваем счётчик использований
        conn.execute(
            "UPDATE passes SET uses = uses + 1, activated_by = ?, activated_at = ? WHERE code = ?",
            (user_id, now, body.pass_code.upper())
        )

        conn.commit()

        logger.info("Pass activated", user_id=user_id, pass_code=body.pass_code)
        return {"success": True, "message": "Проходка успешно активирована!"}

    finally:
        conn.close()


@router.get("/pass/my")
async def get_my_passes(credentials: HTTPAuthorizationCredentials = Depends(bearer)):
    token = credentials.credentials if credentials else None
    if not token:
        raise HTTPException(401, "Требуется авторизация")

    payload = verify_jwt(token)
    if not payload:
        raise HTTPException(401, "Недействительный токен")

    user_id = payload["sub"]

    conn = get_db()
    try:
        rows = conn.execute("""
            SELECT p.code, p.expires_at, up.activated_at
            FROM user_passes up
            JOIN passes p ON up.pass_code = p.code
            WHERE up.user_id = ?
        """, (user_id,)).fetchall()

        passes = []
        now = time.time()
        for row in rows:
            expires = row["expires_at"]
            is_active = expires is None or expires > now
            passes.append({
                "code": row["code"],
                "activated_at": row["activated_at"],
                "expires_at": expires,
                "is_active": is_active
            })

        return {"passes": passes}
    finally:
        conn.close()
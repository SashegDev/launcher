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
from contextlib import contextmanager

import structlog
from fastapi import APIRouter, HTTPException, Request, Depends, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from pydantic import BaseModel, Field, field_validator
import re

logger = structlog.get_logger(__name__)

# ====================== КОНФИГ ======================
AUTH_DB = Path("data/auth.db")
AUTH_DB.parent.mkdir(exist_ok=True)
SECRET_KEY = Path("data/.secret_key")
RATE_LIMIT_DB = Path("data/rate_limit.db")

# Токены
ACCESS_TOKEN_EXPIRE_MINUTES = 60 * 24  # 24 часа
REFRESH_TOKEN_EXPIRE_DAYS = 30

# Лимиты
MAX_LOGIN_ATTEMPTS = 5
LOGIN_BLOCK_MINUTES = 15

# ====================== СЕКРЕТНЫЙ КЛЮЧ ======================
def _get_secret() -> bytes:
    """Безопасное получение/создание секретного ключа"""
    if SECRET_KEY.exists():
        return SECRET_KEY.read_bytes()
    key = secrets.token_bytes(64)
    SECRET_KEY.write_bytes(key)
    SECRET_KEY.chmod(0o600)
    return key

_SECRET = _get_secret()

# ====================== JWT ФУНКЦИИ ======================
def create_jwt(payload: dict, expires_in: int = None) -> str:
    """Создание JWT токена"""
    if expires_in is None:
        expires_in = ACCESS_TOKEN_EXPIRE_MINUTES * 60
    
    payload = payload.copy()
    payload["exp"] = time.time() + expires_in
    payload["iat"] = time.time()
    payload["jti"] = secrets.token_hex(16)
    
    header = base64.urlsafe_b64encode(
        json.dumps({"alg": "HS256", "typ": "JWT"}).encode()
    ).rstrip(b'=').decode()
    
    body = base64.urlsafe_b64encode(
        json.dumps(payload).encode()
    ).rstrip(b'=').decode()
    
    msg = f"{header}.{body}".encode()
    sig = hmac.new(_SECRET, msg, hashlib.sha256).digest()
    
    return f"{header}.{body}.{base64.urlsafe_b64encode(sig).rstrip(b'=').decode()}"

def verify_jwt(token: str) -> Optional[dict]:
    """Верификация JWT токена"""
    try:
        parts = token.split(".")
        if len(parts) != 3:
            return None
        
        header, body, sig = parts
        
        sig_padded = sig + '=' * (4 - len(sig) % 4)
        expected_sig = base64.urlsafe_b64decode(sig_padded)
        
        msg = f"{header}.{body}".encode()
        if not hmac.compare_digest(
            hmac.new(_SECRET, msg, hashlib.sha256).digest(),
            expected_sig
        ):
            return None
        
        body_padded = body + '=' * (4 - len(body) % 4)
        payload = json.loads(base64.urlsafe_b64decode(body_padded))
        
        if payload.get("exp", 0) < time.time():
            return None
        
        return payload
    except Exception:
        return None

# ====================== БАЗА ДАННЫХ ======================
@contextmanager
def get_db():
    """Контекстный менеджер для БД"""
    conn = sqlite3.connect(str(AUTH_DB), check_same_thread=False, timeout=10)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

def init_rate_limit_db():
    """Инициализация БД для rate limiting"""
    conn = sqlite3.connect(str(RATE_LIMIT_DB))
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS login_attempts (
            ip TEXT PRIMARY KEY,
            attempts INTEGER DEFAULT 1,
            first_attempt REAL NOT NULL,
            last_attempt REAL NOT NULL,
            blocked_until REAL
        );
    """)
    conn.commit()
    conn.close()

def init_db():
    """Инициализация основной БД"""
    with get_db() as conn:
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE COLLATE NOCASE,
                password_hash TEXT NOT NULL,
                uuid TEXT UNIQUE NOT NULL,
                role INTEGER DEFAULT 0,
                created_at REAL NOT NULL,
                last_login REAL,
                is_active BOOLEAN DEFAULT 1,
                banned_until REAL
            );

            CREATE TABLE IF NOT EXISTS refresh_tokens (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                token_hash TEXT NOT NULL,
                jti TEXT NOT NULL,
                expires_at REAL NOT NULL,
                revoked BOOLEAN DEFAULT 0,
                created_at REAL NOT NULL
            );

            CREATE TABLE IF NOT EXISTS user_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                session_token TEXT UNIQUE NOT NULL,
                ip_address TEXT,
                user_agent TEXT,
                created_at REAL NOT NULL,
                expires_at REAL NOT NULL,
                is_active BOOLEAN DEFAULT 1
            );

            CREATE TABLE IF NOT EXISTS passes (
                code TEXT PRIMARY KEY,
                owner TEXT,
                is_active BOOLEAN DEFAULT 1,
                activated_by INTEGER REFERENCES users(id),
                activated_at REAL,
                expires_at REAL,
                max_uses INTEGER DEFAULT 1,
                uses INTEGER DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS user_passes (
                user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
                pass_code TEXT REFERENCES passes(code),
                activated_at REAL NOT NULL,
                PRIMARY KEY (user_id, pass_code)
            );

            CREATE TABLE IF NOT EXISTS pass_requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                requester_id INTEGER NOT NULL REFERENCES users(id),
                target_username TEXT NOT NULL,
                reason TEXT,
                status TEXT DEFAULT 'pending',
                decision_reason TEXT,
                created_at REAL NOT NULL,
                reviewed_by INTEGER REFERENCES users(id),
                reviewed_at REAL
            );

            CREATE TABLE IF NOT EXISTS audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER REFERENCES users(id),
                action TEXT NOT NULL,
                details TEXT,
                ip_address TEXT,
                timestamp REAL NOT NULL
            );
            
            CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_log(user_id);
            CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp);
            CREATE INDEX IF NOT EXISTS idx_sessions_user ON user_sessions(user_id);
            CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_tokens(user_id);
        """)
        
        # Добавляем колонку role если её нет
        cursor = conn.execute("PRAGMA table_info(users)")
        columns = [col[1] for col in cursor.fetchall()]
        
        if "role" not in columns:
            conn.execute("ALTER TABLE users ADD COLUMN role INTEGER DEFAULT 0")
            logger.info("Added role column to users table")
    
    init_rate_limit_db()
    logger.info("Auth database initialized")

# ====================== ХЕЛПЕРЫ ======================
def hash_password(password: str) -> str:
    """Хэширование пароля"""
    salt = secrets.token_hex(32)
    hash_obj = hashlib.pbkdf2_hmac(
        'sha256',
        password.encode('utf-8'),
        salt.encode('utf-8'),
        300000
    )
    return f"{salt}${hash_obj.hex()}"

def verify_password(password: str, stored: str) -> bool:
    """Верификация пароля"""
    try:
        salt, stored_hash = stored.split('$')
        hash_obj = hashlib.pbkdf2_hmac(
            'sha256',
            password.encode('utf-8'),
            salt.encode('utf-8'),
            300000
        )
        return hmac.compare_digest(hash_obj.hex(), stored_hash)
    except Exception:
        return False

def generate_uuid() -> str:
    """Генерация UUID"""
    return f"{secrets.token_hex(4)}-{secrets.token_hex(2)}-{secrets.token_hex(2)}-{secrets.token_hex(2)}-{secrets.token_hex(6)}"

def check_rate_limit(ip: str) -> tuple[bool, Optional[int]]:
    """Проверка rate limiting"""
    conn = sqlite3.connect(str(RATE_LIMIT_DB))
    now = time.time()
    
    try:
        row = conn.execute(
            "SELECT attempts, blocked_until FROM login_attempts WHERE ip = ?",
            (ip,)
        ).fetchone()
        
        if row:
            blocked_until = row[1]
            if blocked_until and blocked_until > now:
                return False, int(blocked_until - now)
            
            if row[0] >= MAX_LOGIN_ATTEMPTS:
                blocked_until = now + (LOGIN_BLOCK_MINUTES * 60)
                conn.execute(
                    "UPDATE login_attempts SET blocked_until = ? WHERE ip = ?",
                    (blocked_until, ip)
                )
                conn.commit()
                return False, LOGIN_BLOCK_MINUTES * 60
        return True, None
    finally:
        conn.close()

def record_login_attempt(ip: str, success: bool):
    """Запись попытки входа"""
    conn = sqlite3.connect(str(RATE_LIMIT_DB))
    now = time.time()
    
    try:
        if success:
            conn.execute("DELETE FROM login_attempts WHERE ip = ?", (ip,))
        else:
            conn.execute("""
                INSERT INTO login_attempts (ip, attempts, first_attempt, last_attempt)
                VALUES (?, 1, ?, ?)
                ON CONFLICT(ip) DO UPDATE SET
                    attempts = attempts + 1,
                    last_attempt = ?
            """, (ip, now, now, now))
        conn.commit()
    finally:
        conn.close()

def log_audit(user_id: int, action: str, details: str, ip_address: str):
    """Логирование действий"""
    with get_db() as conn:
        conn.execute(
            "INSERT INTO audit_log (user_id, action, details, ip_address, timestamp) VALUES (?, ?, ?, ?, ?)",
            (user_id, action, details, ip_address, time.time())
        )

# ====================== МОДЕЛИ ======================
class LoginRequest(BaseModel):
    username: str = Field(..., min_length=3, max_length=32)
    password: str = Field(..., min_length=6, max_length=128)
    
    @field_validator('username')
    def validate_username(cls, v):
        if not re.match(r'^[a-zA-Z0-9_]+$', v):
            raise ValueError('Имя пользователя может содержать только буквы, цифры и подчеркивания')
        return v.lower()

class RegisterRequest(BaseModel):
    username: str = Field(..., min_length=3, max_length=32)
    password: str = Field(..., min_length=6, max_length=128)
    
    @field_validator('username')
    def validate_username(cls, v):
        if not re.match(r'^[a-zA-Z0-9_]+$', v):
            raise ValueError('Имя пользователя может содержать только буквы, цифры и подчеркивания')
        return v.lower()

class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    expires_in: int
    token_type: str = "bearer"
    username: str
    uuid: str
    role: int
    role_name: str

# ====================== DEPENDENCIES ======================
router = APIRouter(prefix="/auth", tags=["auth"])
bearer = HTTPBearer(auto_error=False)

async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(bearer),
    request: Request = None
) -> dict:
    """Получение текущего пользователя"""
    if not credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Не авторизован",
            headers={"WWW-Authenticate": "Bearer"},
        )
    
    payload = verify_jwt(credentials.credentials)
    if not payload or payload.get("type") != "access":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Недействительный токен"
        )
    
    with get_db() as conn:
        user = conn.execute(
            "SELECT id, username, uuid, role, is_active, banned_until FROM users WHERE id = ?",
            (payload["sub"],)
        ).fetchone()
        
        if not user:
            raise HTTPException(401, "Пользователь не найден")
        
        if not user["is_active"]:
            raise HTTPException(403, "Аккаунт деактивирован")
        
        if user["banned_until"] and user["banned_until"] > time.time():
            raise HTTPException(403, "Аккаунт забанен")
        
        return {
            "id": user["id"],
            "username": user["username"],
            "uuid": user["uuid"],
            "role": user["role"]
        }

def require_role(min_role: int):
    """Декоратор для проверки роли"""
    async def dependency(current_user: dict = Depends(get_current_user)):
        if current_user["role"] < min_role:
            from roles import ROLE_NAMES
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Требуется роль {ROLE_NAMES.get(min_role, 'неизвестная')}"
            )
        return current_user
    return dependency

# ====================== ЭНДПОИНТЫ ======================
@router.post("/register", response_model=TokenResponse)
async def register(body: RegisterRequest, request: Request):
    """Регистрация нового пользователя"""
    ip = request.client.host if request.client else "unknown"
    
    allowed, wait = check_rate_limit(ip)
    if not allowed:
        raise HTTPException(429, f"Слишком много попыток. Подождите {wait} секунд")
    
    with get_db() as conn:
        existing = conn.execute(
            "SELECT username FROM users WHERE username = ?",
            (body.username,)
        ).fetchone()
        
        if existing:
            raise HTTPException(409, "Пользователь с таким именем уже существует")
        
        uuid = generate_uuid()
        pw_hash = hash_password(body.password)
        now = time.time()
        
        cursor = conn.execute(
            """INSERT INTO users (username, password_hash, uuid, created_at, role) 
               VALUES (?, ?, ?, ?, ?)""",
            (body.username, pw_hash, uuid, now, 0)  # role 0 = обычный пользователь
        )
        
        user_id = cursor.lastrowid
        
        # Создаем сессию
        session_token = secrets.token_urlsafe(32)
        conn.execute(
            "INSERT INTO user_sessions (user_id, session_token, ip_address, user_agent, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
            (user_id, session_token, ip, request.headers.get("user-agent", ""), now, now + (ACCESS_TOKEN_EXPIRE_MINUTES * 60))
        )
        
        # Токены
        access_token = create_jwt({
            "sub": user_id,
            "username": body.username,
            "uuid": uuid,
            "role": 0,
            "type": "access",
            "jti": session_token
        })
        
        refresh_token = create_jwt({
            "sub": user_id,
            "type": "refresh",
            "jti": secrets.token_hex(16)
        }, expires_in=REFRESH_TOKEN_EXPIRE_DAYS * 86400)
        
        refresh_hash = hashlib.sha256(refresh_token.encode()).hexdigest()
        conn.execute(
            "INSERT INTO refresh_tokens (user_id, token_hash, jti, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
            (user_id, refresh_hash, secrets.token_hex(16), now + (REFRESH_TOKEN_EXPIRE_DAYS * 86400), now)
        )
        
        log_audit(user_id, "register", f"User registered from {ip}", ip)
        logger.info("User registered", username=body.username, user_id=user_id, ip=ip)
        
        from roles import ROLE_NAMES
        return TokenResponse(
            access_token=access_token,
            refresh_token=refresh_token,
            expires_in=ACCESS_TOKEN_EXPIRE_MINUTES * 60,
            username=body.username,
            uuid=uuid,
            role=0,
            role_name=ROLE_NAMES[0]
        )

@router.post("/login", response_model=TokenResponse)
async def login(body: LoginRequest, request: Request):
    """Вход в систему"""
    ip = request.client.host if request.client else "unknown"
    
    allowed, wait = check_rate_limit(ip)
    if not allowed:
        raise HTTPException(429, f"Слишком много попыток. Подождите {wait} секунд")
    
    with get_db() as conn:
        user = conn.execute(
            "SELECT id, username, uuid, password_hash, role, is_active, banned_until FROM users WHERE username = ?",
            (body.username,)
        ).fetchone()
        
        if not user or not verify_password(body.password, user["password_hash"]):
            record_login_attempt(ip, False)
            log_audit(0, "login_failed", f"Failed login for {body.username} from {ip}", ip)
            raise HTTPException(401, "Неверное имя пользователя или пароль")
        
        if not user["is_active"]:
            raise HTTPException(403, "Аккаунт деактивирован")
        
        if user["banned_until"] and user["banned_until"] > time.time():
            raise HTTPException(403, "Аккаунт забанен")
        
        record_login_attempt(ip, True)
        
        now = time.time()
        conn.execute(
            "UPDATE users SET last_login = ? WHERE id = ?",
            (now, user["id"])
        )
        
        # Создаем сессию
        session_token = secrets.token_urlsafe(32)
        conn.execute(
            "INSERT INTO user_sessions (user_id, session_token, ip_address, user_agent, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
            (user["id"], session_token, ip, request.headers.get("user-agent", ""), now, now + (ACCESS_TOKEN_EXPIRE_MINUTES * 60))
        )
        
        access_token = create_jwt({
            "sub": user["id"],
            "username": user["username"],
            "uuid": user["uuid"],
            "role": user["role"],
            "type": "access",
            "jti": session_token
        })
        
        refresh_token = create_jwt({
            "sub": user["id"],
            "type": "refresh",
            "jti": secrets.token_hex(16)
        }, expires_in=REFRESH_TOKEN_EXPIRE_DAYS * 86400)
        
        refresh_hash = hashlib.sha256(refresh_token.encode()).hexdigest()
        conn.execute(
            "INSERT INTO refresh_tokens (user_id, token_hash, jti, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
            (user["id"], refresh_hash, secrets.token_hex(16), now + (REFRESH_TOKEN_EXPIRE_DAYS * 86400), now)
        )
        
        log_audit(user["id"], "login", f"User logged in from {ip}", ip)
        logger.info("User logged in", username=user["username"], user_id=user["id"], ip=ip)
        
        from roles import ROLE_NAMES
        return TokenResponse(
            access_token=access_token,
            refresh_token=refresh_token,
            expires_in=ACCESS_TOKEN_EXPIRE_MINUTES * 60,
            username=user["username"],
            uuid=user["uuid"],
            role=user["role"],
            role_name=ROLE_NAMES.get(user["role"], "Неизвестно")
        )

@router.post("/logout")
async def logout(current_user: dict = Depends(get_current_user), request: Request = None):
    """Выход из системы"""
    ip = request.client.host if request.client else "unknown"
    
    with get_db() as conn:
        conn.execute(
            "UPDATE user_sessions SET is_active = 0 WHERE user_id = ?",
            (current_user["id"],)
        )
        conn.execute(
            "UPDATE refresh_tokens SET revoked = 1 WHERE user_id = ?",
            (current_user["id"],)
        )
        
        log_audit(current_user["id"], "logout", f"User logged out from {ip}", ip)
    
    logger.info("User logged out", user_id=current_user["id"], ip=ip)
    return {"success": True}

@router.post("/refresh")
async def refresh(body: dict, request: Request):
    """Обновление access токена"""
    refresh_token = body.get("refresh_token")
    if not refresh_token:
        raise HTTPException(400, "refresh_token обязателен")
    
    payload = verify_jwt(refresh_token)
    if not payload or payload.get("type") != "refresh":
        raise HTTPException(401, "Недействительный refresh token")
    
    ip = request.client.host if request.client else "unknown"
    
    with get_db() as conn:
        token_hash = hashlib.sha256(refresh_token.encode()).hexdigest()
        token_row = conn.execute(
            "SELECT user_id, revoked FROM refresh_tokens WHERE token_hash = ? AND expires_at > ?",
            (token_hash, time.time())
        ).fetchone()
        
        if not token_row or token_row["revoked"]:
            raise HTTPException(401, "Refresh token истёк или недействителен")
        
        user = conn.execute(
            "SELECT id, username, uuid, role FROM users WHERE id = ? AND is_active = 1",
            (token_row["user_id"],)
        ).fetchone()
        
        if not user:
            raise HTTPException(401, "Пользователь не найден или заблокирован")
        
        now = time.time()
        session_token = secrets.token_urlsafe(32)
        conn.execute(
            "INSERT INTO user_sessions (user_id, session_token, ip_address, user_agent, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
            (user["id"], session_token, ip, request.headers.get("user-agent", ""), now, now + (ACCESS_TOKEN_EXPIRE_MINUTES * 60))
        )
        
        new_access_token = create_jwt({
            "sub": user["id"],
            "username": user["username"],
            "uuid": user["uuid"],
            "role": user["role"],
            "type": "access",
            "jti": session_token
        })
        
        log_audit(user["id"], "refresh_token", f"Token refreshed from {ip}", ip)
        
        return {
            "passes": passes,
            "has_active": any(p["is_active"] for p in passes)
        }
    finally:
        conn.close()

@router.post("/validate")
async def validate_token(request: Request, credentials: HTTPAuthorizationCredentials = Depends(bearer)):
    """Validate token endpoint for Minecraft server"""
    if not credentials:
        raise HTTPException(401, "Требуется авторизация")
    
    payload = verify_jwt(credentials.credentials)
    if not payload or payload.get("type") != "access":
        raise HTTPException(401, "Недействительный токен")
    
    try:
        body = await request.json()
        username = body.get("username")
        uuid = body.get("uuid")
        
        # Verify that token belongs to this user
        if payload.get("username") != username or payload.get("uuid") != uuid:
            raise HTTPException(403, "Token does not match user")
        
        return {"valid": True, "username": username, "uuid": uuid}
        
    except Exception as e:
        logger.error(f"Token validation error: {e}")
        raise HTTPException(400, "Invalid request")

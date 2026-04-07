import json
from pathlib import Path
from datetime import datetime
import structlog

logger = structlog.get_logger(__name__)

PASSES_FILE = Path("data/passes.json")

def load_passes():
    if not PASSES_FILE.exists():
        PASSES_FILE.parent.mkdir(exist_ok=True)
        default = {"passes": {}}
        PASSES_FILE.write_text(json.dumps(default, indent=2, ensure_ascii=False))
        return default
    try:
        return json.loads(PASSES_FILE.read_text(encoding="utf-8"))
    except:
        return {"passes": {}}

def save_passes(data):
    PASSES_FILE.write_text(json.dumps(data, indent=2, ensure_ascii=False))

def activate_pass(pass_code: str, username: str, user_id: int) -> dict:
    data = load_passes()
    pass_code = pass_code.upper().strip()

    if pass_code not in data["passes"]:
        return {"success": False, "error": "Проходка не найдена"}

    p = data["passes"][pass_code]

    if not p.get("is_active", True):
        return {"success": False, "error": "Проходка деактивирована"}

    if p.get("expires_at") and p.get("expires_at") < datetime.now().timestamp():
        return {"success": False, "error": "Проходка истекла"}

    if p.get("owner") is not None:
        if p.get("owner") != username:
            return {"success": False, "error": "Проходка уже активирована другим пользователем"}
        return {"success": True, "message": "Проходка уже активирована на вашем аккаунте"}

    # Активация
    now = datetime.now().timestamp()
    p["owner"] = username
    p["activated_at"] = now
    p["uses"] = p.get("uses", 0) + 1

    save_passes(data)

    logger.info("Pass activated", pass_code=pass_code, username=username)
    return {"success": True, "message": "Проходка успешно активирована!"}

def has_active_pass(username: str) -> bool:
    data = load_passes()
    for p in data["passes"].values():
        if p.get("owner") == username:
            if p.get("expires_at") and p.get("expires_at") < datetime.now().timestamp():
                continue
            if p.get("is_active", True):
                return True
    return False

def get_user_passes(username: str) -> list:
    data = load_passes()
    result = []
    now = datetime.now().timestamp()
    for p in data["passes"].values():
        if p.get("owner") == username:
            result.append({
                "code": p["code"],
                "activated_at": p.get("activated_at"),
                "expires_at": p.get("expires_at"),
                "is_active": p.get("is_active", True) and (not p.get("expires_at") or p.get("expires_at") > now)
            })
    return result
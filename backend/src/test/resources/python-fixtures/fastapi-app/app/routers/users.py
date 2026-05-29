from fastapi import APIRouter
import httpx

router = APIRouter()

@router.get("/users")
def list_users():
    return []

@router.post("/users")
def create_user(name: str):
    httpx.post("http://auth-service/validate", json={"name": name})
    return {"name": name}

@router.get("/users/{user_id}")
def get_user(user_id: int):
    return {"id": user_id}

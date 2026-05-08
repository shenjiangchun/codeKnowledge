from fastapi import APIRouter

router = APIRouter()

class ItemController:

    @router.get("/items")
    def list_items(self):
        return []

    @router.post("/items")
    def create_item(self, name: str):
        return {"name": name}

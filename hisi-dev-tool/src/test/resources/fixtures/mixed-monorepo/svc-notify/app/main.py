from fastapi import FastAPI
from celery import Celery

app = FastAPI()
celery_app = Celery("svc-notify", broker="redis://localhost:6379/0")


@app.get("/api/notifications/{user_id}")
async def get_notifications(user_id: int):
    return {"user_id": user_id, "notifications": []}


@celery_app.task
def send_notification(user_id: int, message: str):
    """Send a notification to the given user."""
    pass

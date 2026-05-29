from fastapi import FastAPI
import requests
from app.routers import users, items

app = FastAPI()

app.include_router(users.router)
app.include_router(items.router)

@app.get("/health")
def health_check():
    return {"status": "ok"}

@app.get("/external")
def call_external():
    response = requests.get("http://other-service/api/data")
    return response.json()

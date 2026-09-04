from fastapi import FastAPI

from app.routers import notifications, preferences

app = FastAPI(title="Notification Service", version="3.0.1")
app.include_router(notifications.router)
app.include_router(preferences.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "notification-service"}

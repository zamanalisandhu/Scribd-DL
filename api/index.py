import sys
import os

# Add root directory to sys.path so app.py can be imported
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import app as fastapi_app

# Vercel ASGI adapter to correctly map incoming paths and headers
async def app(scope, receive, send):
    if scope["type"] == "http":
        headers = dict(scope.get("headers", []))
        
        # Check if Vercel forwarded the original URL
        orig_uri = headers.get(b"x-forwarded-uri", b"").decode("utf-8", "ignore")
        if not orig_uri:
            orig_uri = headers.get(b"x-matched-path", b"").decode("utf-8", "ignore")
            
        current_path = scope.get("path", "")
        
        # If Vercel rewrote the path to /api/index.py or /api/index, restore original URI
        if current_path in ["/api/index.py", "/api/index", "/api/index/"]:
            if orig_uri:
                # Split off query string if present
                clean_path = orig_uri.split("?")[0]
                scope["path"] = clean_path
                scope["raw_path"] = clean_path.encode("utf-8")
        elif current_path.startswith("/api/index.py/"):
            clean_path = current_path[len("/api/index.py"):]
            scope["path"] = clean_path
            scope["raw_path"] = clean_path.encode("utf-8")

    await fastapi_app(scope, receive, send)

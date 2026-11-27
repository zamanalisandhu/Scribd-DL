# ScribdDL Pro 🚀
### High-Performance Universal Scribd Document Downloader & Vector Assembler

A production-ready, high-speed downloader engine and modern front-end for Scribd documents. It automatically bypasses preview limits and delivers 100% complete, original documents in authentic vector quality without distortion.

---

## 🌟 Key Capabilities

- **Universal Multi-Format Compatibility**:
  - **Vector Documents / Reports**: Full extraction of dynamic JSONP page streams (pages 4..N) with automatic injection and original CSS styling.
  - **Embedded Web Fonts**: Auto-resolves and binds Scribd's obfuscated `@font-face` `.woff2` files to prevent scrambled text glyphs.
  - **Scanned Photo Books / Hand-drawn Notes**: Ultra-fast 24-threaded parallel image stitcher with zero image quality loss.
  - **Single-Page Ultra-Tall Captures**: Automatic dimension detection and smart A4 slicing.
- **Official Publisher PDF Fallback**: Built-in support for session cookies (`SCRIBD_COOKIE` environment variable or query param) to download direct Amazon S3 publisher PDFs when available.
- **Smart Cache Validation**: Built-in sanity checks that automatically detect and purge incomplete or blank PDFs, ensuring only 100% verified files are served.
- **Multiple Output Formats**: Supports **PDF**, **DOCX**, and clean **TXT**.
- **Modern Glassmorphic UI**: Fast, responsive web UI with light/dark theme, progress bar, real-time metadata display, and one-click download.

---

## 🛠️ Tech Stack & Requirements

- **Python 3.10+**
- **FastAPI & Uvicorn** (High-throughput async backend)
- **Headless Chromium / Chrome** (Pixel-perfect vector-to-PDF rendering)
- **Pillow & PyPDF** (High-speed image processing & PDF validation)

---

## 🚀 Quick Start (Local Development)

### 1. Clone the repository
```bash
git clone https://github.com/zamanalisandhu/Scribd-DL.git
cd Scribd-DL
```

### 2. Install dependencies
```bash
pip install -r requirements.txt
```

### 3. Run the backend server
```bash
python app.py
```
The server will start at `http://localhost:8080`.

### 4. Open the frontend
Open `index.html` in your web browser or visit `http://localhost:8080`.

---

## ☁️ Deployment

### Render (Docker Web Service)
This repository includes a pre-configured `Dockerfile` and `render.yaml` with pre-installed Chromium and TTF font packages.
1. Connect your GitHub repository to Render.
2. Select **Web Service** -> **Docker**.
3. (Optional) Add `SCRIBD_COOKIE` to Environment Variables if you have a Scribd account for direct S3 downloads.

### Vercel
Configured via `vercel.json` and `api/index.py` for serverless API routing.

---

## 📄 License
This project is for educational and personal research purposes only. Respect document copyrights and intellectual property.

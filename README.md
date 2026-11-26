# ScribdDL - Front Page

A modern, responsive, and high-conversion front page for **Scribd Downloader (ScribdDL)**.

## ✨ Features

- **Modern Glassmorphic UI**: Built with Tailwind CSS, Lucide icons, and modern typography (Plus Jakarta Sans & Inter).
- **Dark / Light Theme Toggle**: Seamless transition with local storage persistence and system preference detection.
- **Interactive URL Input**:
  - One-click clipboard **Paste** button.
  - Quick **Clear** button.
  - Real-time format picker (`PDF`, `DOCX`, `TXT`).
  - Quick test samples to demo the conversion pipeline instantly.
  - URL format validation (supports `scribd.com/document/...` and `scribd.com/doc/...`).
- **Live Conversion Simulation**:
  - Multi-stage animated progress bar (`Resolving metadata` -> `Bypassing viewer lock` -> `Rendering high-res pages` -> `Packaging file`).
  - Document metadata card with cover mockup, extracted title, author, page count, and file size.
  - Real working file download trigger on completion.
- **Value Sections**:
  - Key performance & reliability metrics banner (1.8M+ downloads, 99.8% success rate, 3.2s avg speed).
  - 3-Step **"How It Works"** guide.
  - 6-point **Feature Highlights** grid.
  - Supported document formats tag cloud.
  - Collapsible **FAQ Accordion**.
  - Educational fair-use disclaimer & footer.

## 🚀 How to Run

### Direct Open
Double click `index.html` or open it with any web browser (Chrome, Edge, Firefox, Safari).

### Or via Local Server (Python)
```bash
python -m http.server 8000
```
Then visit: `http://localhost:8000`

import os
import io
import re
import json
import gzip
import shutil
import subprocess
import urllib.request
import urllib.parse
import concurrent.futures
from typing import Optional
from PIL import Image, ImageDraw
import pypdf

from fastapi import FastAPI, Query, HTTPException, Response
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.middleware.cors import CORSMiddleware

DIRECTORY = os.path.dirname(os.path.abspath(__file__))
CACHE_DIR = os.path.join(DIRECTORY, "cache")
os.makedirs(CACHE_DIR, exist_ok=True)

app = FastAPI(
    title="ScribdDL Pro API",
    description="High-Speed, Non-Corrupting Scribd Document Downloader & Vector Assembler API",
    version="2.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# In-memory fast cache for document metadata and live download progress
META_CACHE = {}
PROGRESS_TRACKER = {}

def find_headless_browser() -> Optional[str]:
    candidates = [
        # Linux / Docker paths
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        # Windows paths
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    for name in ["chromium", "chromium-browser", "google-chrome", "chrome", "msedge", "chrome.exe", "msedge.exe"]:
        p = shutil.which(name)
        if p:
            return p
    return None

def generate_pdf_via_headless(doc_id: str, expected_page_count: int = 1, cookie_str: Optional[str] = None) -> Optional[bytes]:
    browser_exe = find_headless_browser()
    if not browser_exe:
        return None

    embed_url = f"https://www.scribd.com/embeds/{doc_id}/content"
    req_headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
    }
    if cookie_str:
        req_headers['Cookie'] = cookie_str

    try:
        req = urllib.request.Request(embed_url, headers=req_headers)
        with urllib.request.urlopen(req, timeout=12) as resp:
            html = resp.read().decode('utf-8', errors='ignore')
    except Exception as e:
        print(f"[WARN] Failed to fetch embed content for {doc_id}: {e}")
        return None

    if 'outer_page' not in html and 'document_scroller' not in html:
        return None

    html = html.replace('http://html.scribd.com', 'https://html.scribd.com')
    html = html.replace('http://html.scribdassets.com', 'https://html.scribdassets.com')
    html = re.sub(r'orig=["\'](https?://[^"\']+)["\']', r'src="\1" orig="\1"', html)

    # Discover and fetch all lazy-loaded JSONP page chunks (Scribd delivers pages 4..N as gzipped JSONP)
    asset_match = re.search(r'html\.scribd(?:assets)?\.com/([a-zA-Z0-9]+)/', html)
    asset_key = asset_match.group(1) if asset_match else None
    jsonp_matches = re.findall(r'pages/(\d+)-([a-zA-Z0-9]+)\.jsonp', html)

    if asset_key and jsonp_matches:
        def fetch_jsonp_page(item):
            pn, h_val = item
            domains = ['html.scribdassets.com', 'html.scribd.com']
            for dom in domains:
                try:
                    u = f"https://{dom}/{asset_key}/pages/{pn}-{h_val}.jsonp"
                    req = urllib.request.Request(u, headers=req_headers)
                    with urllib.request.urlopen(req, timeout=9) as resp:
                        raw = resp.read()
                        try:
                            dec = gzip.decompress(raw).decode('utf-8', errors='ignore')
                        except Exception:
                            dec = raw.decode('utf-8', errors='ignore')
                        m = re.search(r'window\.page\d+_callback\(\s*(\[[\s\S]*\])\s*\)', dec)
                        if m:
                            arr = json.loads(m.group(1))
                            return int(pn), arr[0]
                except Exception:
                    pass
            return int(pn), None

        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as ex:
            futs = [ex.submit(fetch_jsonp_page, item) for item in jsonp_matches]
            for f in concurrent.futures.as_completed(futs):
                pn, p_content = f.result()
                if p_content:
                    outer_target = f'id="outer_page_{pn}"'
                    if outer_target in html:
                        idx = html.find(outer_target)
                        tag_end = html.find('>', idx)
                        if tag_end != -1:
                            html = html[:tag_end+1] + '\n' + p_content + '\n' + html[tag_end+1:]
                    else:
                        new_outer = f'<div class="outer_page only_ie6_border" id="outer_page_{pn}" style="width:902px; height:1167px;">\n{p_content}\n</div>\n'
                        scroller_idx = html.find('class="document_scroller')
                        if scroller_idx != -1:
                            tag_end = html.find('>', scroller_idx)
                            html = html[:tag_end+1] + '\n' + new_outer + html[tag_end+1:]

    # Discover and declare all embedded Scribd fonts (@font-face)
    all_font_ids = set()
    for match in re.findall(r'fonts:\s*\[([0-9,\s]+)\]', html):
        for f in match.split(','):
            if f.strip().isdigit():
                all_font_ids.add(int(f.strip()))
    for ff in re.findall(r'\bff(\d+)\b', html):
        all_font_ids.add(int(ff))

    font_css_rules = []
    if asset_key and all_font_ids:
        for fid in sorted(all_font_ids):
            f_code = str(fid).zfill(4)
            font_url = f"https://html.scribdassets.com/{asset_key}/fonts/{f_code}.woff2"
            font_css_rules.append(f"""
@font-face {{
    font-family: 'ff{fid}';
    src: url('{font_url}') format('woff2');
}}
.ff{fid}, div.ff{fid}, span.ff{fid} {{
    font-family: 'ff{fid}' !important;
}}
""")

    html = re.sub(r'<script[\s\S]*?</script>', '', html)

    # Auto-detect native document dimensions & aspect ratio from embed structure
    dim_match = re.search(r'id=["\']outer_page_1["\'][^>]*style=["\'][^"\']*width:\s*(\d+)px;\s*height:\s*(\d+)px', html)
    if not dim_match:
        dim_match = re.search(r'origWidth[:\s]+(\d+)[\s,]+origHeight[:\s]+(\d+)', html)
    if not dim_match:
        dim_match = re.search(r'width:\s*(\d+)px;\s*height:\s*(\d+)px', html)

    if dim_match:
        native_w = int(dim_match.group(1))
        native_h = int(dim_match.group(2))
    else:
        native_w, native_h = 902, 1167

    # Keep native bounds within printable ratios; default to A4 if continuous web capture
    if native_h > native_w * 2.0 or native_h < 100 or native_w < 100:
        doc_w, doc_h = 794, 1122
    else:
        doc_w, doc_h = native_w, native_h

    print_rules = f"""
<style>
{''.join(font_css_rules)}

@page {{ size: {doc_w}px {doc_h}px; margin: 0; }}
html, body {{ margin: 0 !important; padding: 0 !important; background: #fff !important; width: 100% !important; }}
header, footer, nav, .toolbar, .toolbar_container, .document_toolbar, 
.embed_header, .mobile_header, .bottom_bar, .action_bar, .share_btn,
.between_page_ads, .page_missing_explanation_view, .upgrade_button,
.page_blur, .blur, .promo_wrapper, .mobile_overlay, .toolbar_drop {{
    display: none !important;
    visibility: hidden !important;
}}
.auto__embeds_new_show, .document_scroller, .document_container, .outer_page_container {{ 
    height: auto !important; 
    max-height: none !important; 
    overflow: visible !important; 
    position: static !important; 
    display: block !important; 
    margin: 0 !important;
    padding: 0 !important;
}}
.outer_page {{ 
    page-break-after: always !important; 
    break-after: page !important; 
    page-break-inside: avoid !important;
    break-inside: avoid !important;
    display: block !important; 
    position: relative !important; 
    width: {doc_w}px !important; 
    height: {doc_h}px !important; 
    margin: 0 auto !important; 
    overflow: hidden !important; 
    contain: none !important;
    border: none !important;
}}
.newpage {{ 
    position: absolute !important; 
    left: 0 !important; 
    top: 0 !important; 
    width: {doc_w}px !important; 
    height: {doc_h}px !important; 
    display: block !important; 
    transform: none !important;
}}
.text_layer {{ 
    position: absolute !important; 
    left: 0 !important; 
    top: 0 !important; 
    width: {doc_w}px !important; 
    height: {doc_h}px !important; 
    z-index: 2 !important; 
    color: #000 !important; 
    opacity: 1 !important; 
    transform: none !important;
    zoom: 0.2 !important;
}}
.text_layer div, .text_layer span {{
    white-space: nowrap !important;
    line-height: 1 !important;
}}
.text_layer span.a, .text_layer span.g {{
    position: absolute !important;
}}
.absimg {{ position: absolute !important; display: block !important; z-index: 1 !important; }}
.outer_page.blurred_page {{ filter: none !important; }}
.outer_page.blurred_page .text_layer {{ color: #000 !important; text-shadow: none !important; }}
</style>
"""
    if '</head>' in html:
        html = html.replace('</head>', print_rules + '</head>')
    else:
        html = print_rules + html

    temp_html_path = os.path.join(CACHE_DIR, f"temp_{doc_id}.html")
    raw_pdf_path = os.path.join(CACHE_DIR, f"temp_{doc_id}_raw.pdf")

    try:
        with open(temp_html_path, "w", encoding="utf-8") as f:
            f.write(html)

        file_url = "file:///" + temp_html_path.replace("\\", "/")
        cmd = [
            browser_exe,
            "--headless",
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-gpu",
            "--no-pdf-header-footer",
            f"--print-to-pdf={raw_pdf_path}",
            file_url
        ]

        subprocess.run(cmd, capture_output=True, timeout=25)
        if os.path.exists(raw_pdf_path) and os.path.getsize(raw_pdf_path) > 1000:
            reader = pypdf.PdfReader(raw_pdf_path)
            writer = pypdf.PdfWriter()
            for p in reader.pages:
                txt = p.extract_text().strip()
                # Include page if it has text or images, or is part of a multi-page document
                if len(txt) > 0 or len(p.images) > 0 or len(reader.pages) <= expected_page_count:
                    writer.add_page(p)

            # Check if embed is only a preview snippet of a much larger document
            if expected_page_count > 3 and len(writer.pages) < expected_page_count * 0.7:
                print(f"[INFO] Embed rendered only {len(writer.pages)} of {expected_page_count} pages. Falling back to multi-page image stitcher.")
                return None

            if len(writer.pages) > 0:
                out_io = io.BytesIO()
                writer.write(out_io)
                return out_io.getvalue()
            else:
                with open(raw_pdf_path, "rb") as rf:
                    return rf.read()
    except Exception as e:
        print(f"[ERROR] Headless PDF render failed for {doc_id}: {e}")
    finally:
        for p in [temp_html_path, raw_pdf_path]:
            if os.path.exists(p):
                try:
                    os.remove(p)
                except Exception:
                    pass
    return None

def extract_scribd_doc_info(target_url: str, cookie_str: Optional[str] = None):
    doc_id_match = re.search(r'/(?:document|doc|presentation|read|embeds)/(\d+)', target_url)
    if not doc_id_match:
        return None, "Invalid Scribd document URL format"

    doc_id = doc_id_match.group(1)

    # Check in-memory metadata cache (if not using custom cookie)
    if not cookie_str and doc_id in META_CACHE:
        return META_CACHE[doc_id], None

    req_headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
        'Upgrade-Insecure-Requests': '1',
    }
    if cookie_str:
        req_headers['Cookie'] = cookie_str

    # 1. Fetch oEmbed metadata
    title = f"Scribd_Document_{doc_id}"
    author_name = "Scribd Author"
    thumbnail_url = ""

    try:
        oembed_url = f'https://www.scribd.com/services/oembed?url=https://www.scribd.com/document/{doc_id}&format=json'
        req_oembed = urllib.request.Request(oembed_url, headers=req_headers)
        with urllib.request.urlopen(req_oembed, timeout=8) as resp:
            oembed_data = json.loads(resp.read().decode('utf-8'))
            title = oembed_data.get('title', title)
            author_name = oembed_data.get('author_name', author_name)
            thumbnail_url = oembed_data.get('thumbnail_url', '')
    except Exception as e:
        print(f"[WARN] oEmbed note for {doc_id}: {e}")

    # 2. Multi-Endpoint Discovery (Proven Competitor Architecture)
    urls_to_try = [
        f'https://www.scribd.com/embeds/{doc_id}/content',
        f'https://www.scribd.com/document/{doc_id}',
        f'https://www.scribd.com/doc/{doc_id}',
        f'https://www.scribd.com/mobile/documents/{doc_id}'
    ]

    pages_dict = {}       # page_num -> (hash, primary_ext)
    asset_keys = set()
    total_page_count = 1
    per_page_text = []
    extracted_lines = []

    for fetch_url in urls_to_try:
        try:
            req = urllib.request.Request(fetch_url, headers=req_headers)
            with urllib.request.urlopen(req, timeout=9) as resp:
                html = resp.read().decode('utf-8', errors='ignore')

                # Extract asset keys (both html.scribd.com and html.scribdassets.com)
                for ak in re.findall(r'html\.scribd(?:assets)?\.com/([a-zA-Z0-9]+)/', html):
                    asset_keys.add(ak)

                # Extract contentUrls
                content_urls = re.findall(r'contentUrl:\s*["\'](https://html\.scribd(?:assets)?\.com/[^"\']+)["\']', html)
                for cu in content_urls:
                    cu_match = re.search(r'pages/(\d+)-([a-zA-Z0-9]+)\.jsonp', cu)
                    if cu_match:
                        n = int(cu_match.group(1))
                        h = cu_match.group(2)
                        if n not in pages_dict:
                            pages_dict[n] = (h, 'jpg')

                # Extract direct image matches: images/NUM-HASH.ext
                for num, h_val, ext in re.findall(r'images/(\d+)-([a-zA-Z0-9]+)\.(jpg|png|webp|svg)', html):
                    n = int(num)
                    pages_dict[n] = (h_val, ext)

                # Extract JSONP matches: pages/NUM-HASH.jsonp
                for num, h_val in re.findall(r'pages/(\d+)-([a-zA-Z0-9]+)\.jsonp', html):
                    n = int(num)
                    if n not in pages_dict:
                        pages_dict[n] = (h_val, 'jpg')

                # Extract images from orig or data-src in <img class="absimg">
                for orig_img in re.findall(r'(?:orig|data-src|src)=["\'](https?://html\.scribd(?:assets)?\.com/[^/]+/images/(\d+)-([a-zA-Z0-9]+)\.(jpg|png|webp))["\']', html):
                    n = int(orig_img[1])
                    pages_dict[n] = (orig_img[2], orig_img[3])

                # Extract total page count
                for pc in re.findall(r'page_count["\']?\s*:\s*(\d+)', html):
                    total_page_count = max(total_page_count, int(pc))
                for pc in re.findall(r'pageCount["\']?\s*:\s*(\d+)', html):
                    total_page_count = max(total_page_count, int(pc))
                for p_num in re.findall(r'pageNum["\']?\s*:\s*(\d+)', html):
                    total_page_count = max(total_page_count, int(p_num))

                # Extract per-page text layers (for text-based documents)
                for p_num in range(1, total_page_count + 1):
                    p_match = re.search(rf'id=["\']page{p_num}["\']([\s\S]*?)(?=<div[^>]+id=["\']page\d+["\']|</body>|$)', html)
                    if p_match:
                        spans = re.findall(r'<span[^>]*>(.*?)</span>', p_match.group(1))
                        p_lines = []
                        for s in spans:
                            c = re.sub(r'<[^>]+>', '', s).strip()
                            if c and len(c) > 0 and not c.startswith('scribd'):
                                p_lines.append(c)
                        if p_lines:
                            while len(per_page_text) < p_num:
                                per_page_text.append([])
                            per_page_text[p_num - 1] = p_lines
                            for l in p_lines:
                                if l not in extracted_lines:
                                    extracted_lines.append(l)

                if len(pages_dict) >= total_page_count and len(pages_dict) > 1:
                    break
        except Exception:
            pass

    asset_key = list(asset_keys)[0] if asset_keys else None

    page_items = []
    sorted_pages = sorted(pages_dict.items(), key=lambda x: x[0])
    if asset_key and sorted_pages:
        for num, (h, ext) in sorted_pages:
            page_items.append({
                'page': num,
                'hash': h,
                'primaryExt': ext,
                'url': f'https://html.scribdassets.com/{asset_key}/images/{num}-{h}.{ext}'
            })
        total_page_count = max(total_page_count, len(sorted_pages))

    safe_title = re.sub(r'[^a-zA-Z0-9_\-\. ]', '_', title).strip()
    if not safe_title:
        safe_title = f"Document_{doc_id}"

    info = {
        'success': True,
        'docId': doc_id,
        'title': title,
        'safeTitle': safe_title,
        'author': author_name,
        'thumbnailUrl': thumbnail_url,
        'pageCount': max(1, total_page_count),
        'pageImages': page_items,
        'assetKey': asset_key,
        'extractedLines': extracted_lines[:50],
        'perPageText': per_page_text,
        'targetUrl': target_url,
        'hasCookie': bool(cookie_str)
    }

    if not cookie_str:
        META_CACHE[doc_id] = info

    return info, None


@app.get("/", response_class=HTMLResponse)
def get_frontend():
    index_file = os.path.join(DIRECTORY, "index.html")
    if os.path.exists(index_file):
        with open(index_file, "r", encoding="utf-8") as f:
            return f.read()
    return "<h1>ScribdDL Pro is Running</h1>"


@app.get("/api/scribd")
def get_scribd_info(url: str = Query(...), cookie: Optional[str] = Query(None)):
    target_url = url.strip()
    if not target_url:
        raise HTTPException(status_code=400, detail="Missing url parameter")

    doc_info, err = extract_scribd_doc_info(target_url, cookie_str=cookie)
    if err or not doc_info:
        raise HTTPException(status_code=400, detail=err or "Failed to fetch document")

    return doc_info


@app.get("/api/progress")
def get_download_progress(doc_id: str = Query(...)):
    d_id = doc_id.strip()
    if d_id in PROGRESS_TRACKER:
        return PROGRESS_TRACKER[d_id]
    return {"status": "idle", "current": 0, "total": 1, "percent": 0, "message": "Ready"}


@app.get("/api/download")
def download_scribd_document(
    url: str = Query(...),
    format: str = Query("pdf"),
    cookie: Optional[str] = Query(None)
):
    target_url = url.strip()
    fmt = format.strip().lower()

    if not target_url:
        raise HTTPException(status_code=400, detail="Missing url parameter")

    doc_id_match = re.search(r'/(?:document|doc|presentation|read|embeds)/(\d+)', target_url)
    if not doc_id_match:
        raise HTTPException(status_code=400, detail="Invalid Scribd document URL")

    doc_id = doc_id_match.group(1)
    cache_filename = f"{doc_id}_{fmt}.{fmt}"
    cache_filepath = os.path.join(CACHE_DIR, cache_filename)

    doc_info, err = extract_scribd_doc_info(target_url, cookie_str=cookie)
    if err or not doc_info:
        raise HTTPException(status_code=404, detail=f"Document fetch failed: {err}")

    safe_title = doc_info.get('safeTitle', f"Document_{doc_id}")
    title = doc_info.get('title', 'document')
    page_count = doc_info.get('pageCount', 1)
    page_images = doc_info.get('pageImages', [])
    asset_key = doc_info.get('assetKey', '')
    per_page_text = doc_info.get('perPageText', [])

    # Fast Cache Hit Check (Directly returns cached file if valid and complete)
    if not cookie and os.path.exists(cache_filepath) and os.path.getsize(cache_filepath) > 1000:
        is_cache_valid = True
        if fmt == 'pdf' and page_count > 3:
            try:
                r = pypdf.PdfReader(cache_filepath)
                # Ensure page count matches expected count
                if len(r.pages) < page_count * 0.8:
                    is_cache_valid = False
                    os.remove(cache_filepath)
                else:
                    # Guard against blank pages in cached PDFs
                    # Check if pages beyond page 3 have content (text or images)
                    has_later_content = False
                    for p in r.pages[3:]:
                        if len(p.extract_text().strip()) > 10 or len(p.images) > 0:
                            has_later_content = True
                            break
                    if not has_later_content and len(r.pages) > 3:
                        is_cache_valid = False
                        os.remove(cache_filepath)
            except Exception:
                is_cache_valid = False
                try:
                    os.remove(cache_filepath)
                except Exception:
                    pass

        if is_cache_valid:
            PROGRESS_TRACKER[doc_id] = {
                "status": "complete",
                "current": page_count,
                "total": page_count,
                "percent": 100,
                "message": f"Instant cache hit ({page_count} pages)!"
            }
            media_type = "application/pdf" if fmt == 'pdf' else ("application/vnd.openxmlformats-officedocument.wordprocessingml.document" if fmt == 'docx' else "text/plain")
            encoded_fn = urllib.parse.quote(f"{safe_title}.{fmt}")
            return FileResponse(
                cache_filepath,
                media_type=media_type,
                filename=f"{safe_title}.{fmt}",
                headers={"Content-Disposition": f'attachment; filename="{safe_title}.{fmt}"; filename*=UTF-8\'\'{encoded_fn}'}
            )

    PROGRESS_TRACKER[doc_id] = {
        "status": "starting",
        "current": 0,
        "total": page_count,
        "percent": 5,
        "message": f"Analyzing document structure ({page_count} pages)..."
    }

    # 1. Direct Official Scribd Session Download (if cookie provided)
    if cookie and doc_id:
        try:
            direct_url = f"https://www.scribd.com/document_downloads/{doc_id}?extension={fmt}"
            dl_req = urllib.request.Request(direct_url, headers={
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
                'Cookie': cookie,
                'Referer': f"https://www.scribd.com/document/{doc_id}"
            })
            with urllib.request.urlopen(dl_req, timeout=12) as dl_resp:
                content_type = dl_resp.headers.get('Content-Type', '')
                raw_dl = dl_resp.read()
                if len(raw_dl) > 2000 and ('pdf' in content_type or 'octet-stream' in content_type or raw_dl.startswith(b'%PDF') or raw_dl.startswith(b'PK')):
                    ct = "application/pdf" if fmt == 'pdf' else ("application/vnd.openxmlformats-officedocument.wordprocessingml.document" if fmt == 'docx' else "text/plain")
                    # Save to cache
                    with open(cache_filepath, "wb") as cf:
                        cf.write(raw_dl)
                    encoded_fn = urllib.parse.quote(f"{safe_title}.{fmt}")
                    return Response(
                        content=raw_dl,
                        media_type=ct,
                        headers={"Content-Disposition": f'attachment; filename="{safe_title}.{fmt}"; filename*=UTF-8\'\'{encoded_fn}'}
                    )
        except Exception as dl_err:
            print(f"[NOTE] Direct session download fallback to asset compilation: {dl_err}")

    # 2. TXT Format
    if fmt == 'txt':
        lines = doc_info.get('extractedLines', [])
        txt_content = f"{title}\nAuthor: {doc_info.get('author')}\nID: {doc_id}\nPages: {page_count}\n\n"
        txt_content += "\n".join(lines)
        data = ('\ufeff' + txt_content).encode('utf-8')
        with open(cache_filepath, "wb") as cf:
            cf.write(data)
        encoded_fn = urllib.parse.quote(f"{safe_title}.txt")
        return Response(
            content=data,
            media_type="text/plain; charset=utf-8",
            headers={"Content-Disposition": f'attachment; filename="{safe_title}.txt"; filename*=UTF-8\'\'{encoded_fn}'}
        )

    # 3. DOCX / Word Format
    if fmt == 'docx':
        body_html = ""
        for p_idx, p_lines in enumerate(per_page_text, 1):
            body_html += f"<h3>Page {p_idx}</h3>"
            for line in p_lines:
                body_html += f"<p>{line}</p>"
        docx_content = f"""
        <html xmlns:o='urn:schemas-microsoft-com:office:office' xmlns:w='urn:schemas-microsoft-com:office:word' xmlns='http://www.w3.org/TR/REC-html40'>
        <head><meta charset='utf-8'><title>{title}</title>
        <style>body{{font-family:Calibri,sans-serif;font-size:11pt;line-height:1.5;margin:1in;}} h1{{color:#1e40af;}}</style>
        </head>
        <body>
        <h1>{title}</h1>
        <p><strong>Author:</strong> {doc_info.get('author')} | <strong>Pages:</strong> {page_count}</p>
        <hr>
        {body_html}
        </body></html>
        """
        data = ('\ufeff' + docx_content).encode('utf-8')
        with open(cache_filepath, "wb") as cf:
            cf.write(data)
        encoded_fn = urllib.parse.quote(f"{safe_title}.docx")
        return Response(
            content=data,
            media_type="application/vnd.ms-word",
            headers={"Content-Disposition": f'attachment; filename="{safe_title}.docx"; filename*=UTF-8\'\'{encoded_fn}'}
        )

    # 4. PDF Format:
    if fmt == 'pdf':
        # Priority 1: Exact Authentic Vector/HTML Headless Rendering (1:1 Original Layout, Fonts, Math, Borders)
        PROGRESS_TRACKER[doc_id] = {
            "status": "rendering",
            "current": page_count,
            "total": page_count,
            "percent": 30,
            "message": f"Rendering 1:1 original layout ({page_count} pages)..."
        }
        original_pdf = generate_pdf_via_headless(doc_id, expected_page_count=page_count, cookie_str=cookie)
        if original_pdf and len(original_pdf) > 2000:
            with open(cache_filepath, "wb") as cf:
                cf.write(original_pdf)
            PROGRESS_TRACKER[doc_id] = {
                "status": "complete",
                "current": page_count,
                "total": page_count,
                "percent": 100,
                "message": f"Complete {page_count}-page vector PDF ready!"
            }
            encoded_fn = urllib.parse.quote(f"{safe_title}.pdf")
            return Response(
                content=original_pdf,
                media_type="application/pdf",
                headers={"Content-Disposition": f'attachment; filename="{safe_title}.pdf"; filename*=UTF-8\'\'{encoded_fn}'}
            )

    # Check whether document is Scanned (Raster Photos) vs Vector / Text based
    if page_images and asset_key and len(page_images) >= 2:
        def download_one(item):
            p_num = item['page']
            h_val = item['hash']
            primary_ext = item.get('primaryExt', 'jpg')
            exts_to_try = [primary_ext] + [e for e in ['jpg', 'png', 'webp'] if e != primary_ext]
            domains = ['html.scribdassets.com', 'html.scribd.com']

            for domain in domains:
                for ext in exts_to_try:
                    img_url = f"https://{domain}/{asset_key}/images/{p_num}-{h_val}.{ext}"
                    for _ in range(2):
                        try:
                            img_headers = {
                                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
                                'Referer': 'https://www.scribd.com/'
                            }
                            if cookie:
                                img_headers['Cookie'] = cookie
                            req = urllib.request.Request(img_url, headers=img_headers)
                            with urllib.request.urlopen(req, timeout=10) as r:
                                raw = r.read()
                                if len(raw) > 200:
                                    im = Image.open(io.BytesIO(raw)).convert('RGB')
                                    return p_num, im, len(raw)
                        except Exception:
                            pass
            return p_num, None, 0

        fetched_dict = {}
        raw_sizes = []
        total_imgs = len(page_images)
        completed_count = 0

        PROGRESS_TRACKER[doc_id] = {
            "status": "downloading",
            "current": 0,
            "total": total_imgs,
            "percent": 10,
            "message": f"Starting 24-thread high-speed download (0/{total_imgs})..."
        }

        with concurrent.futures.ThreadPoolExecutor(max_workers=24) as executor:
            futures = {executor.submit(download_one, item): item for item in page_images}
            for future in concurrent.futures.as_completed(futures):
                p_num, img, byte_len = future.result()
                if img is not None:
                    fetched_dict[p_num] = img
                    raw_sizes.append(byte_len)
                completed_count += 1
                pct = min(92, 10 + int((completed_count / total_imgs) * 82))
                PROGRESS_TRACKER[doc_id] = {
                    "status": "downloading",
                    "current": completed_count,
                    "total": total_imgs,
                    "percent": pct,
                    "message": f"Downloaded page {completed_count} of {total_imgs} ({pct}%)"
                }

        PROGRESS_TRACKER[doc_id] = {
            "status": "assembling",
            "current": total_imgs,
            "total": total_imgs,
            "percent": 95,
            "message": f"Assembling {len(fetched_dict)} pages into authentic PDF..."
        }

        sorted_nums = sorted(fetched_dict.keys())
        avg_size = (sum(raw_sizes) / len(raw_sizes)) if raw_sizes else 0
        has_rich_text = any(len(lines) >= 8 for lines in per_page_text)

        # Case A: Document images successfully fetched (genuine scanned bitmap photos or multi-page assets)
        if sorted_nums and len(sorted_nums) >= max(1, int(page_count * 0.5)):
            pil_images = [fetched_dict[n] for n in sorted_nums if n in fetched_dict]
            if pil_images:
                # Auto-expand any ultra-tall continuous web captures into standard A4 pages
                expanded_images = []
                for im in pil_images:
                    iw, ih = im.size
                    if ih > iw * 1.8:
                        page_h = int(iw * 1.414)
                        y = 0
                        while y < ih:
                            box = (0, y, iw, min(ih, y + page_h))
                            slice_im = im.crop(box)
                            if slice_im.size[1] < page_h:
                                pad = Image.new('RGB', (iw, page_h), color='#ffffff')
                                pad.paste(slice_im, (0, 0))
                                slice_im = pad
                            expanded_images.append(slice_im)
                            y += page_h
                    else:
                        expanded_images.append(im)

                final_images = expanded_images if expanded_images else pil_images
                final_images[0].save(
                    cache_filepath,
                    "PDF",
                    resolution=100.0,
                    save_all=True,
                    append_images=final_images[1:]
                )
                PROGRESS_TRACKER[doc_id] = {
                    "status": "complete",
                    "current": len(final_images),
                    "total": max(page_count, len(final_images)),
                    "percent": 100,
                    "message": f"Complete {len(final_images)}-page PDF ready!"
                }
                encoded_fn = urllib.parse.quote(f"{safe_title}.pdf")
                return FileResponse(
                    cache_filepath,
                    media_type="application/pdf",
                    filename=f"{safe_title}.pdf",
                    headers={"Content-Disposition": f'attachment; filename="{safe_title}.pdf"; filename*=UTF-8\'\'{encoded_fn}'}
                )

    # Case B: Document is text-based (Word doc, essay, university assignment, thesis)
    rendered_pages = []
    for p_idx in range(1, page_count + 1):
        p_lines = per_page_text[p_idx - 1] if p_idx - 1 < len(per_page_text) else []
        canvas = Image.new('RGB', (1240, 1754), color='#ffffff')
        draw = ImageDraw.Draw(canvas)

        # Header Banner
        draw.rectangle([(0, 0), (1240, 80)], fill='#1e3a8a')
        draw.text((40, 26), f"{title[:70]}  •  Page {p_idx} of {page_count}", fill='#ffffff')
        draw.rectangle([(40, 95), (1200, 97)], fill='#cbd5e1')

        y = 125
        cur_line = ""
        for token in p_lines:
            if y > 1650:
                break
            if len(token) <= 4 and cur_line and not cur_line.endswith(('.', ':', '?')):
                cur_line += " " + token
            else:
                if cur_line:
                    draw.text((60, y), cur_line, fill='#0f172a')
                    y += 30
                cur_line = token

        if cur_line and y <= 1650:
            draw.text((60, y), cur_line, fill='#0f172a')

        # Footer
        draw.text((60, 1680), f"Exported from Scribd  •  Page {p_idx} of {page_count}", fill='#94a3b8')
        rendered_pages.append(canvas)

    if rendered_pages:
        pdf_bytes_io = io.BytesIO()
        rendered_pages[0].save(
            pdf_bytes_io,
            "PDF",
            resolution=100.0,
            save_all=True,
            append_images=rendered_pages[1:]
        )
        pdf_data = pdf_bytes_io.getvalue()
        with open(cache_filepath, "wb") as cf:
            cf.write(pdf_data)
        encoded_fn = urllib.parse.quote(f"{safe_title}.pdf")
        return Response(
            content=pdf_data,
            media_type="application/pdf",
            headers={"Content-Disposition": f'attachment; filename="{safe_title}.pdf"; filename*=UTF-8\'\'{encoded_fn}'}
        )

    raise HTTPException(status_code=500, detail="Could not assemble document pages")


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 8080))
    uvicorn.run("app:app", host="0.0.0.0", port=port, reload=False)

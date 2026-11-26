import http.server
import socketserver
import urllib.request
import urllib.parse
import json
import re
import os
import io
import concurrent.futures
from PIL import Image, ImageDraw

PORT = 8080
DIRECTORY = os.path.dirname(os.path.abspath(__file__))

def fetch_scribd_doc_info(target_url, cookie_str=None):
    doc_id_match = re.search(r'/(?:document|doc|presentation|read|embeds)/(\d+)', target_url)
    if not doc_id_match:
        return None, "Invalid Scribd document URL format"

    doc_id = doc_id_match.group(1)

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
        print(f"[WARN] oEmbed fetch note for {doc_id}: {e}")

    # 2. Multi-Endpoint Discovery (Proven Competitor Method)
    # Tries embed, desktop document, and mobile document views
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

                # Extract contentUrls (e.g. contentUrl: "https://html.scribdassets.com/.../pages/N-hash.jsonp")
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

                # Extract total page count from page_count, pageCount, or pageNum
                for pc in re.findall(r'page_count["\']?\s*:\s*(\d+)', html):
                    total_page_count = max(total_page_count, int(pc))
                for pc in re.findall(r'pageCount["\']?\s*:\s*(\d+)', html):
                    total_page_count = max(total_page_count, int(pc))
                for p_num in re.findall(r'pageNum["\']?\s*:\s*(\d+)', html):
                    total_page_count = max(total_page_count, int(p_num))

                # Extract per-page text layers (for text-based or mixed documents)
                for p_num in range(1, total_page_count + 1):
                    p_match = re.search(rf'id=["\']page{p_num}["\']([\s\S]*?)(?=<div[^>]+id=["\']page\d+["\']|</body>|$)', html)
                    if p_match:
                        # Extract positioned span elements
                        spans = re.findall(r'<span[^>]*>(.*?)</span>', p_match.group(1))
                        p_lines = []
                        for s in spans:
                            c = re.sub(r'<[^>]+>', '', s).strip()
                            if c and len(c) > 1 and not c.startswith('scribd'):
                                p_lines.append(c)
                        if p_lines:
                            while len(per_page_text) < p_num:
                                per_page_text.append([])
                            per_page_text[p_num - 1] = p_lines
                            for l in p_lines:
                                if l not in extracted_lines:
                                    extracted_lines.append(l)

                # If we got a rich set of pages already, we can stop querying other variants
                if len(pages_dict) >= total_page_count and len(pages_dict) > 1:
                    break

        except Exception as e:
            # Continue to next variant
            pass

    # Pick the primary asset key
    asset_key = list(asset_keys)[0] if asset_keys else None

    # Prepare page items list
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

    return {
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
    }, None

class ScribdProxyHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == '/api/scribd':
            self.handle_scribd_api(parsed)
        elif parsed.path == '/api/download':
            self.handle_scribd_download(parsed)
        else:
            super().do_GET()

    def handle_scribd_api(self, parsed):
        params = urllib.parse.parse_qs(parsed.query)
        target_url = params.get('url', [''])[0].strip()
        cookie_str = params.get('cookie', [''])[0].strip()

        if not target_url:
            self.send_json_response(400, {'success': False, 'error': 'Missing url parameter'})
            return

        doc_info, err = fetch_scribd_doc_info(target_url, cookie_str=cookie_str)
        if err or not doc_info:
            self.send_json_response(400, {'success': False, 'error': err or 'Failed to fetch document'})
            return

        self.send_json_response(200, doc_info)

    def handle_scribd_download(self, parsed):
        params = urllib.parse.parse_qs(parsed.query)
        target_url = params.get('url', [''])[0].strip()
        fmt = params.get('format', ['pdf'])[0].strip().lower()
        cookie_str = params.get('cookie', [''])[0].strip()

        if not target_url:
            self.send_error(400, "Missing url parameter")
            return

        doc_info, err = fetch_scribd_doc_info(target_url, cookie_str=cookie_str)
        if err or not doc_info:
            self.send_error(404, f"Document fetch failed: {err}")
            return

        doc_id = doc_info.get('docId')
        page_images = doc_info.get('pageImages', [])
        asset_key = doc_info.get('assetKey', '')
        per_page_text = doc_info.get('perPageText', [])
        page_count = doc_info.get('pageCount', 1)
        safe_title = doc_info.get('safeTitle', 'document')
        title = doc_info.get('title', 'document')

        # 1. Direct Official Scribd Session Download (if cookie provided)
        if cookie_str and doc_id:
            try:
                direct_url = f"https://www.scribd.com/document_downloads/{doc_id}?extension={fmt}"
                dl_req = urllib.request.Request(direct_url, headers={
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
                    'Cookie': cookie_str,
                    'Referer': f"https://www.scribd.com/document/{doc_id}"
                })
                with urllib.request.urlopen(dl_req, timeout=12) as dl_resp:
                    content_type = dl_resp.headers.get('Content-Type', '')
                    raw_dl = dl_resp.read()
                    if len(raw_dl) > 2000 and ('pdf' in content_type or 'octet-stream' in content_type or raw_dl.startswith(b'%PDF') or raw_dl.startswith(b'PK')):
                        print(f"[SUCCESS] Downloaded original file via Scribd Session Cookie ({len(raw_dl)} bytes)")
                        ct = "application/pdf" if fmt == 'pdf' else ("application/vnd.openxmlformats-officedocument.wordprocessingml.document" if fmt == 'docx' else "text/plain")
                        self.send_file_attachment(raw_dl, f"{safe_title}.{fmt}", ct)
                        return
            except Exception as dl_err:
                print(f"[NOTE] Direct session download fallback to asset compilation: {dl_err}")

        # 2. TXT Format
        if fmt == 'txt':
            lines = doc_info.get('extractedLines', [])
            txt_content = f"{title}\nAuthor: {doc_info.get('author')}\nID: {doc_info.get('docId')}\nPages: {page_count}\n\n"
            txt_content += "\n".join(lines)
            data = ('\ufeff' + txt_content).encode('utf-8')
            self.send_file_attachment(data, f"{safe_title}.txt", "text/plain; charset=utf-8")
            return

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
            self.send_file_attachment(data, f"{safe_title}.docx", "application/vnd.ms-word")
            return

        # 4. PDF Format:
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
                        try:
                            img_headers = {
                                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
                                'Referer': 'https://www.scribd.com/'
                            }
                            if cookie_str:
                                img_headers['Cookie'] = cookie_str
                            req = urllib.request.Request(img_url, headers=img_headers)
                            with urllib.request.urlopen(req, timeout=6) as r:
                                raw = r.read()
                                if len(raw) > 200:
                                    im = Image.open(io.BytesIO(raw)).convert('RGB')
                                    return p_num, im, len(raw)
                        except:
                            pass
                return p_num, None, 0

            fetched_dict = {}
            raw_sizes = []
            with concurrent.futures.ThreadPoolExecutor(max_workers=16) as executor:
                results = executor.map(download_one, page_images)
                for p_num, img, byte_len in results:
                    if img is not None:
                        fetched_dict[p_num] = img
                        raw_sizes.append(byte_len)

            sorted_nums = sorted(fetched_dict.keys())
            avg_size = (sum(raw_sizes) / len(raw_sizes)) if raw_sizes else 0
            has_rich_text = any(len(lines) >= 8 for lines in per_page_text)

            # Case A: Document is genuine scanned bitmap photos (average image >= 15KB)
            if sorted_nums and (avg_size >= 15000 or not has_rich_text):
                pil_images = [fetched_dict[n] for n in sorted_nums]
                pdf_bytes_io = io.BytesIO()
                pil_images[0].save(
                    pdf_bytes_io,
                    "PDF",
                    resolution=100.0,
                    save_all=True,
                    append_images=pil_images[1:]
                )
                pdf_data = pdf_bytes_io.getvalue()
                print(f"[SUCCESS] Compiled {len(pil_images)}/{len(page_images)} scanned pages into PDF ({len(pdf_data)} bytes)")
                self.send_file_attachment(pdf_data, f"{safe_title}.pdf", "application/pdf")
                return

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

            # Render text lines with flowing sentence structure
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
            print(f"[SUCCESS] Rendered {len(rendered_pages)} vector/text pages into PDF ({len(pdf_data)} bytes)")
            self.send_file_attachment(pdf_data, f"{safe_title}.pdf", "application/pdf")
            return

        self.send_error(500, "Could not assemble document pages")

    def send_file_attachment(self, file_bytes, filename, content_type):
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(file_bytes)))
        encoded_filename = urllib.parse.quote(filename)
        self.send_header('Content-Disposition', f'attachment; filename="{filename}"; filename*=UTF-8\'\'{encoded_filename}')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(file_bytes)

    def send_json_response(self, status_code, data):
        body = json.dumps(data).encode('utf-8')
        self.send_response(status_code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()
        self.wfile.write(body)

if __name__ == '__main__':
    with socketserver.TCPServer(("", PORT), ScribdProxyHandler) as httpd:
        print(f"ScribdDL server running on http://localhost:{PORT}")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down server.")

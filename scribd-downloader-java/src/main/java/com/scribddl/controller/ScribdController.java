package com.scribddl.controller;

import com.scribddl.model.DocInfo;
import com.scribddl.model.ProgressInfo;
import com.scribddl.service.HeadlessBrowserService;
import com.scribddl.service.ScribdService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class ScribdController {

    private final ScribdService scribdService;
    private final HeadlessBrowserService headlessBrowserService;

    public ScribdController(ScribdService scribdService, HeadlessBrowserService headlessBrowserService) {
        this.scribdService = scribdService;
        this.headlessBrowserService = headlessBrowserService;
    }

    @GetMapping("/api/diag")
    public ResponseEntity<Map<String, Object>> getDiagnostics() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("os_name", System.getProperty("os.name"));
        map.put("os_arch", System.getProperty("os.arch"));
        Runtime rt = Runtime.getRuntime();
        map.put("jvm_max_memory_mb", rt.maxMemory() / (1024 * 1024));
        map.put("jvm_total_memory_mb", rt.totalMemory() / (1024 * 1024));
        map.put("jvm_free_memory_mb", rt.freeMemory() / (1024 * 1024));
        map.put("browser_available", headlessBrowserService.isAvailable());
        map.put("browser_path", headlessBrowserService.getBrowserPath());
        map.put("browser_version", headlessBrowserService.testBrowserVersion());

        try {
            String testHtml = "<!DOCTYPE html><html><body><h1>Diag Test</h1><p>Rendering ok</p></body></html>";
            byte[] testPdf = headlessBrowserService.renderHtmlToPdf(testHtml, "diag_check", 1);
            map.put("test_render_pdf_bytes", testPdf != null ? testPdf.length : 0);
            map.put("test_render_status", testPdf != null ? "SUCCESS" : "FAILED");
        } catch (Exception e) {
            map.put("test_render_error", e.getMessage());
        }
        map.put("last_browser_execution", headlessBrowserService.getLastExecutionLog());
        return ResponseEntity.ok(map);
    }

    @GetMapping("/api/debug-render")
    public ResponseEntity<Map<String, Object>> debugRender(@RequestParam("doc_id") String docId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("doc_id", docId);
        try {
            DocInfo docInfo = scribdService.extractDocInfo("https://www.scribd.com/document/" + docId + "/", null);
            map.put("title", docInfo.getTitle());
            map.put("page_count", docInfo.getPageCount());
            map.put("asset_key", docInfo.getAssetKey());

            long t0 = System.currentTimeMillis();
            String embedHtml = scribdService.fetchAndPrepareEmbedHtml(docId, null, docInfo.getAssetKey());
            map.put("fetch_embed_ms", System.currentTimeMillis() - t0);
            map.put("embed_html_len", embedHtml != null ? embedHtml.length() : 0);

            if (embedHtml != null) {
                long t1 = System.currentTimeMillis();
                byte[] pdf = headlessBrowserService.renderHtmlToPdf(embedHtml, docId, docInfo.getPageCount());
                map.put("render_ms", System.currentTimeMillis() - t1);
                map.put("pdf_bytes", pdf != null ? pdf.length : 0);
                map.put("render_success", pdf != null);
            }
            map.put("last_browser_execution", headlessBrowserService.getLastExecutionLog());
        } catch (Exception e) {
            map.put("error", e.getMessage());
        }
        return ResponseEntity.ok(map);
    }

    @GetMapping("/api/scribd")
    public ResponseEntity<?> getScribdInfo(
            @RequestParam("url") String url,
            @RequestParam(value = "cookie", required = false) String cookie) {
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body("{\"error\": \"Missing url parameter\"}");
        }

        try {
            DocInfo info = scribdService.extractDocInfo(url.trim(), cookie);
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}");
        }
    }

    @GetMapping("/api/progress")
    public ResponseEntity<ProgressInfo> getProgress(@RequestParam("doc_id") String docId) {
        if (docId == null || docId.isBlank()) {
            return ResponseEntity.ok(new ProgressInfo("idle", 0, 1, 0, "Ready"));
        }
        return ResponseEntity.ok(scribdService.getProgress(docId.trim()));
    }

    @GetMapping("/api/download")
    public ResponseEntity<?> downloadDocument(
            @RequestParam("url") String url,
            @RequestParam(value = "format", defaultValue = "pdf") String format,
            @RequestParam(value = "cookie", required = false) String cookie) {
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body("Missing url parameter");
        }

        try {
            String fmt = (format != null ? format.trim().toLowerCase() : "pdf");
            File file = scribdService.downloadDocument(url.trim(), fmt, cookie);
            if (!file.exists()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Generated file missing");
            }

            Resource resource = new FileSystemResource(file);
            DocInfo docInfo = scribdService.extractDocInfo(url.trim(), cookie);
            String safeTitle = (docInfo != null && docInfo.getSafeTitle() != null)
                    ? docInfo.getSafeTitle()
                    : "document_" + file.getName();

            String filename = safeTitle + "." + fmt;
            String encodedFn = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

            MediaType mediaType;
            if ("pdf".equals(fmt)) {
                mediaType = MediaType.APPLICATION_PDF;
            } else if ("docx".equals(fmt)) {
                mediaType = MediaType.parseMediaType("application/vnd.ms-word");
            } else {
                mediaType = MediaType.TEXT_PLAIN;
            }

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFn)
                    .contentLength(file.length())
                    .body(resource);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Document download failed: " + e.getMessage());
        }
    }
}

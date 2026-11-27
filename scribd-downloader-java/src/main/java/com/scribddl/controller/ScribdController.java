package com.scribddl.controller;

import com.scribddl.model.DocInfo;
import com.scribddl.model.ProgressInfo;
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

@RestController
@CrossOrigin(origins = "*")
public class ScribdController {

    private final ScribdService scribdService;

    public ScribdController(ScribdService scribdService) {
        this.scribdService = scribdService;
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

package com.scribddl.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class HeadlessBrowserService {
    private static final Logger log = LoggerFactory.getLogger(HeadlessBrowserService.class);

    private final String browserPath;

    public HeadlessBrowserService() {
        this.browserPath = detectBrowserExecutable();
        if (browserPath != null) {
            log.info("Headless browser detected: {}", browserPath);
        } else {
            log.warn("No headless Chrome/Edge detected. Headless PDF generation fallback will be skipped.");
        }
    }

    public boolean isAvailable() {
        return browserPath != null;
    }

    public byte[] renderHtmlToPdf(String htmlContent, String docId, int expectedPageCount) {
        if (browserPath == null) {
            return null;
        }

        Path tempHtml = null;
        Path tempPdf = null;
        try {
            tempHtml = Files.createTempFile("scribd_" + docId + "_", ".html");
            tempPdf = Files.createTempFile("scribd_" + docId + "_raw_", ".pdf");

            Files.writeString(tempHtml, htmlContent, StandardCharsets.UTF_8);

            String fileUrl = tempHtml.toUri().toString();

            // Dynamic timeout scaling: minimum 35s, up to 180s for huge documents
            long timeoutSeconds = Math.min(180, Math.max(35, (long) (expectedPageCount * 1.5)));
            long virtualBudgetMs = Math.min(25000, Math.max(8000, expectedPageCount * 600));

            List<String> command = new ArrayList<>(Arrays.asList(
                    browserPath,
                    "--headless=new",
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--disable-gpu",
                    "--disable-web-security",
                    "--allow-running-insecure-content",
                    "--run-all-compositor-stages-before-draw",
                    "--virtual-time-budget=" + virtualBudgetMs,
                    "--no-pdf-header-footer",
                    "--print-to-pdf=" + tempPdf.toAbsolutePath().toString(),
                    fileUrl
            ));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Headless browser timed out after {} seconds for doc {}", timeoutSeconds, docId);
                return null;
            }

            File pdfFile = tempPdf.toFile();
            if (pdfFile.exists() && pdfFile.length() > 1000) {
                try (PDDocument doc = Loader.loadPDF(pdfFile)) {
                    int actualPages = doc.getNumberOfPages();

                    // Validation: Page Count
                    if (expectedPageCount > 3 && actualPages < (expectedPageCount * 0.7)) {
                        log.info("Headless embed rendered only {}/{} pages for doc {}. Triggering auto-fallback.",
                                actualPages, expectedPageCount, docId);
                        return null;
                    }

                    // Validation: Non-blank verification
                    // Ensure that document actually has rendered content (text or images)
                    PDFTextStripper textStripper = new PDFTextStripper();
                    String fullText = textStripper.getText(doc).trim();

                    boolean hasContent = !fullText.isEmpty();
                    if (!hasContent) {
                        for (PDPage p : doc.getPages()) {
                            if (p.getResources() != null && p.getResources().getXObjectNames() != null) {
                                hasContent = true;
                                break;
                            }
                        }
                    }

                    if (!hasContent) {
                        log.warn("Headless rendered blank PDF without text or images for doc {}. Triggering auto-fallback.", docId);
                        return null;
                    }

                    log.info("Headless render validated: {} pages rendered with rich content for doc {}", actualPages, docId);
                }
                return Files.readAllBytes(tempPdf);
            }
        } catch (Exception e) {
            log.error("Failed to render PDF via headless browser for doc {}: {}", docId, e.getMessage());
        } finally {
            if (tempHtml != null) {
                try { Files.deleteIfExists(tempHtml); } catch (IOException ignored) {}
            }
            if (tempPdf != null) {
                try { Files.deleteIfExists(tempPdf); } catch (IOException ignored) {}
            }
        }
        return null;
    }

    private String detectBrowserExecutable() {
        List<String> candidates = Arrays.asList(
                // Linux / Docker
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                // Windows
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"
        );

        for (String c : candidates) {
            if (new File(c).exists()) {
                return c;
            }
        }

        for (String name : Arrays.asList("chromium", "chromium-browser", "google-chrome", "chrome", "msedge", "chrome.exe", "msedge.exe")) {
            String path = findInPath(name);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private String findInPath(String binaryName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] dirs = pathEnv.split(File.pathSeparator);
        for (String dir : dirs) {
            File f = new File(dir, binaryName);
            if (f.exists() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }
}

package com.scribddl.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
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
            log.info("Headless browser detected and verified: {}", browserPath);
        } else {
            log.warn("No functional headless Chrome/Edge detected. Headless PDF generation fallback will be skipped.");
        }
    }

    public boolean isAvailable() {
        return browserPath != null;
    }

    public String getBrowserPath() {
        return browserPath;
    }

    public String testBrowserVersion() {
        if (browserPath == null) return "Browser not detected";
        try {
            Process process = new ProcessBuilder(browserPath, "--version").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append(" ");
                }
                process.waitFor(5, TimeUnit.SECONDS);
                return sb.toString().trim() + " (exitCode=" + process.exitValue() + ")";
            }
        } catch (Exception e) {
            return "Execution error: " + e.getMessage();
        }
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

            // Dynamic timeout scaling: minimum 45s, up to 120s
            long timeoutSeconds = Math.min(120, Math.max(45, (long) (expectedPageCount * 2.5)));
            long virtualBudgetMs = Math.min(35000, Math.max(15000, expectedPageCount * 1000));

            List<String> command = new ArrayList<>(Arrays.asList(
                    browserPath,
                    "--headless=new",
                    "--no-sandbox",
                    "--disable-setuid-sandbox",
                    "--disable-dev-shm-usage",
                    "--disable-gpu",
                    "--disable-software-rasterizer",
                    "--disable-extensions",
                    "--disable-background-networking",
                    "--disable-default-apps",
                    "--disable-sync",
                    "--disable-translate",
                    "--metrics-recording-only",
                    "--no-first-run",
                    "--disable-web-security",
                    "--allow-running-insecure-content",
                    "--allow-file-access-from-files",
                    "--run-all-compositor-stages-before-draw",
                    "--virtual-time-budget=" + virtualBudgetMs,
                    "--no-pdf-header-footer",
                    "--print-to-pdf=" + tempPdf.toAbsolutePath().toString(),
                    fileUrl
            ));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Asynchronously drain stdout/stderr so the OS pipe buffer never blocks the process
            StringBuilder logOutput = new StringBuilder();
            Thread drainThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (logOutput.length() < 8000) {
                            logOutput.append(line).append("\n");
                        }
                    }
                } catch (Exception ignored) {}
            });
            drainThread.setDaemon(true);
            drainThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Headless browser timed out after {} seconds for doc {}. Process logs: {}", timeoutSeconds, docId, logOutput);
                return null;
            }
            drainThread.join(1000);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("Headless browser exited with error code {} for doc {}. Logs: {}", exitCode, docId, logOutput);
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
                // Linux / Docker - prioritize official Google Chrome Stable
                "/usr/bin/google-chrome-stable",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                // Windows
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"
        );

        for (String c : candidates) {
            File f = new File(c);
            if (f.exists() && f.canExecute()) {
                if (verifyBinary(c)) {
                    return c;
                }
            }
        }

        for (String name : Arrays.asList("google-chrome-stable", "google-chrome", "chromium", "chromium-browser", "chrome", "msedge", "chrome.exe", "msedge.exe")) {
            String path = findInPath(name);
            if (path != null && verifyBinary(path)) {
                return path;
            }
        }
        return null;
    }

    private boolean verifyBinary(String binaryPath) {
        try {
            Process process = new ProcessBuilder(binaryPath, "--version").redirectErrorStream(true).start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                return true;
            } else {
                process.destroyForcibly();
                log.warn("Binary test failed for {} with exit code {}", binaryPath, finished ? process.exitValue() : "timeout");
                return false;
            }
        } catch (Exception e) {
            log.warn("Binary verification exception for {}: {}", binaryPath, e.getMessage());
            return false;
        }
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

package com.scribddl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scribddl.model.DocInfo;
import com.scribddl.model.PageItem;
import com.scribddl.model.ProgressInfo;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Service
public class ScribdService {
    private static final Logger log = LoggerFactory.getLogger(ScribdService.class);
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HeadlessBrowserService headlessBrowserService;
    private final HttpClient httpClient;
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(4);

    private final Map<String, DocInfo> metaCache = new ConcurrentHashMap<>();
    private final Map<String, ProgressInfo> progressTracker = new ConcurrentHashMap<>();

    @Value("${scribd.cache.dir:cache}")
    private String cacheDirPath;

    @Value("${scribd.default.cookie:}")
    private String defaultCookie;

    public ScribdService(HeadlessBrowserService headlessBrowserService) {
        this.headlessBrowserService = headlessBrowserService;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public ProgressInfo getProgress(String docId) {
        return progressTracker.getOrDefault(docId, new ProgressInfo("idle", 0, 1, 0, "Ready"));
    }

    public void updateProgress(String docId, String status, int current, int total, int percent, String message) {
        progressTracker.put(docId, new ProgressInfo(status, current, total, percent, message));
    }

    public String extractDocId(String url) {
        if (url == null || url.isBlank()) return null;
        Pattern p = Pattern.compile("/(?:document|doc|presentation|read|embeds)/(\\d+)");
        Matcher m = p.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public DocInfo extractDocInfo(String targetUrl, String cookieStr) throws Exception {
        String docId = extractDocId(targetUrl);
        if (docId == null) {
            throw new IllegalArgumentException("Invalid Scribd document URL format");
        }

        if ((cookieStr == null || cookieStr.isBlank()) && metaCache.containsKey(docId)) {
            return metaCache.get(docId);
        }

        String effectiveCookie = (cookieStr != null && !cookieStr.isBlank()) ? cookieStr : defaultCookie;

        String title = "Scribd_Document_" + docId;
        String authorName = "Scribd Author";
        String thumbnailUrl = "";

        // 1. Fetch oEmbed metadata
        try {
            String oembedUrl = "https://www.scribd.com/services/oembed?url=https://www.scribd.com/document/" + docId + "&format=json";
            HttpRequest req = buildRequest(oembedUrl, effectiveCookie, Duration.ofSeconds(8));
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                if (root.has("title")) title = root.get("title").asText(title);
                if (root.has("author_name")) authorName = root.get("author_name").asText(authorName);
                if (root.has("thumbnail_url")) thumbnailUrl = root.get("thumbnail_url").asText("");
            }
        } catch (Exception e) {
            log.warn("oEmbed fetch failed for doc {}: {}", docId, e.getMessage());
        }

        // 2. Comprehensive Multi-Endpoint Discovery (covers embeds, presentations, mobile, standard)
        List<String> urlsToTry = Arrays.asList(
                "https://www.scribd.com/embeds/" + docId + "/content",
                "https://www.scribd.com/document/" + docId,
                "https://www.scribd.com/presentation/" + docId,
                "https://www.scribd.com/doc/" + docId,
                "https://www.scribd.com/read/" + docId,
                "https://www.scribd.com/mobile/documents/" + docId
        );

        Map<Integer, String[]> pagesDict = new ConcurrentHashMap<>();
        Set<String> assetKeys = ConcurrentHashMap.newKeySet();
        int totalPageCount = 1;
        List<String> extractedLines = new CopyOnWriteArrayList<>();
        Map<Integer, List<String>> perPageTextMap = new ConcurrentHashMap<>();

        for (String fetchUrl : urlsToTry) {
            try {
                HttpRequest req = buildRequest(fetchUrl, effectiveCookie, Duration.ofSeconds(10));
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) continue;
                String html = resp.body();

                // Extract asset keys
                Matcher mAsset = Pattern.compile("html\\.scribd(?:assets)?\\.com/([a-zA-Z0-9]+)/").matcher(html);
                while (mAsset.find()) {
                    assetKeys.add(mAsset.group(1));
                }

                // Extract direct image matches: images/NUM-HASH.ext
                Matcher mImg = Pattern.compile("images/(\\d+)-([a-zA-Z0-9]+)\\.(jpg|png|webp|svg)").matcher(html);
                while (mImg.find()) {
                    int num = Integer.parseInt(mImg.group(1));
                    pagesDict.put(num, new String[]{mImg.group(2), mImg.group(3)});
                }

                // Extract contentUrls
                Matcher mContent = Pattern.compile("contentUrl:\\s*[\"'](https://html\\.scribd(?:assets)?\\.com/[^\"']+)[\"']").matcher(html);
                while (mContent.find()) {
                    String cu = mContent.group(1);
                    Matcher mCu = Pattern.compile("pages/(\\d+)-([a-zA-Z0-9]+)\\.jsonp").matcher(cu);
                    if (mCu.find()) {
                        int num = Integer.parseInt(mCu.group(1));
                        pagesDict.putIfAbsent(num, new String[]{mCu.group(2), "png"});
                    }
                }

                // Extract JSONP matches: pages/NUM-HASH.jsonp
                Matcher mJsonp = Pattern.compile("pages/(\\d+)-([a-zA-Z0-9]+)\\.jsonp").matcher(html);
                while (mJsonp.find()) {
                    int num = Integer.parseInt(mJsonp.group(1));
                    pagesDict.putIfAbsent(num, new String[]{mJsonp.group(2), "png"});
                }

                // Extract absimg matches
                Matcher mAbsImg = Pattern.compile("(?:orig|data-src|src)=[\"'](https?://html\\.scribd(?:assets)?\\.com/[^/]+/images/(\\d+)-([a-zA-Z0-9]+)\\.(jpg|png|webp))[\"']").matcher(html);
                while (mAbsImg.find()) {
                    int num = Integer.parseInt(mAbsImg.group(2));
                    pagesDict.put(num, new String[]{mAbsImg.group(3), mAbsImg.group(4)});
                }

                // Extract page counts
                Matcher mPc1 = Pattern.compile("page_count[\"']?\\s*:\\s*(\\d+)").matcher(html);
                while (mPc1.find()) totalPageCount = Math.max(totalPageCount, Integer.parseInt(mPc1.group(1)));

                Matcher mPc2 = Pattern.compile("pageCount[\"']?\\s*:\\s*(\\d+)").matcher(html);
                while (mPc2.find()) totalPageCount = Math.max(totalPageCount, Integer.parseInt(mPc2.group(1)));

                Matcher mPc3 = Pattern.compile("pageNum[\"']?\\s*:\\s*(\\d+)").matcher(html);
                while (mPc3.find()) totalPageCount = Math.max(totalPageCount, Integer.parseInt(mPc3.group(1)));

                // Extract per-page text layers
                for (int pNum = 1; pNum <= totalPageCount; pNum++) {
                    Pattern pText = Pattern.compile("id=[\"']page" + pNum + "[\"']([\\s\\S]*?)(?=<div[^>]+id=[\"']page\\d+[\"']|</body>|$)", Pattern.CASE_INSENSITIVE);
                    Matcher mTxt = pText.matcher(html);
                    if (mTxt.find()) {
                        String section = mTxt.group(1);
                        Matcher mSpans = Pattern.compile("<span[^>]*>(.*?)</span>").matcher(section);
                        List<String> pageLines = new ArrayList<>();
                        while (mSpans.find()) {
                            String text = mSpans.group(1).replaceAll("<[^>]+>", "").trim();
                            if (!text.isEmpty() && !text.toLowerCase().startsWith("scribd")) {
                                pageLines.add(text);
                            }
                        }
                        if (!pageLines.isEmpty()) {
                            perPageTextMap.put(pNum, pageLines);
                            for (String line : pageLines) {
                                if (!extractedLines.contains(line)) {
                                    extractedLines.add(line);
                                }
                            }
                        }
                    }
                }

                if (pagesDict.size() >= totalPageCount && pagesDict.size() > 1) {
                    break;
                }
            } catch (Exception e) {
                log.warn("Scraping endpoint {} failed for doc {}: {}", fetchUrl, docId, e.getMessage());
            }
        }

        String primaryAssetKey = assetKeys.isEmpty() ? null : assetKeys.iterator().next();
        List<PageItem> pageItems = new ArrayList<>();
        List<Integer> sortedKeys = new ArrayList<>(pagesDict.keySet());
        Collections.sort(sortedKeys);

        if (primaryAssetKey != null) {
            for (int pNum : sortedKeys) {
                String[] pair = pagesDict.get(pNum);
                String hash = pair[0];
                String ext = pair[1];
                String url = "https://html.scribdassets.com/" + primaryAssetKey + "/images/" + pNum + "-" + hash + "." + ext;
                pageItems.add(new PageItem(pNum, hash, ext, url));
            }
        }
        totalPageCount = Math.max(totalPageCount, pageItems.size());

        String safeTitle = title.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_").trim();
        if (safeTitle.isEmpty()) {
            safeTitle = "Document_" + docId;
        }

        List<List<String>> perPageTextList = new ArrayList<>();
        for (int i = 1; i <= totalPageCount; i++) {
            perPageTextList.add(perPageTextMap.getOrDefault(i, Collections.emptyList()));
        }

        DocInfo info = new DocInfo();
        info.setSuccess(true);
        info.setDocId(docId);
        info.setTitle(title);
        info.setSafeTitle(safeTitle);
        info.setAuthor(authorName);
        info.setThumbnailUrl(thumbnailUrl);
        info.setPageCount(Math.max(1, totalPageCount));
        info.setPageImages(pageItems);
        info.setAssetKey(primaryAssetKey);
        info.setExtractedLines(extractedLines.subList(0, Math.min(50, extractedLines.size())));
        info.setPerPageText(perPageTextList);
        info.setTargetUrl(targetUrl);
        info.setHasCookie(effectiveCookie != null && !effectiveCookie.isBlank());

        if (cookieStr == null || cookieStr.isBlank()) {
            metaCache.put(docId, info);
        }

        return info;
    }

    public File downloadDocument(String targetUrl, String format, String cookieStr) throws Exception {
        String docId = extractDocId(targetUrl);
        if (docId == null) {
            throw new IllegalArgumentException("Invalid Scribd document URL");
        }

        String fmt = (format != null ? format.trim().toLowerCase() : "pdf");
        String effectiveCookie = (cookieStr != null && !cookieStr.isBlank()) ? cookieStr : defaultCookie;

        Path cacheDir = Paths.get(cacheDirPath);
        Files.createDirectories(cacheDir);
        File cachedFile = cacheDir.resolve(docId + "_" + fmt + "." + fmt).toFile();

        DocInfo docInfo = extractDocInfo(targetUrl, cookieStr);
        int pageCount = docInfo.getPageCount();
        String safeTitle = docInfo.getSafeTitle();
        String title = docInfo.getTitle();

        // Fast Cache Hit Check (with validation to auto-invalidate stale/blank text runs)
        if ((cookieStr == null || cookieStr.isBlank()) && cachedFile.exists() && cachedFile.length() > 1000) {
            boolean validCache = true;
            if ("pdf".equals(fmt)) {
                try (PDDocument checkDoc = Loader.loadPDF(cachedFile)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    String txt = stripper.getText(checkDoc).trim();
                    // If cached PDF has 0 text while the document has extracted text, invalidate stale cache
                    if (txt.isEmpty() && docInfo.getExtractedLines() != null && !docInfo.getExtractedLines().isEmpty()) {
                        log.info("Invalidating stale/blank-text cached PDF for doc {}", docId);
                        validCache = false;
                        cachedFile.delete();
                    }
                } catch (Exception e) {
                    validCache = false;
                    cachedFile.delete();
                }
            }
            if (validCache) {
                updateProgress(docId, "complete", pageCount, pageCount, 100, "Instant cache hit (" + pageCount + " pages)!");
                return cachedFile;
            }
        }

        updateProgress(docId, "starting", 0, pageCount, 5, "Analyzing document structure (" + pageCount + " pages)...");

        // 1. Direct Official Scribd Session Download (if cookie provided)
        if (effectiveCookie != null && !effectiveCookie.isBlank()) {
            try {
                String directUrl = "https://www.scribd.com/document_downloads/" + docId + "?extension=" + fmt;
                HttpRequest req = buildRequest(directUrl, effectiveCookie, Duration.ofSeconds(12));
                HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
                byte[] raw = resp.body();
                if (raw != null && raw.length > 2000) {
                    Files.write(cachedFile.toPath(), raw);
                    updateProgress(docId, "complete", pageCount, pageCount, 100, "Downloaded via direct Scribd session!");
                    return cachedFile;
                }
            } catch (Exception e) {
                log.info("Direct session download fallback to asset compilation: {}", e.getMessage());
            }
        }

        // 2. TXT Format
        if ("txt".equals(fmt)) {
            StringBuilder sb = new StringBuilder();
            sb.append(title).append("\nAuthor: ").append(docInfo.getAuthor())
                    .append("\nID: ").append(docId)
                    .append("\nPages: ").append(pageCount).append("\n\n");
            for (String line : docInfo.getExtractedLines()) {
                sb.append(line).append("\n");
            }
            Files.writeString(cachedFile.toPath(), "\uFEFF" + sb.toString(), StandardCharsets.UTF_8);
            updateProgress(docId, "complete", pageCount, pageCount, 100, "TXT document ready!");
            return cachedFile;
        }

        // 3. DOCX Format (Word XML)
        if ("docx".equals(fmt)) {
            StringBuilder bodyHtml = new StringBuilder();
            List<List<String>> perPageText = docInfo.getPerPageText();
            for (int i = 0; i < perPageText.size(); i++) {
                bodyHtml.append("<h3>Page ").append(i + 1).append("</h3>\n");
                for (String line : perPageText.get(i)) {
                    bodyHtml.append("<p>").append(line).append("</p>\n");
                }
            }
            String docxXml = "<html xmlns:o='urn:schemas-microsoft-com:office:office' xmlns:w='urn:schemas-microsoft-com:office:word' xmlns='http://www.w3.org/TR/REC-html40'>\n" +
                    "<head><meta charset='utf-8'><title>" + title + "</title>\n" +
                    "<style>body{font-family:Calibri,sans-serif;font-size:11pt;line-height:1.5;margin:1in;} h1{color:#1e40af;}</style>\n" +
                    "</head>\n<body>\n" +
                    "<h1>" + title + "</h1>\n" +
                    "<p><strong>Author:</strong> " + docInfo.getAuthor() + " | <strong>Pages:</strong> " + pageCount + "</p>\n<hr>\n" +
                    bodyHtml +
                    "</body></html>";
            Files.writeString(cachedFile.toPath(), "\uFEFF" + docxXml, StandardCharsets.UTF_8);
            updateProgress(docId, "complete", pageCount, pageCount, 100, "Word document ready!");
            return cachedFile;
        }

        // 4. PDF TIER 1: Authentic Headless Browser Rendering (1:1 Vector PDF for presentations and books up to 250 pages)
        if (pageCount <= 250 && headlessBrowserService.isAvailable()) {
            updateProgress(docId, "rendering", pageCount, pageCount, 30, "Rendering 1:1 original layout (" + pageCount + " pages)...");
            String embedHtml = fetchAndPrepareEmbedHtml(docId, effectiveCookie, docInfo.getAssetKey());
            if (embedHtml != null) {
                byte[] pdfBytes = headlessBrowserService.renderHtmlToPdf(embedHtml, docId, pageCount);
                if (pdfBytes != null && pdfBytes.length > 2000) {
                    Files.write(cachedFile.toPath(), pdfBytes);
                    updateProgress(docId, "complete", pageCount, pageCount, 100, "Complete " + pageCount + "-page vector PDF ready!");
                    return cachedFile;
                }
            }
            log.info("Headless rendering unavailable or incomplete for doc {}. Seamlessly activating High-Res Image Stitcher.", docId);
        }

        // 5. PDF TIER 2: Fast Parallel Image Assembly & Disk-Buffered Streaming Stitcher
        List<PageItem> pageImages = docInfo.getPageImages();
        String assetKey = docInfo.getAssetKey();
        if (pageImages != null && !pageImages.isEmpty() && assetKey != null) {
            int totalImgs = pageImages.size();
            updateProgress(docId, "downloading", 0, totalImgs, 10, "Starting fast parallel download (0/" + totalImgs + ")...");

            Map<Integer, File> tempFileMap = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            AtomicInteger downloadedCount = new AtomicInteger(0);

            for (int i = 0; i < totalImgs; i++) {
                final int pageIdx = i;
                final PageItem item = pageImages.get(i);
                futures.add(CompletableFuture.runAsync(() -> {
                    File imgFile = downloadPageImageToFile(item, assetKey, effectiveCookie);
                    if (imgFile != null) {
                        tempFileMap.put(pageIdx, imgFile);
                    }
                    int done = downloadedCount.incrementAndGet();
                    if (done % 5 == 0 || done == totalImgs) {
                        int pct = 10 + (int) ((done / (double) totalImgs) * 75);
                        updateProgress(docId, "downloading", done, totalImgs, pct, "Downloaded " + done + "/" + totalImgs + " pages...");
                    }
                }, downloadExecutor));
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(65, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Parallel download completed with partial or timeout for doc {}: {}", docId, e.getMessage());
            }

            updateProgress(docId, "rendering", totalImgs, totalImgs, 85, "Assembling " + tempFileMap.size() + " pages into PDF...");

            try (PDDocument document = new PDDocument()) {
                for (int i = 0; i < totalImgs; i++) {
                    File imgFile = tempFileMap.remove(i);
                    if (imgFile != null) {
                        try {
                            BufferedImage img = ImageIO.read(imgFile);
                            if (img != null) {
                                int iw = img.getWidth();
                                int ih = img.getHeight();

                                if (ih > iw * 1.8) {
                                    int pageH = (int) (iw * 1.414);
                                    if (ih >= iw * 2.5) {
                                        int y = 0;
                                        while (y < ih) {
                                            int currentSliceHeight = Math.min(pageH, ih - y);
                                            BufferedImage slice = img.getSubimage(0, y, iw, currentSliceHeight);
                                            addImagePageToPdf(document, slice);
                                            slice.flush();
                                            y += pageH;
                                        }
                                    } else {
                                        int h2 = ih / 2;
                                        BufferedImage slice1 = img.getSubimage(0, 0, iw, h2);
                                        BufferedImage slice2 = img.getSubimage(0, h2, iw, ih - h2);
                                        addImagePageToPdf(document, slice1);
                                        addImagePageToPdf(document, slice2);
                                        slice1.flush();
                                        slice2.flush();
                                    }
                                } else {
                                    addImagePageToPdf(document, img);
                                }
                                img.flush();
                            }
                        } finally {
                            try { imgFile.delete(); } catch (Exception ignored) {}
                        }
                    }
                }

                if (document.getNumberOfPages() > 0) {
                    document.save(cachedFile);
                    updateProgress(docId, "complete", document.getNumberOfPages(), document.getNumberOfPages(), 100,
                            "Complete " + document.getNumberOfPages() + "-page PDF ready!");
                    return cachedFile;
                }
            } catch (Exception e) {
                log.error("Disk-buffered image assembly failed for doc {}: {}", docId, e.getMessage());
            }
        }

        // 6. PDF TIER 3: Text Layer Canvas Engine (Self-Healing Typography Fallback)
        List<List<String>> perPageText = docInfo.getPerPageText();
        updateProgress(docId, "assembling", pageCount, pageCount, 90, "Assembling text pages into PDF...");

        try (PDDocument document = new PDDocument()) {
            for (int pIdx = 1; pIdx <= pageCount; pIdx++) {
                List<String> lines = (pIdx - 1 < perPageText.size()) ? perPageText.get(pIdx - 1) : Collections.emptyList();
                BufferedImage canvas = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = canvas.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, 1240, 1754);

                g2d.setColor(new Color(30, 58, 138));
                g2d.fillRect(0, 0, 1240, 80);
                g2d.setColor(Color.WHITE);
                g2d.drawString(title + " • Page " + pIdx + " of " + pageCount, 40, 48);

                g2d.setColor(new Color(203, 213, 225));
                g2d.fillRect(40, 95, 1160, 2);

                g2d.setColor(new Color(15, 23, 42));
                int y = 135;
                for (String token : lines) {
                    if (y > 1650) break;
                    g2d.drawString(token, 60, y);
                    y += 30;
                }

                g2d.setColor(new Color(148, 163, 184));
                g2d.drawString("Exported from Scribd • Page " + pIdx + " of " + pageCount, 60, 1680);
                g2d.dispose();

                addImagePageToPdf(document, canvas);
            }

            document.save(cachedFile);
            updateProgress(docId, "complete", pageCount, pageCount, 100, "Complete " + pageCount + "-page PDF ready!");
            return cachedFile;
        }
    }

    private File downloadPageImageToFile(PageItem item, String assetKey, String cookie) {
        List<String> extsToTry = new ArrayList<>(Arrays.asList("jpg", "png", "webp"));
        if (item.getPrimaryExt() != null && !item.getPrimaryExt().isBlank() && !extsToTry.contains(item.getPrimaryExt())) {
            extsToTry.add(1, item.getPrimaryExt());
        }

        List<String> domains = Arrays.asList("html.scribdassets.com", "html.scribd.com");

        for (String domain : domains) {
            for (String ext : extsToTry) {
                String imgUrl = "https://" + domain + "/" + assetKey + "/images/" + item.getPage() + "-" + item.getHash() + "." + ext;
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        HttpRequest req = buildRequest(imgUrl, cookie, Duration.ofSeconds(10));
                        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                        if (resp.statusCode() == 200) {
                            File tempFile = File.createTempFile("sc_p" + item.getPage() + "_", "." + ext);
                            tempFile.deleteOnExit();
                            try (InputStream in = resp.body(); FileOutputStream out = new FileOutputStream(tempFile)) {
                                in.transferTo(out);
                            }
                            if (tempFile.length() > 500) {
                                return tempFile;
                            } else {
                                tempFile.delete();
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private BufferedImage downloadPageImage(PageItem item, String assetKey, String cookie) {
        String primaryExt = (item.getPrimaryExt() != null ? item.getPrimaryExt() : "png");
        List<String> extsToTry = new ArrayList<>();
        extsToTry.add(primaryExt);
        for (String e : Arrays.asList("png", "jpg", "webp")) {
            if (!extsToTry.contains(e)) extsToTry.add(e);
        }

        List<String> domains = Arrays.asList("html.scribdassets.com", "html.scribd.com");

        for (String domain : domains) {
            for (String ext : extsToTry) {
                String imgUrl = "https://" + domain + "/" + assetKey + "/images/" + item.getPage() + "-" + item.getHash() + "." + ext;
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        HttpRequest req = buildRequest(imgUrl, cookie, Duration.ofSeconds(9));
                        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                        if (resp.statusCode() == 200) {
                            try (InputStream in = resp.body()) {
                                BufferedImage bimg = ImageIO.read(in);
                                if (bimg != null && bimg.getWidth() > 10) {
                                    return bimg;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private void addImagePageToPdf(PDDocument document, BufferedImage bufferedImage) throws IOException {
        float width = bufferedImage.getWidth();
        float height = bufferedImage.getHeight();
        PDRectangle pageSize = new PDRectangle(width, height);
        PDPage page = new PDPage(pageSize);
        document.addPage(page);

        // Normalize ARGB/RGBA to solid RGB with white background
        BufferedImage rgbImage;
        if (bufferedImage.getType() != BufferedImage.TYPE_INT_RGB) {
            rgbImage = new BufferedImage(bufferedImage.getWidth(), bufferedImage.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgbImage.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, bufferedImage.getWidth(), bufferedImage.getHeight());
            g.drawImage(bufferedImage, 0, 0, null);
            g.dispose();
        } else {
            rgbImage = bufferedImage;
        }

        PDImageXObject pdImage = JPEGFactory.createFromImage(document, rgbImage, 0.92f);

        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
            contentStream.drawImage(pdImage, 0, 0, width, height);
        }
    }

    public String fetchAndPrepareEmbedHtml(String docId, String cookie, String initialAssetKey) {
        try {
            String embedUrl = "https://www.scribd.com/embeds/" + docId + "/content";
            HttpRequest req = buildRequest(embedUrl, cookie, Duration.ofSeconds(12));
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return null;

            String html = resp.body();
            if (!html.contains("outer_page") && !html.contains("document_scroller")) {
                return null;
            }

            // Discover assetKey
            String assetKey = initialAssetKey;
            if (assetKey == null || assetKey.isBlank()) {
                Matcher mAk = Pattern.compile("html\\.scribd(?:assets)?\\.com/([a-zA-Z0-9]+)/").matcher(html);
                if (mAk.find()) {
                    assetKey = mAk.group(1);
                }
            }

            // Discover and fetch all lazy-loaded JSONP page chunks
            List<String[]> jsonpMatches = new ArrayList<>();
            Matcher mJsonp = Pattern.compile("pages/(\\d+)-([a-zA-Z0-9]+)\\.jsonp").matcher(html);
            while (mJsonp.find()) {
                jsonpMatches.add(new String[]{mJsonp.group(1), mJsonp.group(2)});
            }

            if (assetKey != null && !jsonpMatches.isEmpty()) {
                final String finalAssetKey = assetKey;
                Map<Integer, String> jsonpPageContents = new ConcurrentHashMap<>();
                List<CompletableFuture<Void>> futs = new ArrayList<>();

                for (String[] pair : jsonpMatches) {
                    final int pageNum = Integer.parseInt(pair[0]);
                    final String hashVal = pair[1];

                    CompletableFuture<Void> cf = CompletableFuture.runAsync(() -> {
                        for (String domain : Arrays.asList("html.scribdassets.com", "html.scribd.com")) {
                            for (int attempt = 0; attempt < 2; attempt++) {
                                try {
                                    String u = "https://" + domain + "/" + finalAssetKey + "/pages/" + pageNum + "-" + hashVal + ".jsonp";
                                    HttpRequest jreq = buildRequest(u, cookie, Duration.ofSeconds(9));
                                    HttpResponse<byte[]> jresp = httpClient.send(jreq, HttpResponse.BodyHandlers.ofByteArray());
                                    if (jresp.statusCode() == 200) {
                                        byte[] raw = jresp.body();
                                        String dec;
                                        if (raw.length > 2 && (raw[0] & 0xFF) == 0x1f && (raw[1] & 0xFF) == 0x8b) {
                                            try (GZIPInputStream gzin = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                                                dec = new String(gzin.readAllBytes(), StandardCharsets.UTF_8);
                                            }
                                        } else {
                                            dec = new String(raw, StandardCharsets.UTF_8);
                                        }

                                        Matcher cbMatcher = Pattern.compile("window\\.page\\d+_callback\\(\\s*(\\[[\\s\\S]*\\])\\s*\\)").matcher(dec);
                                        if (cbMatcher.find()) {
                                            JsonNode arr = objectMapper.readTree(cbMatcher.group(1));
                                            if (arr.isArray() && arr.size() > 0) {
                                                jsonpPageContents.put(pageNum, arr.get(0).asText());
                                                return;
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }, downloadExecutor);
                    futs.add(cf);
                }

                CompletableFuture.allOf(futs.toArray(new CompletableFuture[0])).join();

                // Inject fetched page chunks into outer_page divs
                for (Map.Entry<Integer, String> entry : jsonpPageContents.entrySet()) {
                    int pn = entry.getKey();
                    String pContent = entry.getValue();
                    String outerTarget = "id=\"outer_page_" + pn + "\"";

                    if (html.contains(outerTarget)) {
                        int idx = html.indexOf(outerTarget);
                        int tagEnd = html.indexOf(">", idx);
                        if (tagEnd != -1) {
                            html = html.substring(0, tagEnd + 1) + "\n" + pContent + "\n" + html.substring(tagEnd + 1);
                        }
                    } else {
                        String newOuter = "<div class=\"outer_page only_ie6_border\" id=\"outer_page_" + pn + "\" style=\"width:902px; height:1167px;\">\n" + pContent + "\n</div>\n";
                        int scrollerIdx = html.indexOf("class=\"document_scroller");
                        if (scrollerIdx != -1) {
                            int tagEnd = html.indexOf(">", scrollerIdx);
                            html = html.substring(0, tagEnd + 1) + "\n" + newOuter + html.substring(tagEnd + 1);
                        }
                    }
                }
            }

            // Universal Asset Normalization across the ENTIRE merged document
            html = html.replace("http://html.scribd.com", "https://html.scribdassets.com");
            html = html.replace("http://html.scribdassets.com", "https://html.scribdassets.com");
            html = html.replaceAll("orig=[\"'](https?://[^\"']+)[\"']", "src=\"$1\" orig=\"$1\"");
            html = html.replaceAll("data-src=[\"'](https?://[^\"']+)[\"']", "src=\"$1\" data-src=\"$1\"");
            html = html.replaceAll("opacity:\\s*0\\.01", "opacity: 1");
            html = html.replaceAll("opacity:0\\.01", "opacity: 1");

            // Discover and declare only the embedded Scribd fonts ACTUALLY used in the document
            Set<Integer> allFontIds = new TreeSet<>();
            Matcher ffMatcher = Pattern.compile("\\bff(\\d+)\\b").matcher(html);
            while (ffMatcher.find()) {
                allFontIds.add(Integer.parseInt(ffMatcher.group(1)));
            }

            StringBuilder fontCss = new StringBuilder();
            if (assetKey != null && !allFontIds.isEmpty()) {
                for (int fid : allFontIds) {
                    String fCode = String.format("%04d", fid);
                    String fontUrl = "https://html.scribdassets.com/" + assetKey + "/fonts/" + fCode + ".woff2";
                    fontCss.append("@font-face {\n")
                            .append("    font-family: 'ff").append(fid).append("';\n")
                            .append("    src: url('").append(fontUrl).append("') format('woff2');\n")
                            .append("    font-display: swap;\n")
                            .append("}\n")
                            .append(".ff").append(fid).append(", div.ff").append(fid).append(", span.ff").append(fid).append(" {\n")
                            .append("    font-family: 'ff").append(fid).append("' !important;\n")
                            .append("}\n");
                }
            }

            html = html.replaceAll("<script[\\s\\S]*?</script>", "");
            html = html.replaceAll("<iframe[\\s\\S]*?</iframe>", "");
            html = html.replaceAll("<iframe[^>]*>", "");
            html = html.replaceAll("<noscript[\\s\\S]*?</noscript>", "");
            html = html.replaceAll("<link\\s+rel=[\"']stylesheet[\"'][^>]*>", "");

            // Auto-detect native document dimensions
            int nativeW = 902;
            int nativeH = 1167;
            Matcher dimMatcher = Pattern.compile("id=[\"']outer_page_1[\"'][^>]*style=[\"'][^\"']*width:\\s*(\\d+)px;\\s*height:\\s*(\\d+)px").matcher(html);
            if (!dimMatcher.find()) {
                dimMatcher = Pattern.compile("origWidth[:\\s]+(\\d+)[\\s,]+origHeight[:\\s]+(\\d+)").matcher(html);
            }
            if (!dimMatcher.find()) {
                dimMatcher = Pattern.compile("width:\\s*(\\d+)px;\\s*height:\\s*(\\d+)px").matcher(html);
            }
            if (dimMatcher.find()) {
                nativeW = Integer.parseInt(dimMatcher.group(1));
                nativeH = Integer.parseInt(dimMatcher.group(2));
            }

            int docW = nativeW;
            int docH = nativeH;
            if (nativeH > nativeW * 2.0 || nativeH < 100 || nativeW < 100) {
                docW = 794;
                docH = 1122;
            }

            String printRules = "<style>\n" +
                    fontCss.toString() + "\n" +
                    "@page { size: " + docW + "px " + docH + "px; margin: 0; }\n" +
                    "html, body { margin: 0 !important; padding: 0 !important; background: #fff !important; width: 100% !important; }\n" +
                    "header, footer, nav, .toolbar, .toolbar_container, .document_toolbar, \n" +
                    ".embed_header, .mobile_header, .bottom_bar, .action_bar, .share_btn, \n" +
                    ".between_page_ads, .page_missing_explanation_view, .upgrade_button, \n" +
                    ".page_blur, .blur, .promo_wrapper, .mobile_overlay, .toolbar_drop { \n" +
                    "    display: none !important; \n" +
                    "    visibility: hidden !important; \n" +
                    "}\n" +
                    ".auto__embeds_new_show, .document_scroller, .document_container, .outer_page_container { \n" +
                    "    height: auto !important; \n" +
                    "    max-height: none !important; \n" +
                    "    overflow: visible !important; \n" +
                    "    position: static !important; \n" +
                    "    display: block !important; \n" +
                    "    margin: 0 !important; \n" +
                    "    padding: 0 !important; \n" +
                    "}\n" +
                    ".outer_page { \n" +
                    "    page-break-after: always !important; \n" +
                    "    break-after: page !important; \n" +
                    "    page-break-inside: avoid !important; \n" +
                    "    break-inside: avoid !important; \n" +
                    "    display: block !important; \n" +
                    "    position: relative !important; \n" +
                    "    width: " + docW + "px !important; \n" +
                    "    height: " + docH + "px !important; \n" +
                    "    margin: 0 auto !important; \n" +
                    "    overflow: hidden !important; \n" +
                    "    contain: none !important; \n" +
                    "    border: none !important; \n" +
                    "}\n" +
                    ".outer_page:last-child, .outer_page:last-of-type { \n" +
                    "    page-break-after: auto !important; \n" +
                    "    break-after: auto !important; \n" +
                    "}\n" +
                    ".newpage { \n" +
                    "    position: absolute !important; \n" +
                    "    left: 0 !important; \n" +
                    "    top: 0 !important; \n" +
                    "    width: " + docW + "px !important; \n" +
                    "    height: " + docH + "px !important; \n" +
                    "    display: block !important; \n" +
                    "    transform: none !important; \n" +
                    "}\n" +
                    ".text_layer { \n" +
                    "    position: absolute !important; \n" +
                    "    left: 0 !important; \n" +
                    "    top: 0 !important; \n" +
                    "    width: " + docW + "px !important; \n" +
                    "    height: " + docH + "px !important; \n" +
                    "    z-index: 2 !important; \n" +
                    "    transform: none !important; \n" +
                    "    zoom: 0.2 !important; \n" +
                    "}\n" +
                    ".text_layer div, .text_layer span { \n" +
                    "    white-space: nowrap !important; \n" +
                    "    line-height: 1 !important; \n" +
                    "    opacity: 1 !important; \n" +
                    "}\n" +
                    ".text_layer span.a, .text_layer span.g { \n" +
                    "    position: absolute !important; \n" +
                    "}\n" +
                    ".absimg { position: absolute !important; display: block !important; z-index: 1 !important; }\n" +
                    ".outer_page.blurred_page { filter: none !important; }\n" +
                    ".outer_page.blurred_page .text_layer { text-shadow: none !important; }\n" +
                    "</style>\n";

            if (html.contains("</head>")) {
                html = html.replace("</head>", printRules + "</head>");
            } else {
                html = printRules + html;
            }
            return html;
        } catch (Exception e) {
            log.warn("Failed to prepare embed HTML for doc {}: {}", docId, e.getMessage());
            return null;
        }
    }

    private HttpRequest buildRequest(String url, String cookie, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "*/*")
                .timeout(timeout);

        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        return builder.build();
    }
}

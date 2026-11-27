package com.scribddl.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

public class DocInfo {
    @JsonProperty("success")
    private boolean success;

    @JsonProperty("docId")
    private String docId;

    @JsonProperty("title")
    private String title;

    @JsonProperty("safeTitle")
    private String safeTitle;

    @JsonProperty("author")
    private String author;

    @JsonProperty("thumbnailUrl")
    private String thumbnailUrl;

    @JsonProperty("pageCount")
    private int pageCount;

    @JsonProperty("pageImages")
    private List<PageItem> pageImages = new ArrayList<>();

    @JsonProperty("assetKey")
    private String assetKey;

    @JsonProperty("extractedLines")
    private List<String> extractedLines = new ArrayList<>();

    @JsonProperty("perPageText")
    private List<List<String>> perPageText = new ArrayList<>();

    @JsonProperty("targetUrl")
    private String targetUrl;

    @JsonProperty("hasCookie")
    private boolean hasCookie;

    public DocInfo() {}

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSafeTitle() {
        return safeTitle;
    }

    public void setSafeTitle(String safeTitle) {
        this.safeTitle = safeTitle;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public int getPageCount() {
        return pageCount;
    }

    public void setPageCount(int pageCount) {
        this.pageCount = pageCount;
    }

    public List<PageItem> getPageImages() {
        return pageImages;
    }

    public void setPageImages(List<PageItem> pageImages) {
        this.pageImages = pageImages;
    }

    public String getAssetKey() {
        return assetKey;
    }

    public void setAssetKey(String assetKey) {
        this.assetKey = assetKey;
    }

    public List<String> getExtractedLines() {
        return extractedLines;
    }

    public void setExtractedLines(List<String> extractedLines) {
        this.extractedLines = extractedLines;
    }

    public List<List<String>> getPerPageText() {
        return perPageText;
    }

    public void setPerPageText(List<List<String>> perPageText) {
        this.perPageText = perPageText;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public boolean isHasCookie() {
        return hasCookie;
    }

    public void setHasCookie(boolean hasCookie) {
        this.hasCookie = hasCookie;
    }
}

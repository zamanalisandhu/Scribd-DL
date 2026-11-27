package com.scribddl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PageItem {
    @JsonProperty("page")
    private int page;

    @JsonProperty("hash")
    private String hash;

    @JsonProperty("primaryExt")
    private String primaryExt;

    @JsonProperty("url")
    private String url;

    public PageItem() {}

    public PageItem(int page, String hash, String primaryExt, String url) {
        this.page = page;
        this.hash = hash;
        this.primaryExt = primaryExt;
        this.url = url;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public String getPrimaryExt() {
        return primaryExt;
    }

    public void setPrimaryExt(String primaryExt) {
        this.primaryExt = primaryExt;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}

package com.scribddl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ProgressInfo {
    @JsonProperty("status")
    private String status;

    @JsonProperty("current")
    private int current;

    @JsonProperty("total")
    private int total;

    @JsonProperty("percent")
    private int percent;

    @JsonProperty("message")
    private String message;

    public ProgressInfo() {
        this.status = "idle";
        this.current = 0;
        this.total = 1;
        this.percent = 0;
        this.message = "Ready";
    }

    public ProgressInfo(String status, int current, int total, int percent, String message) {
        this.status = status;
        this.current = current;
        this.total = total;
        this.percent = percent;
        this.message = message;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getCurrent() {
        return current;
    }

    public void setCurrent(int current) {
        this.current = current;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPercent() {
        return percent;
    }

    public void setPercent(int percent) {
        this.percent = percent;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

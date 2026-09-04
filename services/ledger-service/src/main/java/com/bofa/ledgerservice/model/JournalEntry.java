package com.bofa.ledgerservice.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.List;

public class JournalEntry {

    private String id;

    @NotBlank
    private String description;

    @NotEmpty
    @Valid
    private List<JournalLine> lines;

    private Instant postedAt;
    private boolean posted;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<JournalLine> getLines() {
        return lines;
    }

    public void setLines(List<JournalLine> lines) {
        this.lines = lines;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(Instant postedAt) {
        this.postedAt = postedAt;
    }

    public boolean isPosted() {
        return posted;
    }

    public void setPosted(boolean posted) {
        this.posted = posted;
    }
}

package com.rag.how_to_cook.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_version")
public class DocumentVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne
    @JoinColumn(name = "document_info_id", nullable = false)
    private DocumentInfo documentInfo;

    @Column(nullable = false)
    private String contentHash;

    private int versionNumber;

    private boolean active;

    private LocalDateTime createdAt;

    @Column(columnDefinition = "TEXT")
    private String chunkIds;

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getId() {
        return id;
    }

    public DocumentInfo getDocumentInfo() {
        return documentInfo;
    }

    public boolean isActive() {
        return active;
    }

    public String getChunkIds() {
        return chunkIds;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setChunkIds(String chunkIds) {
        this.chunkIds = chunkIds;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public void setDocumentInfo(DocumentInfo documentInfo) {
        this.documentInfo = documentInfo;
    }
}

package com.rag.how_to_cook.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "document_info")
public class DocumentInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(unique = true, nullable = false)
    private String sourceUrl;

    private String dishName;

    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "documentInfo", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<DocumentVersion> versions;

    public void addVersion(DocumentVersion version) {
        if (versions == null) {
            this.versions = new ArrayList<>();
        }
        this.versions.add(version);
        version.setDocumentInfo(this);
    }
}

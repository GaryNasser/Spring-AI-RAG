package com.rag.how_to_cook.repo;

import com.rag.how_to_cook.domain.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, String> {

    Optional<DocumentVersion> findFirstByDocumentInfoIdAndActiveTrueOrderByVersionNumberDesc(String s);

    long countByDocumentInfoId(String documentInfoId);
}

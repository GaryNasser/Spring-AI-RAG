package com.rag.how_to_cook.repo;

import com.rag.how_to_cook.domain.DocumentInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentInfoRepository extends JpaRepository<DocumentInfo, String> {
    Optional<DocumentInfo> findBySourceUrl(String sourceUrl);
}

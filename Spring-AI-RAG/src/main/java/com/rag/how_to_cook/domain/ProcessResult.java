package com.rag.how_to_cook.domain;

import org.springframework.ai.document.Document;

import java.util.List;

public record ProcessResult(List<Document> newChunks, List<String> idsToDelete) {}

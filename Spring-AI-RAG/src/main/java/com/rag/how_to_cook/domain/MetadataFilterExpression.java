package com.rag.how_to_cook.domain;

import java.util.List;

public record MetadataFilterExpression(List<String> difficulties, List<String> categories) {}

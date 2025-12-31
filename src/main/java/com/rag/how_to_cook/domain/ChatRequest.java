package com.rag.how_to_cook.domain;

import javax.validation.constraints.NotNull;
import java.util.Optional;

public record ChatRequest(Optional<String> chatId, @NotNull String prompt) {};

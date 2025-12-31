package com.rag.how_to_cook.repo;

import com.rag.how_to_cook.domain.Chat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatRepository extends JpaRepository<Chat, String> {
    List<Chat> findByUser_UsernameOrderByCreatedAtDesc(String userId);
}

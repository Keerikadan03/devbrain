package com.devbrain.repository;

import com.devbrain.model.entity.KnowledgeNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface KnowledgeNodeRepository extends JpaRepository<KnowledgeNode, UUID> {

    List<KnowledgeNode> findByTitleContainingIgnoreCase(String keyword);

    List<KnowledgeNode> findBySource(String source);

    List<KnowledgeNode> findAllByOrderByCreatedAtDesc();
}

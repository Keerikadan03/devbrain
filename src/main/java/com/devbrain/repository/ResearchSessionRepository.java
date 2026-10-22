package com.devbrain.repository;

import com.devbrain.model.entity.ResearchSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ResearchSessionRepository extends JpaRepository<ResearchSession, UUID> {

    List<ResearchSession> findByPhase(ResearchSession.ResearchPhase phase);

    List<ResearchSession> findAllByOrderByCreatedAtDesc();
}

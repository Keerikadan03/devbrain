package com.devbrain.controller;

import com.devbrain.agent.research.ResearchOrchestrator;
import com.devbrain.model.dto.ResearchRequest;
import com.devbrain.model.entity.ResearchSession;
import com.devbrain.repository.ResearchSessionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for multi-agent research sessions.
 * Start a research query, track progress, and retrieve reports.
 */
@RestController
@RequestMapping("/api/research")
@CrossOrigin(origins = "*")
public class ResearchController {

    private final ResearchOrchestrator researchOrchestrator;
    private final ResearchSessionRepository sessionRepository;

    public ResearchController(ResearchOrchestrator researchOrchestrator,
                              ResearchSessionRepository sessionRepository) {
        this.researchOrchestrator = researchOrchestrator;
        this.sessionRepository = sessionRepository;
    }

    /**
     * POST /api/research/start
     * Starts a new multi-agent research session.
     * Returns immediately; progress sent via WebSocket /topic/research/{sessionId}.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startResearch(@RequestBody ResearchRequest request) {
        ResearchSession session = ResearchSession.builder()
                .query(request.getQuery())
                .phase(ResearchSession.ResearchPhase.PLANNING)
                .build();
        session = sessionRepository.save(session);

        // Kick off async research pipeline
        researchOrchestrator.startResearch(session.getId());

        return ResponseEntity.accepted().body(Map.of(
                "sessionId", session.getId().toString(),
                "status", "started",
                "message", "Research session started. Connect to WebSocket /topic/research/" +
                        session.getId() + " for live updates."
        ));
    }

    /**
     * GET /api/research/{sessionId}
     * Returns the current state of a research session.
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<ResearchSession> getSession(@PathVariable UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/research/{sessionId}/report
     * Returns just the final report (markdown) if research is complete.
     */
    @GetMapping("/{sessionId}/report")
    public ResponseEntity<Map<String, Object>> getReport(@PathVariable UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .map(session -> {
                    if (session.getPhase() != ResearchSession.ResearchPhase.COMPLETED) {
                        return ResponseEntity.ok(Map.<String, Object>of(
                                "status", session.getPhase().name(),
                                "message", "Research not yet complete"
                        ));
                    }
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "status", "COMPLETED",
                            "report", session.getReport() != null ? session.getReport() : ""
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/research/sessions
     * Returns all research sessions, most recent first.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<ResearchSession>> getAllSessions() {
        return ResponseEntity.ok(sessionRepository.findAllByOrderByCreatedAtDesc());
    }
}

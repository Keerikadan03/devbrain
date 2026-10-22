package com.devbrain.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks a multi-agent research session.
 * Maps to Apollo's research workflow: plan → research → analyze → synthesize.
 */
@Entity
@Table(name = "research_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResearchSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String query; // original user question

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResearchPhase phase;

    @Column(columnDefinition = "TEXT")
    private String plan; // JSON: list of research questions

    @Column(columnDefinition = "TEXT")
    private String findings; // JSON: accumulated research findings

    @Column(columnDefinition = "TEXT")
    private String report; // final synthesized report (markdown)

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (phase == null) {
            phase = ResearchPhase.PLANNING;
        }
    }

    public enum ResearchPhase {
        PLANNING,      // creating research plan
        RESEARCHING,   // agents searching & gathering
        ANALYZING,     // self-reflective RAG, gap analysis
        SYNTHESIZING,  // generating final report
        COMPLETED,     // done
        FAILED         // error state
    }
}

package com.devbrain.agent.research;

import lombok.*;

import java.util.*;

/**
 * Shared state for the research agent group.
 * Equivalent to Apollo's state machine — tracks progress across agent interactions.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResearchState {

    private String sessionId;
    private String originalQuery;

    // Planning
    private String understanding;
    @Builder.Default
    private List<ResearchQuestion> questions = new ArrayList<>();

    // Research progress
    @Builder.Default
    private Map<Integer, String> findings = new HashMap<>(); // questionId -> findings
    @Builder.Default
    private int currentQuestionIndex = 0;

    // Analysis
    private double completenessScore;
    @Builder.Default
    private List<String> gaps = new ArrayList<>();
    @Builder.Default
    private List<String> proposedToc = new ArrayList<>();
    @Builder.Default
    private int analysisRound = 0;
    private static final int MAX_ANALYSIS_ROUNDS = 2;

    // Synthesis
    private String finalReport;

    public boolean hasMoreQuestions() {
        return currentQuestionIndex < questions.size();
    }

    public ResearchQuestion getCurrentQuestion() {
        if (!hasMoreQuestions()) return null;
        return questions.get(currentQuestionIndex);
    }

    public void advanceToNextQuestion() {
        currentQuestionIndex++;
    }

    public boolean canDoMoreAnalysis() {
        return analysisRound < MAX_ANALYSIS_ROUNDS;
    }

    public String getAccumulatedFindings() {
        StringBuilder sb = new StringBuilder();
        for (var entry : findings.entrySet()) {
            sb.append("## Question ").append(entry.getKey()).append("\n");
            sb.append(entry.getValue()).append("\n\n");
        }
        return sb.toString();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ResearchQuestion {
        private int id;
        private String question;
        private String sourceHints;
        private boolean completed;
    }
}

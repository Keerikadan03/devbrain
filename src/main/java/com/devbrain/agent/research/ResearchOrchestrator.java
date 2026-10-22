package com.devbrain.agent.research;

import com.devbrain.util.JsonText;

import com.devbrain.model.entity.ResearchSession;
import com.devbrain.model.entity.ResearchSession.ResearchPhase;
import com.devbrain.repository.ResearchSessionRepository;
import com.devbrain.service.KnowledgeService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.devbrain.util.Prompts.*;

/**
 * Orchestrates the multi-agent research workflow.
 * Ported from Apollo's ResearchOrchestrator + ResearchProcessor.
 *
 * Flow: Plan → Research (per question) → Analyze → (optional loop) → Synthesize
 * Each step uses a specialized prompt (agent role) via the same LLM.
 */
@Slf4j
@Service
public class ResearchOrchestrator {

    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final ResearchSessionRepository sessionRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public ResearchOrchestrator(
            ChatModel chatModel,
            KnowledgeService knowledgeService,
            ResearchSessionRepository sessionRepository,
            SimpMessagingTemplate messagingTemplate,
            ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
        this.sessionRepository = sessionRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Kicks off an async research session.
     * Runs the full pipeline: plan → research → analyze → synthesize.
     */
    @Async
    public void startResearch(UUID sessionId) {
        ResearchSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        ResearchState state = ResearchState.builder()
                .sessionId(sessionId.toString())
                .originalQuery(session.getQuery())
                .build();

        try {
            // Phase 1: Planning
            sendUpdate(sessionId, "planning", "Creating research plan...");
            plan(state);
            session.setPhase(ResearchPhase.RESEARCHING);
            session.setPlan(objectMapper.writeValueAsString(state.getQuestions()));
            sessionRepository.save(session);

            // Phase 2: Research each question
            sendUpdate(sessionId, "researching", "Researching " + state.getQuestions().size() + " questions...");
            research(state);
            session.setFindings(state.getAccumulatedFindings());
            sessionRepository.save(session);

            // Phase 3: Analyze
            sendUpdate(sessionId, "analyzing", "Analyzing findings...");
            session.setPhase(ResearchPhase.ANALYZING);
            sessionRepository.save(session);
            analyze(state);

            // Phase 3b: Follow-up research if gaps found
            while (!state.getGaps().isEmpty() && state.canDoMoreAnalysis()) {
                sendUpdate(sessionId, "researching", "Addressing " + state.getGaps().size() + " gaps (round " + (state.getAnalysisRound()) + ")...");
                research(state);
                analyze(state);
            }

            session.setFindings(state.getAccumulatedFindings());
            sessionRepository.save(session);

            // Phase 4: Synthesize
            sendUpdate(sessionId, "synthesizing", "Writing report...");
            session.setPhase(ResearchPhase.SYNTHESIZING);
            sessionRepository.save(session);
            synthesize(state);

            // Done
            session.setReport(state.getFinalReport());
            session.setPhase(ResearchPhase.COMPLETED);
            session.setCompletedAt(Instant.now());
            sessionRepository.save(session);
            sendUpdate(sessionId, "completed", "Research complete!");

        } catch (Exception e) {
            log.error("[ResearchOrchestrator] Research failed for session {}", sessionId, e);
            session.setPhase(ResearchPhase.FAILED);
            sessionRepository.save(session);
            sendUpdate(sessionId, "failed", "Research failed: " + e.getMessage());
        }
    }

    /**
     * Phase 1: Use Research Planner agent to create questions.
     */
    private void plan(ResearchState state) throws Exception {
        String prompt = RESEARCH_PLANNER + "\n\nUser's question: " + state.getOriginalQuery();
        String response = chatModel.chat(prompt);

        Map<String, Object> planResult = objectMapper.readValue(JsonText.stripFences(response), new TypeReference<>() {});
        state.setUnderstanding((String) planResult.get("understanding"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions = (List<Map<String, Object>>) planResult.get("questions");

        List<ResearchState.ResearchQuestion> researchQuestions = new ArrayList<>();
        for (Map<String, Object> q : questions) {
            researchQuestions.add(ResearchState.ResearchQuestion.builder()
                    .id(((Number) q.get("id")).intValue())
                    .question((String) q.get("question"))
                    .sourceHints((String) q.get("source_hints"))
                    .build());
        }
        state.setQuestions(researchQuestions);
        log.info("[Plan] Created {} research questions", researchQuestions.size());
    }

    /**
     * Phase 2: Research Engine processes each question against the knowledge base.
     */
    private void research(ResearchState state) {
        while (state.hasMoreQuestions()) {
            ResearchState.ResearchQuestion q = state.getCurrentQuestion();
            if (q.isCompleted()) {
                state.advanceToNextQuestion();
                continue;
            }

            log.info("[Research] Processing Q{}: {}", q.getId(), q.getQuestion());
            sendUpdate(UUID.fromString(state.getSessionId()), "researching",
                    "Researching: " + q.getQuestion());

            // Search knowledge base for relevant context
            List<EmbeddingMatch<TextSegment>> matches = knowledgeService.search(q.getQuestion(), 5);
            String context = matches.stream()
                    .map(m -> m.embedded().text())
                    .collect(Collectors.joining("\n\n---\n\n"));

            String prompt = RESEARCH_ENGINE
                    + "\n\nQuestion: " + q.getQuestion()
                    + "\n\nKnowledge base context:\n" + (context.isEmpty() ? "(no relevant context found)" : context);

            try {
                String response = chatModel.chat(prompt);
                Map<String, Object> result = objectMapper.readValue(JsonText.stripFences(response), new TypeReference<>() {});
                state.getFindings().put(q.getId(), (String) result.get("findings"));
                q.setCompleted(true);
            } catch (Exception e) {
                log.error("[Research] Failed for Q{}", q.getId(), e);
                state.getFindings().put(q.getId(), "Research failed: " + e.getMessage());
                q.setCompleted(true);
            }

            state.advanceToNextQuestion();
        }
    }

    /**
     * Phase 3: Self-reflective RAG analysis — checks for gaps and may add follow-up questions.
     */
    private void analyze(ResearchState state) throws Exception {
        String prompt = RESEARCH_ANALYZER
                + "\n\nOriginal query: " + state.getOriginalQuery()
                + "\n\nResearch findings:\n" + state.getAccumulatedFindings();

        String response = chatModel.chat(prompt);
        Map<String, Object> result = objectMapper.readValue(JsonText.stripFences(response), new TypeReference<>() {});

        state.setCompletenessScore(((Number) result.get("completeness_score")).doubleValue());
        state.setProposedToc(asList(result.get("proposed_toc")));
        state.setAnalysisRound(state.getAnalysisRound() + 1);

        boolean ready = (boolean) result.getOrDefault("ready_for_synthesis", true);
        if (!ready && state.canDoMoreAnalysis()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> newQuestions = (List<Map<String, Object>>) result.getOrDefault("new_questions", List.of());

            List<ResearchState.ResearchQuestion> followUps = new ArrayList<>();
            int nextId = state.getQuestions().size() + 1;
            for (Map<String, Object> nq : newQuestions) {
                followUps.add(ResearchState.ResearchQuestion.builder()
                        .id(nextId++)
                        .question((String) nq.get("question"))
                        .sourceHints((String) nq.getOrDefault("reason", ""))
                        .build());
            }
            state.getQuestions().addAll(followUps);
            state.setGaps(followUps.stream().map(ResearchState.ResearchQuestion::getQuestion).toList());
            log.info("[Analyze] Added {} follow-up questions (round {})", followUps.size(), state.getAnalysisRound());
        } else {
            state.setGaps(List.of()); // clear gaps to stop the loop
            log.info("[Analyze] Ready for synthesis. Completeness: {}", state.getCompletenessScore());
        }
    }

    /**
     * Phase 4: Synthesize all findings into a final report.
     */
    private void synthesize(ResearchState state) {
        String prompt = REPORT_SYNTHESIZER
                + "\n\nOriginal query: " + state.getOriginalQuery()
                + "\n\nProposed table of contents: " + state.getProposedToc()
                + "\n\nAll research findings:\n" + state.getAccumulatedFindings();

        String report = chatModel.chat(prompt);
        state.setFinalReport(report);
        log.info("[Synthesize] Report generated ({} chars)", report.length());
    }

    private void sendUpdate(UUID sessionId, String status, String message) {
        messagingTemplate.convertAndSend("/topic/research/" + sessionId,
                (Object) Map.of("status", status, "message", message));
    }

    @SuppressWarnings("unchecked")
    private List<String> asList(Object obj) {
        if (obj instanceof List) return (List<String>) obj;
        return new ArrayList<>();
    }
}

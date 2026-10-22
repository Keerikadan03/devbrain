package com.devbrain.util;

/**
 * System prompts for all LLM interactions.
 * Content extraction prompts from Bits2Brain + research agent prompts from Apollo.
 */
public final class Prompts {

    private Prompts() {}

    // ─── Content Ingestion Prompts (from Bits2Brain) ───

    public static final String TEXT_EXTRACT = """
        You are a knowledge extraction engine. Analyze the following text and return a JSON object with:
        {
          "title": "a concise, descriptive title",
          "summary": "a 2-3 sentence summary of the key points",
          "tags": ["tag1", "tag2", "tag3"],
          "concepts": ["concept1", "concept2"],
          "text": "the full cleaned-up text content"
        }
        "concepts" must be SPECIFIC subjects: named technologies, products, people, events, vulnerabilities,
        algorithms or precise ideas (e.g. "PHP", "use-after-free", "multi-head attention"). Never use generic
        category words such as documentation, guide, tutorial, article, video, overview or programming.
        Return ONLY valid JSON, no markdown fences.

        Text to analyze:
        """;

    /** Judges candidate links between a new document and existing ones. */
    public static final String RELATION_JUDGE = """
        You decide whether documents in a personal knowledge base are genuinely related to a NEW document.

        Two documents are related ONLY if they are about the same specific subject: the same technology,
        programming language, product, entity, event, vulnerability, algorithm or problem, or one directly
        explains, extends, applies or contradicts the other.
        Documents about different ASPECTS of the same specific subject ARE related: e.g. a language's manual,
        a tutorial on that language, a security report about it and career advice about it all concern that language.
        A very short note that just names a subject (e.g. the single word "php") is related to documents about that subject.
        They are NOT related just because they share a generic category or format: both being documentation,
        tutorials, videos, articles, "about programming", "about security" in general, or having similar structure.

        Return ONLY a JSON array with one object per candidate, no markdown fences:
        [{"id": 1, "related": true, "strength": 0.8, "relation": "<=15 words naming the specific shared subject"},
         {"id": 2, "related": false, "relation": "<=15 words on why they are different subjects"}]
        Use strength 0..1 (1 = same subject and aspect, 0.7 = same subject, different aspect, 0.5 = clearly
        connected, below 0.5 = weak).

        """;

    public static final String IMAGE_EXTRACT = """
        Analyze this image and return a JSON object with:
        {
          "title": "what this image shows",
          "summary": "detailed description of the image content",
          "tags": ["tag1", "tag2"],
          "concepts": ["concept1", "concept2"],
          "text": "textual description of all information in the image"
        }
        Return ONLY valid JSON, no markdown fences.
        """;

    // ─── Research Agent Prompts (from Apollo) ───

    public static final String RESEARCH_PLANNER = """
        You are a Research Planner. Given a user's question, create a research plan.

        Your job:
        1. Understand what the user is really asking
        2. Break it down into 3-5 specific research questions that, when answered, will fully address the query
        3. For each question, note what kind of sources would be most useful

        Return a JSON object:
        {
          "understanding": "your interpretation of what the user needs",
          "questions": [
            {
              "id": 1,
              "question": "specific research question",
              "source_hints": "what to search for"
            }
          ]
        }
        Return ONLY valid JSON.
        """;

    public static final String RESEARCH_COORDINATOR = """
        You are a Research Coordinator managing a team of research agents.

        You orchestrate the workflow:
        1. Review the research plan
        2. Delegate questions to the Research Engine (one at a time)
        3. After each answer, check if it's sufficient
        4. When all questions are answered, send findings to the Analyzer
        5. If the Analyzer finds gaps, create follow-up questions (max 2 rounds)
        6. When analysis is complete, trigger report synthesis

        Current state will be provided. Decide the next action.
        Return a JSON object:
        {
          "action": "delegate_research" | "request_analysis" | "synthesize" | "complete",
          "target_question_id": 1,
          "reasoning": "why this action"
        }
        """;

    public static final String RESEARCH_ENGINE = """
        You are a Research Engine. You process ONE research question at a time.

        Given a question and search results from the knowledge base:
        1. Analyze the provided context carefully
        2. Extract relevant facts, data points, and insights
        3. Note any gaps or areas needing more investigation

        Return a JSON object:
        {
          "question_id": 1,
          "findings": "detailed findings from the search results",
          "confidence": 0.85,
          "gaps": ["any information gaps identified"],
          "key_facts": ["fact1", "fact2"]
        }
        Return ONLY valid JSON.
        """;

    public static final String RESEARCH_ANALYZER = """
        You are a Research Analyzer performing self-reflective RAG analysis.

        Given the accumulated research findings:
        1. Assess completeness — are all aspects of the original query covered?
        2. Identify contradictions or inconsistencies in the findings
        3. Determine if additional research questions are needed (max 2 new questions)
        4. Propose a table of contents for the final report

        Return a JSON object:
        {
          "completeness_score": 0.9,
          "gaps": ["gap1", "gap2"],
          "new_questions": [
            {"question": "follow-up question", "reason": "why needed"}
          ],
          "proposed_toc": ["Section 1", "Section 2"],
          "ready_for_synthesis": true
        }
        Return ONLY valid JSON.
        """;

    public static final String REPORT_SYNTHESIZER = """
        You are a Report Synthesizer. Create a comprehensive research report.

        Using all the research findings provided, write a well-structured report in GitHub-Flavored Markdown.

        Requirements:
        - Clear title and executive summary
        - Follow the proposed table of contents
        - Cite specific findings and data points
        - Include a conclusion with actionable insights
        - Minimum 1000 words for depth
        - Use headers, bullet points, and tables where appropriate

        Write the report directly in markdown. Do NOT wrap in JSON.
        """;
}

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

    /**
     * Classifies how candidate documents relate to a NEW document (8 typed relationships).
     * The NEW document and numbered CANDIDATES are appended after this text.
     */
    public static final String RELATIONSHIP_CLASSIFIER = """
        You classify how documents in a personal knowledge base relate to a NEW document.

        STEP 1 - Are they related at all?
        Related ONLY if both are about the same specific subject (the same technology, programming language,
        product, entity, event, vulnerability, algorithm or problem), or one directly explains, extends,
        applies, replaces or contradicts the other. Different ASPECTS of the same specific subject ARE related
        (a language's manual, a tutorial on it, a security report about it, career advice about it).
        A very short note that just names a subject (e.g. the single word "php") is related to documents about it.
        NOT related just because they share a generic category or format: both documentation, both tutorials,
        both videos, both "about programming" or "about security" in general, or similar structure.

        STEP 2 - For related pairs pick exactly ONE type:
        - same_concept: both are about the same core concept (two notes about event sourcing)
        - shared_idea: overlapping but distinct concepts (CQRS and event sourcing)
        - opposing_view: conflicting opinions or recommendations on the same topic
        - builds_upon: one builds on the other, which is its prerequisite/foundation (directional)
        - example_of: one is a concrete instance/implementation of the other's abstract concept (directional)
        - supersedes: one evolved from or replaced the other (newer version, successor) (directional)
        - depends_on: a practical runtime/system dependency, not just a conceptual one (directional)
        - contrasts_with: legitimate alternatives for the same problem, neither wrong (PostgreSQL vs MongoDB)
        Prefer the most specific type that is clearly supported; use shared_idea when unsure.

        DIRECTION (directional types only): an edge reads "FROM <type> TO".
        Set "from" to "new" if the NEW document is the FROM side (e.g. NEW builds upon the candidate),
        or "candidate" if the candidate is the FROM side (e.g. the candidate is an example of NEW).

        Return ONLY a JSON array with one object per candidate, no markdown fences:
        [{"id": 1, "related": true, "type": "builds_upon", "from": "new", "strength": 0.8,
          "explanation": "<=20 words naming the specific shared subject and how they relate"},
         {"id": 2, "related": false, "explanation": "<=15 words on why they are different subjects"}]
        strength 0..1: 1 = same subject and aspect, 0.7 = same subject, different aspect,
        0.5 = clearly connected, below 0.5 = weak.

        """;

    /** Names nested topic groups; the groups (ids, levels, hint terms, sample titles) are appended. */
    public static final String TOPIC_NAMER = """
        You name topic groups in a personal knowledge base. Groups are nested: a higher level contains lower ones.
        For EACH group give a short topic name (2-6 words) for the idea that ALL of its documents share.
        - Higher-level groups get broader names; a sub-group's name must say what distinguishes it from its siblings.
        - No two groups may share a name, and a sub-group must not repeat its parent's name.
        - Prefer plain descriptive topics ("Protests over voter-list revision") over a person's or organisation's
          name, unless the group is genuinely about that person or organisation.
        - Use the hint terms and sample titles; don't invent facts.
        Return ONLY a JSON object mapping every group id to its name, no markdown fences:
        {"topic-ab12cd": "Name", "topic-ef34gh": "Name"}

        GROUPS
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

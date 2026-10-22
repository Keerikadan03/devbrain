# DevBrain — Multi-Agent Engineering Knowledge Engine

## What Is This?

DevBrain is a **personal engineering knowledge base** that combines two hackathon project architectures:

1. **Bits2Brain** (your hackathon project) — Java/Spring Boot content ingestion with Neo4j knowledge graphs
2. **Apollo** (another hackathon project) — C#/.NET multi-agent research system with self-reflective RAG

DevBrain merges both into a single Java/Spring Boot 4 application: you feed it engineering content (URLs, text, eventually YouTube videos), it extracts knowledge, stores it with semantic embeddings + a relationship graph, and then you can query it with a RAG-powered chat or run multi-agent research sessions that plan, research, analyze, and synthesize reports.

---

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Framework | Spring Boot 4.0.8 + Java 21 | Application backbone |
| LLM | Anthropic Claude (via Langchain4j) | Chat, extraction, research agents |
| Embeddings | AllMiniLmL6V2 (local, ONNX) | Semantic search — runs locally, no API key needed |
| Vector Store | PostgreSQL + pgvector | Stores embeddings for similarity search |
| Graph DB | Neo4j 5 Community | Knowledge graph — SIMILAR relationships between nodes |
| Message Queue | Apache Kafka | Async content ingestion pipeline |
| Real-time | WebSocket (STOMP/SockJS) | Live progress updates to frontend |
| Web Scraping | Jsoup | URL content fetching |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                      REST API Layer                      │
│  KnowledgeController  ResearchController  ChatController │
└──────────┬──────────────────┬──────────────────┬────────┘
           │                  │                  │
           ▼                  ▼                  ▼
┌──────────────────┐ ┌────────────────┐ ┌────────────────┐
│ Content Ingestion│ │ Multi-Agent    │ │ RAG Chat       │
│ Layer            │ │ Research Layer │ │ Service        │
│                  │ │                │ │                │
│ Kafka Producer → │ │ Orchestrator   │ │ Embed query →  │
│ Kafka Consumer → │ │ ┌─Planner     │ │ Search pgvector│
│ Parser Manager   │ │ ├─Engine      │ │ → Augment      │
│ ┌─UrlParser     │ │ ├─Analyzer    │ │ → Claude LLM   │
│ ├─TextParser    │ │ └─Synthesizer │ │                │
│ └─(YouTubeParser)│ │                │ │                │
└────────┬─────────┘ └───────┬────────┘ └───────┬────────┘
         │                   │                   │
         ▼                   ▼                   ▼
┌─────────────────────────────────────────────────────────┐
│                   KnowledgeService                       │
│  Save to PostgreSQL + Embed in pgvector + Save to Neo4j │
│  Auto-link similar nodes via embedding similarity        │
└─────────────────────────────────────────────────────────┘
         │                   │                   │
    ┌────▼────┐      ┌──────▼──────┐     ┌──────▼──────┐
    │PostgreSQL│      │  pgvector   │     │   Neo4j     │
    │(content) │      │(embeddings) │     │(graph rels) │
    └─────────┘      └─────────────┘     └─────────────┘
```

---

## Project Structure

```
devbrain/
├── pom.xml                          # Maven config — Spring Boot 4.0.8 + Langchain4j 1.22.0-beta32
├── docker-compose.yml               # PostgreSQL+pgvector, Neo4j, Kafka+Zookeeper
├── src/main/resources/
│   └── application.yml              # All config: DB, Neo4j, Kafka, Claude API, thresholds
│
└── src/main/java/com/devbrain/
    ├── DevbrainApplication.java     # @SpringBootApplication @EnableAsync
    │
    ├── config/
    │   ├── WebSocketConfig.java     # STOMP/SockJS — endpoint /ws, broker /topic
    │   ├── KafkaConfig.java         # Creates ingestion + research topics (3 partitions)
    │   └── EmbeddingConfig.java     # Registers local AllMiniLmL6V2 embedding model bean
    │
    ├── model/
    │   ├── entity/
    │   │   ├── KnowledgeNode.java   # JPA entity: id, title, content, summary, source, metadata(jsonb)
    │   │   └── ResearchSession.java # JPA entity: id, query, phase(enum), plan, findings, report
    │   ├── neo4j/
    │   │   ├── GraphNode.java       # Neo4j @Node("Knowledge"): uuid, title, source, similarTo rels
    │   │   └── SimilarRelationship.java  # @RelationshipProperties: score, target
    │   └── dto/
    │       ├── IngestionRequest.java # { type, content, title }
    │       ├── ResearchRequest.java  # { query }
    │       └── ChatMessage.java     # { message, sessionId }
    │
    ├── repository/
    │   ├── KnowledgeNodeRepository.java  # JPA — findByTitle, findBySource, findAll
    │   ├── ResearchSessionRepository.java # JPA — findByPhase, findAll
    │   └── GraphNodeRepository.java      # Neo4j — Cypher: findSimilarNodes, createSimilarRelationship
    │
    ├── service/
    │   ├── KnowledgeService.java    # CORE: dual storage (PG + pgvector + Neo4j), auto-linking, search
    │   └── ChatService.java         # RAG chat: embed query → search → augment prompt → Claude
    │
    ├── agent/
    │   ├── parsers/
    │   │   ├── Parser.java          # Interface: getName() + run(Map input)
    │   │   ├── UrlParser.java       # Jsoup fetch → truncate → Claude extract → save
    │   │   ├── TextParser.java      # Raw text → Claude extract → save
    │   │   └── ParserManager.java   # Strategy pattern registry: maps name → Parser
    │   └── research/
    │       ├── ResearchState.java   # State machine: questions, findings, gaps, analysis rounds
    │       └── ResearchOrchestrator.java  # Async pipeline: Plan → Research → Analyze → Synthesize
    │
    ├── event/
    │   ├── IngestionEvent.java      # Kafka message DTO
    │   ├── IngestionProducer.java   # Publishes to devbrain.ingestion topic
    │   └── IngestionConsumer.java   # @KafkaListener → routes to ParserManager → WebSocket updates
    │
    ├── controller/
    │   ├── KnowledgeController.java # /api/knowledge — ingest, nodes, search, graph
    │   ├── ResearchController.java  # /api/research — start, status, report, sessions
    │   ├── ChatController.java      # /api/chat — RAG Q&A
    │   └── HealthController.java    # /api/health
    │
    └── util/
        ├── Prompts.java             # All LLM prompts for extraction + 5 research agent roles
        └── TextTruncator.java       # Truncates text to 8000 chars
```

---

## How Each Layer Works

### Layer 1: Content Ingestion (from Bits2Brain)

**Pattern:** Strategy pattern for parsers — easy to add new content types.

**Flow:**
1. Client POSTs to `/api/knowledge/ingest` with `{ type: "url", content: "https://...", title: "optional" }`
2. `KnowledgeController` publishes a Kafka event via `IngestionProducer`
3. `IngestionConsumer` picks it up, routes to the right parser via `ParserManager`
4. Parser (e.g., `UrlParser`) fetches content → sends to Claude with extraction prompt → gets structured JSON back
5. `KnowledgeService.saveFromParsedResult()` does triple save:
   - PostgreSQL: stores the content as a `KnowledgeNode`
   - pgvector: creates embedding and stores for semantic search
   - Neo4j: creates a `GraphNode` and auto-links to similar existing nodes (using embedding cosine similarity > 0.75 threshold)
6. WebSocket sends live status updates to `/topic/ingestion`

**To add a new parser** (e.g., YouTube):
- Create `YouTubeParser.java` implementing `Parser`
- Return `"youtube"` from `getName()`
- Implement `run()` — fetch transcript, send to Claude, save via KnowledgeService
- Spring auto-registers it via `ParserManager`'s `List<Parser>` injection

### Layer 2: Multi-Agent Research (from Apollo)

**Pattern:** Multi-agent orchestration with shared state machine and self-reflective gap analysis.

**5 Agent Roles** (each is a specialized prompt in `Prompts.java`):
1. **Research Planner** — Breaks user's question into sub-questions with source hints
2. **Research Coordinator** — (prompt exists, orchestration handled by ResearchOrchestrator)
3. **Research Engine** — Answers each sub-question using knowledge base context (RAG)
4. **Research Analyzer** — Checks completeness, identifies gaps, proposes follow-up questions
5. **Report Synthesizer** — Writes the final markdown report from all findings

**Flow:**
1. Client POSTs to `/api/research/start` with `{ query: "How does Kafka handle backpressure?" }`
2. Creates a `ResearchSession` (tracked in PostgreSQL with phase enum)
3. `ResearchOrchestrator.startResearch()` runs async:
   - **Plan**: Planner breaks query into 3-5 research questions
   - **Research**: Engine answers each question using knowledge base + Claude
   - **Analyze**: Analyzer scores completeness (0-1), may add follow-up questions
   - **Loop**: If gaps found and < 2 analysis rounds, research the gaps and re-analyze
   - **Synthesize**: Synthesizer writes a structured markdown report
4. Each phase sends WebSocket updates to `/topic/research/{sessionId}`
5. Final report stored in `ResearchSession.report`

### Layer 3: RAG Chat

**Flow:**
1. Client POSTs to `/api/chat` with `{ message: "What is event sourcing?" }`
2. `ChatService` embeds the query, searches pgvector for top 5 matches
3. Builds an augmented prompt with knowledge base context + user question
4. Sends to Claude, returns the answer
5. If no context found, Claude answers from general knowledge and suggests ingesting relevant content

---

## API Endpoints

### Knowledge Management
```
POST   /api/knowledge/ingest        # Ingest content (async via Kafka)
       Body: { "type": "url"|"text", "content": "...", "title": "optional" }

GET    /api/knowledge/nodes          # List all knowledge nodes
GET    /api/knowledge/nodes/{id}     # Get single node by UUID
GET    /api/knowledge/search?q=...&limit=5  # Semantic search
GET    /api/knowledge/graph/{nodeId} # Get related nodes from Neo4j
```

### Multi-Agent Research
```
POST   /api/research/start           # Start research session
       Body: { "query": "your question" }

GET    /api/research/{sessionId}     # Get session status + data
GET    /api/research/{sessionId}/report  # Get final report (markdown)
GET    /api/research/sessions        # List all sessions
```

### RAG Chat
```
POST   /api/chat                     # Ask a question
       Body: { "message": "your question", "sessionId": "optional" }
```

### WebSocket Channels
```
/topic/ingestion              # Live ingestion progress updates
/topic/research/{sessionId}   # Live research phase updates
```

---

## Running Locally

### Prerequisites
- Java 21
- Maven 3.9+
- Docker Desktop (for infrastructure)
- Anthropic API key (console.anthropic.com — separate from Claude Pro subscription)

### Steps

```bash
# 1. Extract project
tar -xzf devbrain.tar.gz
cd devbrain

# 2. Start infrastructure
docker-compose up -d
# Starts: PostgreSQL+pgvector (5432), Neo4j (7474/7687), Kafka (9092), Zookeeper (2181)

# 3. Set your API key
export ANTHROPIC_API_KEY=sk-ant-xxxxx

# 4. Build and run
mvn clean spring-boot:run

# 5. Test health
curl http://localhost:8080/api/health
```

### Testing the APIs

```bash
# Ingest a URL
curl -X POST http://localhost:8080/api/knowledge/ingest \
  -H "Content-Type: application/json" \
  -d '{"type":"url","content":"https://martinfowler.com/articles/microservices.html","title":"Microservices by Fowler"}'

# Ingest raw text
curl -X POST http://localhost:8080/api/knowledge/ingest \
  -H "Content-Type: application/json" \
  -d '{"type":"text","content":"Event sourcing stores state changes as a sequence of events...","title":"Event Sourcing Notes"}'

# Search the knowledge base
curl "http://localhost:8080/api/knowledge/search?q=microservices&limit=5"

# Chat with RAG
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Explain the benefits of event sourcing"}'

# Start a research session
curl -X POST http://localhost:8080/api/research/start \
  -H "Content-Type: application/json" \
  -d '{"query":"Compare CQRS vs traditional CRUD for high-traffic systems"}'

# Check research status (use the sessionId from above)
curl http://localhost:8080/api/research/{sessionId}

# Get the final report
curl http://localhost:8080/api/research/{sessionId}/report
```

---

## What's NOT Built Yet (Next Steps)

### High Priority
1. **YouTubeParser** — Fetch video transcripts and ingest them. Use `io.github.thoroldvix:youtube-transcript-api:0.4.0` Maven dependency. Create `YouTubeParser.java` implementing `Parser`, extract video ID from URL, fetch transcript, send to Claude for extraction.

2. **Angular Frontend** — The backend is API-ready but has no UI. Build: dashboard showing all knowledge nodes, ingestion form (URL, text, YouTube tabs), knowledge graph visualization (vis.js or d3-force with Neo4j data), chat interface with streaming, research session view with live WebSocket progress.

3. **Error Handling** — Add global `@ControllerAdvice` exception handler, input validation (`@Valid`), rate limiting.

### Medium Priority
4. **ImageParser** — Accept image uploads, use Claude's vision to extract text/concepts
5. **PDF Parser** — Extract text from PDFs using Apache PDFBox
6. **Authentication** — Spring Security + JWT for multi-user support
7. **Streaming Chat** — Use Langchain4j's `StreamingChatModel` for token-by-token responses via WebSocket

### Nice to Have
8. **Export** — Export knowledge graph as JSON/CSV
9. **Batch Ingestion** — Upload multiple URLs at once
10. **Tags & Collections** — Organize knowledge nodes into user-defined collections
11. **Scheduled Ingestion** — Auto-re-fetch URLs periodically for updated content

---

## Key Design Decisions

| Decision | Why |
|----------|-----|
| Dual storage (PG + Neo4j) | PG handles content + embeddings efficiently; Neo4j excels at relationship traversal and graph queries |
| Kafka for ingestion | Decouples API response from slow LLM processing; enables retry and scaling |
| Local embeddings (AllMiniLmL6V2) | No API key needed for embeddings; runs fast locally; keeps costs to only LLM calls |
| Strategy pattern for parsers | Adding a new content type = one new class, zero changes to existing code |
| Self-reflective RAG (2 rounds max) | From Apollo — the analyzer catches gaps that a single RAG pass misses |
| WebSocket for updates | Long-running ingestion and research need real-time feedback |

---

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `ANTHROPIC_API_KEY` | Yes | `demo` | Your Anthropic API key from console.anthropic.com |

All other config (DB URLs, Kafka, thresholds) is in `application.yml` and assumes Docker defaults.

---

## Docker Services

| Service | Port | Credentials |
|---------|------|------------|
| PostgreSQL + pgvector | 5432 | devbrain / devbrain |
| Neo4j Browser | 7474 (HTTP), 7687 (Bolt) | neo4j / devbrain123 |
| Kafka | 9092 | — |
| Zookeeper | 2181 | — |

Neo4j Browser UI: http://localhost:7474 — you can visualize the knowledge graph here.

---

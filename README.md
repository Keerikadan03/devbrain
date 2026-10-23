# DevBrain

**A personal knowledge engine for engineers.** Paste in anything you learn from (web pages, PDFs, YouTube videos, Instagram reels, notes) and DevBrain extracts the knowledge, links related ideas together with typed relationships, groups them into a navigable topic map, and lets you chat with or run multi-step research over everything you've saved.

```
paste a link ──► Kafka ──► parser ──► Claude extracts title / summary / concepts
                                          │
                     ┌────────────────────┼─────────────────────┐
                     ▼                    ▼                     ▼
              Postgres (content)   pgvector (embeddings)   Neo4j (typed relationships,
                                                             topic hierarchy)
                     └────────────────────┼─────────────────────┘
                                          ▼
                     Graph explorer · semantic search · RAG chat · research agents
```

---

## Features

### Add anything
- **One box, auto-detected.** Paste a link or text; DevBrain works out whether it's a YouTube video, Instagram post, PDF, web page or plain text.
- **YouTube**: metadata + captions (manual or auto-generated) via `yt-dlp`.
- **Instagram reels/posts**: caption, author, and a transcript of the spoken audio.
- **Videos without captions** are transcribed locally with [whisper.cpp](https://github.com/ggml-org/whisper.cpp): no audio is sent to any API.
- **PDFs** by URL or small file upload (text layer via Apache PDFBox).
- **Web pages** via Jsoup; **text/notes/code** pasted directly.
- Ingestion is **asynchronous** (Kafka) with **live progress** in the UI over WebSocket.

### Typed relationships, not just "similar"
Each new document is compared against likely candidates (cheap vector + shared-concept recall), then **one LLM call** decides which ones are actually related and how:

| Relationship | Meaning | Direction |
|---|---|---|
| same concept | Two notes about the same thing | — |
| shared idea | Overlapping but distinct ideas | — |
| opposing view | Conflicting opinions on the same topic | — |
| contrasts with | Legitimate alternatives for the same problem | — |
| builds upon | A builds on the foundation B | A → B |
| example of | A is a concrete instance of B | A → B |
| supersedes | A evolved from / replaced B | A → B |
| depends on | A needs B in practice | A → B |

Every edge carries a strength score and a one-line explanation.

### Knowledge graph explorer
- **Hierarchical topics.** Documents are grouped into nested, named topics: connected components first, then Leiden community detection (Neo4j Graph Data Science) for sub-topics, weighted by relationship type and strength. Names come from shared concepts, optionally rewritten into readable titles with one batched LLM call (cached by membership).
- **Semantic zoom.** Zooming spreads nodes apart instead of magnifying them. Topics **open as you zoom in** (deeper topics need more zoom) and **fold back into their bubble as you zoom out**, with members animating in and out and returning to the same layout every time.
- **Click to focus.** Clicking a document or topic glides it to the centre of the screen and keeps it there while the layout settles; clicking a topic also opens it.
- **Semantic search in the graph.** Search by meaning; matching documents (and the collapsed topics that contain them) are ringed, and **Enter / clicking a result opens every topic around it and flies to it**.
- Relationships between collapsed topics are merged into one weighted edge; filter edges by type; per-document detail panel with its topic trail and explained relationships.

### Chat with your knowledge base (RAG + graph)
Chunk-level vector search finds relevant passages, then the graph pulls in linked documents. Answers are **grounded in your sources only** and cite them; when your knowledge base isn't enough, it says what's missing and asks before using outside knowledge.

### Multi-agent research
Ask a bigger question and a pipeline of agents works through it: **Plan → Research (per sub-question) → Analyze for gaps → loop → Synthesize** a markdown report, with live phase updates.

### Library
Browse, semantically search and delete everything you've saved (deleting removes the document, its vectors and its graph node).

---

## Quick start (Docker, recommended)

**Requirements:** Docker with Compose v2 (Docker Desktop, or Docker Engine + compose plugin), ~4 GB free RAM, and an [Anthropic API key](https://console.anthropic.com).

```bash
git clone https://github.com/Keerikadan03/devbrain.git
cd devbrain
cp .env.example .env          # then set ANTHROPIC_API_KEY in .env
docker compose up --build
```

Then open:

| | URL |
|---|---|
| **DevBrain UI** | http://localhost:3000 |
| Backend API | http://localhost:8080/api/health |
| Neo4j Browser | http://localhost:7474 (user `neo4j`, password from `NEO4J_PASSWORD`) |

The first build takes a few minutes (Maven dependencies, compiling whisper.cpp, downloading the speech model). The first start also takes a minute while Neo4j downloads its plugins; the backend waits until Postgres, Neo4j and Kafka are healthy.

Useful commands:

```bash
docker compose up -d --build      # run in the background
docker compose logs -f backend    # follow backend logs
docker compose down               # stop (data is kept in Docker volumes)
docker compose down -v            # stop AND delete all data
```

### Configuration (`.env`)

`docker compose` reads `.env` from the project root. Only `ANTHROPIC_API_KEY` is required; see [`.env.example`](.env.example) for everything.

| Variable | Default | What it does |
|---|---|---|
| `ANTHROPIC_API_KEY` | **required** | Anthropic API key used for extraction, relationship classification, topic names, chat and research |
| `ANTHROPIC_MODEL` | `claude-haiku-5-5` | Claude model id |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `devbrain` | Postgres credentials |
| `NEO4J_PASSWORD` | `devbrain123` | Neo4j password (min. 8 characters) |
| `FRONTEND_PORT` / `BACKEND_PORT` | `3000` / `8080` | Host ports for the UI and API |
| `POSTGRES_HOST_PORT` / `NEO4J_HTTP_PORT` / `NEO4J_BOLT_PORT` / `KAFKA_HOST_PORT` | `5433` / `7474` / `7687` / `9092` | Host ports for the infrastructure |
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | Backend URL **as the browser reaches it**. Baked into the UI at build time: rebuild after changing it |
| `TOPICS_LLM_NAMES` | `true` | Readable topic names via one batched LLM call (`false` = concept-keyword names, no LLM cost) |
| `WHISPER_ENABLED` | `true` | Local speech-to-text for videos without captions |
| `WHISPER_MODEL` | `base.en` | Whisper model baked into the image: `tiny.en`, `base.en`, `small.en`, `medium.en` (rebuild after changing) |
| `YTDLP_COOKIES_FILE` | *(empty)* | Path **inside the backend container** to a `cookies.txt` for login-walled videos |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75` | JVM options for the backend |

Changes to anything except `NEXT_PUBLIC_API_URL` and `WHISPER_MODEL` only need `docker compose up -d`; those two need `docker compose up -d --build`.

### Deploying on a server

1. Copy the repo to the server and create `.env` from `.env.example`.
2. **Change `POSTGRES_PASSWORD` and `NEO4J_PASSWORD`.** If you change them after the first start, also run `docker compose down -v` (the databases keep the password they were created with), or change it inside the databases.
3. Set `NEXT_PUBLIC_API_URL` to the address the *browser* uses for the API, e.g. `https://devbrain-api.example.com` or `http://<server-ip>:8080`.
4. `docker compose up -d --build`.
5. Put a reverse proxy (Caddy, nginx, Traefik) with TLS in front of ports 3000 and 8080. The API serves a SockJS/WebSocket endpoint at `/ws`, so allow WebSocket upgrades.

> **Security note:** DevBrain has **no authentication** and the API allows any origin. Anyone who can reach it can read, add and delete your knowledge and spend your Anthropic credits. Keep it on localhost / a private network / behind an authenticating proxy, and don't publish the database ports (`5433`, `7474`, `7687`, `9092`) to the internet. Remove those `ports:` entries in `docker-compose.yml` if you don't need host access.

### Login-walled videos (cookies)

Private or login-walled Instagram/YouTube content needs browser cookies. Export a `cookies.txt` (Netscape format) from your browser, then add a `docker-compose.override.yml` next to `docker-compose.yml`:

```yaml
services:
  backend:
    volumes:
      - ./cookies.txt:/run/secrets/cookies.txt:ro
```

and set `YTDLP_COOKIES_FILE=/run/secrets/cookies.txt` in `.env`. (`cookies.txt` is git-ignored.)

---

## Running from source (development)

**Requirements:** Java 21, Maven 3.9+, Node.js 20+, Docker; optionally `yt-dlp`, `ffmpeg` and `whisper-cpp` for video ingestion (`brew install yt-dlp ffmpeg whisper-cpp` on macOS).

```bash
# 1. Infrastructure only
docker compose up -d postgres neo4j kafka

# 2. Backend on :8080 (reads the API key from your shell)
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run

# 3. Frontend on :3000
cd frontend
npm install
npm run dev
```

The backend defaults (`src/main/resources/application.yml`) match the compose ports, so no other configuration is needed. Every setting in the table above can also be passed to `mvn spring-boot:run` as an environment variable.

**Whisper from source:** download a model once, e.g.
```bash
mkdir -p ~/.devbrain/models
curl -L -o ~/.devbrain/models/ggml-base.en.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin
```
(or point `WHISPER_MODEL_PATH` at one). Without a model, videos fall back to captions/description.

Tests: `mvn test` · Frontend lint/types: `cd frontend && npm run lint && npx tsc --noEmit`

---

## Architecture

| Layer | Technology | Purpose |
|---|---|---|
| Backend | Spring Boot 4 + Java 21 | REST API, ingestion, agents |
| LLM | Anthropic Claude via LangChain4j | Extraction, relationship classification, topic names, chat, research |
| Embeddings | all-MiniLM-L6-v2 (local ONNX) | Vectors computed in-process: no embedding API or key |
| Vector store | PostgreSQL 16 + pgvector | One vector per document (linking) + one per chunk (retrieval) |
| Graph | Neo4j 5 + APOC + Graph Data Science | Typed relationships, concepts, Leiden topic clustering |
| Queue | Apache Kafka | Async ingestion |
| Live updates | WebSocket (STOMP over SockJS) | Ingestion feed |
| Media | yt-dlp, ffmpeg, whisper.cpp | Video metadata, captions, local transcription |
| Frontend | Next.js 16, React 19, Tailwind 4, d3 | UI and graph explorer |

### Ingestion flow
1. `POST /api/knowledge/ingest` → `SourceDetector` picks a parser (unless `type` is given) → event published to Kafka.
2. `IngestionConsumer` routes it to the parser (`ParserManager`, strategy pattern): `UrlParser`, `TextParser`, `PdfParser`, `YoutubeParser`, `InstagramParser`.
3. `ExtractionPipeline`: Claude extracts title, summary, tags and concepts; the **raw text** is kept as content.
4. `KnowledgeService` saves the document to Postgres, stores one document vector + chunk vectors in pgvector, creates the Neo4j node and its concepts.
5. Linking: candidates by vector similarity (threshold rises as the graph grows) + shared concepts → `RelationshipClassifier` (one LLM call) → typed, scored, explained edges in Neo4j.
6. `TopicHierarchyService` recomputes the topic tree in the background a few seconds after the graph changes.
7. Progress is pushed to `/topic/ingestion` over WebSocket.

### Project layout
```
devbrain/
├── docker-compose.yml        # full stack; reads .env
├── Dockerfile                # backend image (jar + yt-dlp + ffmpeg + whisper.cpp)
├── .env.example              # all settings
├── src/main/java/com/devbrain/
│   ├── agent/parsers/        # Url, Text, Pdf, Youtube, Instagram parsers; YtDlp; Transcriber; ExtractionPipeline
│   ├── agent/research/       # ResearchOrchestrator (plan → research → analyze → synthesize)
│   ├── config/               # Kafka topics, WebSocket, embedding model + pgvector store
│   ├── controller/           # /api/knowledge, /api/chat, /api/research, /api/health
│   ├── event/                # Kafka producer/consumer
│   ├── model/                # JPA entities, Neo4j nodes, RelationType, DTOs
│   ├── repository/           # JPA + Neo4j repositories
│   ├── service/              # KnowledgeService, RelationshipClassifier, TopicHierarchyService,
│   │                         # ChatService, SourceDetector
│   └── util/                 # prompts, text helpers
├── src/main/resources/application.yml
└── frontend/                 # Next.js app (Dockerfile inside)
    ├── app/                  # / (add), /library, /graph, /chat, /research
    ├── components/
    └── lib/api.ts            # API client + types
```

---

## API

```
POST   /api/knowledge/ingest                  { "content": "...", "type?": "auto|url|text|pdf|youtube|instagram", "title?": "..." }
GET    /api/knowledge/nodes                   all documents, newest first
GET    /api/knowledge/nodes/{id}              one document
DELETE /api/knowledge/nodes/{id}              delete document, vectors and graph node
GET    /api/knowledge/search?q=...&limit=5    semantic search (chunk-level)
GET    /api/knowledge/graph                   nodes, typed edges, topic hierarchy, relation types
GET    /api/knowledge/graph/{nodeId}          a document's relationships
GET    /api/knowledge/graph/{nodeId}/type/{t} filtered by relationship type (e.g. builds_upon)
POST   /api/knowledge/reindex                 rebuild all vectors and links from stored documents

POST   /api/chat                              { "message": "...", "sessionId?": "..." } → answer + sources

POST   /api/research/start                    { "query": "..." }
GET    /api/research/{sessionId}              status, plan, findings
GET    /api/research/{sessionId}/report       final markdown report
GET    /api/research/sessions                 all sessions

GET    /api/health
WS     /ws  (SockJS/STOMP) → /topic/ingestion
```

Example:
```bash
curl -X POST http://localhost:8080/api/knowledge/ingest \
  -H "Content-Type: application/json" \
  -d '{"content":"https://martinfowler.com/articles/microservices.html"}'

curl "http://localhost:8080/api/knowledge/search?q=event%20sourcing&limit=5"
```

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Set ANTHROPIC_API_KEY in .env` when starting | Create `.env` from `.env.example` and set the key |
| UI says it can't reach the backend | Check `docker compose logs backend`; if the UI is opened from another machine, set `NEXT_PUBLIC_API_URL` to the server's address and rebuild |
| Backend restarts with auth errors after changing DB passwords | The databases keep their original password: `docker compose down -v` (deletes data) or change it inside the DB |
| YouTube/Instagram ingestion fails with "login required" | Provide cookies (see above); yt-dlp also needs updating as sites change: `docker compose build --no-cache backend` |
| Port already in use | Change the `*_PORT` variables in `.env` |
| Topics show keyword names | LLM naming is off (`TOPICS_LLM_NAMES=false`) or the call failed; keyword names are the fallback |

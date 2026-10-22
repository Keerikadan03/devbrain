"use client";
import { useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { API, post, sourceColor } from "@/lib/api";
import { detectType, TYPE_LABELS } from "@/lib/detect";

const TYPES = [
  { id: "auto", label: "Auto-detect", hint: "Paste anything: a link (YouTube, Instagram, PDF, web page), or text…", multiline: true },
  { id: "text", label: "Text", hint: "Paste notes, code, or an article…", multiline: true },
  { id: "url", label: "Web page", hint: "https://example.com/article" },
  { id: "pdf", label: "PDF", hint: "https://example.com/paper.pdf — or choose a small file below" },
  { id: "youtube", label: "YouTube", hint: "https://www.youtube.com/watch?v=…" },
  { id: "instagram", label: "Instagram", hint: "https://www.instagram.com/reel/…" },
] as const;

type Feed = { id: number; status: string; type?: string; title?: string; details?: string };

// Kafka's default message limit is ~1 MB; base64 inflates by a third.
const MAX_PDF_BYTES = 700 * 1024;

export default function AddPage() {
  const [type, setType] = useState<(typeof TYPES)[number]["id"]>("auto");
  const [queued, setQueued] = useState("");
  const [content, setContent] = useState("");
  const [title, setTitle] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [feed, setFeed] = useState<Feed[]>([]);
  const [live, setLive] = useState(false);
  const seq = useRef(0);
  const spec = TYPES.find((t) => t.id === type)!;
  const guess = type === "auto" ? detectType(content) : null;

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(`${API}/ws`) as WebSocket,
      reconnectDelay: 5000,
      onConnect: () => {
        setLive(true);
        client.subscribe("/topic/ingestion", (msg) => {
          const m = JSON.parse(msg.body);
          const item: Feed = {
            id: ++seq.current,
            status: m.status,
            type: m.type,
            title: m.result?.title,
            details: m.result?.details ?? m.result?.error ?? m.details,
          };
          setFeed((f) => [item, ...f].slice(0, 20));
        });
      },
      onWebSocketClose: () => setLive(false),
    });
    client.activate();
    return () => {
      client.deactivate();
    };
  }, []);

  async function submit() {
    setError("");
    setQueued("");
    if (!content.trim()) return setError("Nothing to add yet.");
    setBusy(true);
    try {
      const r = await post<{ type: string; detected: boolean }>("/api/knowledge/ingest", {
        type,
        content: content.trim(),
        title: title.trim() || undefined,
      });
      setQueued(`Queued as ${TYPE_LABELS[r.type as keyof typeof TYPE_LABELS] ?? r.type}${r.detected ? " (auto-detected)" : ""}`);
      setContent("");
      setTitle("");
    } catch (e) {
      setError(
        (e as Error).message.startsWith("400")
          ? "That kind of content isn't supported yet (for example, images)."
          : `Could not reach the backend (${(e as Error).message}). Is it running on ${API}?`,
      );
    } finally {
      setBusy(false);
    }
  }

  function onFile(file?: File) {
    if (!file) return;
    if (file.size > MAX_PDF_BYTES) {
      return setError("That PDF is too big to send directly (limit ~700 KB). Host it and paste its URL instead.");
    }
    const r = new FileReader();
    r.onload = () => {
      setContent(String(r.result).split(",")[1] ?? "");
      if (!title) setTitle(file.name.replace(/\.pdf$/i, ""));
      setError("");
    };
    r.readAsDataURL(file);
  }

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="text-2xl font-semibold">Add to your brain</h1>
      <p className="mt-1 text-sm text-zinc-400">Paste anything. It figures out whether it is a video, PDF, post, web page or text, then links it to related notes.</p>

      <div className="mt-6 flex flex-wrap gap-2">
        {TYPES.map((t) => (
          <button
            key={t.id}
            onClick={() => { setType(t.id); setContent(""); setError(""); setQueued(""); }}
            className={`rounded-full border px-4 py-1.5 text-sm transition ${
              type === t.id ? "border-transparent text-black" : "border-white/15 text-zinc-300 hover:bg-white/5"
            }`}
            style={type === t.id ? { background: sourceColor(t.id) } : undefined}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="mt-4 space-y-3">
        {"multiline" in spec ? (
          <textarea
            value={guess === "pdf" && content.length > 500 ? "(PDF file loaded)" : content}
            readOnly={guess === "pdf" && content.length > 500}
            onChange={(e) => setContent(e.target.value)}
            placeholder={spec.hint}
            rows={10}
            className="w-full rounded-lg border border-white/10 bg-white/5 p-3 text-sm outline-none focus:border-violet-400"
          />
        ) : (
          <input
            value={type === "pdf" && content.length > 500 ? "(PDF file loaded)" : content}
            onChange={(e) => setContent(e.target.value)}
            placeholder={spec.hint}
            className="w-full rounded-lg border border-white/10 bg-white/5 p-3 text-sm outline-none focus:border-violet-400"
          />
        )}
        {guess && (
          <p className="text-sm text-zinc-400">
            Looks like: <span className="font-medium text-zinc-200">{TYPE_LABELS[guess]}</span>
          </p>
        )}
        {(type === "pdf" || type === "auto") && (
          <input
            type="file"
            accept="application/pdf"
            onChange={(e) => onFile(e.target.files?.[0])}
            className="block text-sm text-zinc-400 file:mr-3 file:rounded-md file:border-0 file:bg-white/10 file:px-3 file:py-1.5 file:text-zinc-200"
          />
        )}
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Title (optional — otherwise generated)"
          className="w-full rounded-lg border border-white/10 bg-white/5 p-3 text-sm outline-none focus:border-violet-400"
        />
        {error && <p className="text-sm text-red-400">{error}</p>}
        {queued && <p className="text-sm text-emerald-400">{queued}</p>}
        <button
          onClick={submit}
          disabled={busy}
          className="rounded-lg bg-violet-500 px-5 py-2 text-sm font-medium text-white transition hover:bg-violet-400 disabled:opacity-50"
        >
          {busy ? "Sending…" : "Add to knowledge base"}
        </button>
      </div>

      <div className="mt-10">
        <div className="flex items-center gap-2 text-sm font-medium text-zinc-300">
          Live activity
          <span className={`h-2 w-2 rounded-full ${live ? "bg-emerald-400" : "bg-zinc-600"}`} title={live ? "connected" : "disconnected"} />
        </div>
        <ul className="mt-3 space-y-2">
          {feed.length === 0 && <li className="text-sm text-zinc-500">Nothing yet — add something above.</li>}
          {feed.map((f) => (
            <li key={f.id} className="flex items-center gap-3 rounded-lg border border-white/10 bg-white/[0.03] px-3 py-2 text-sm">
              <span
                className={`rounded px-2 py-0.5 text-xs ${
                  f.status === "completed" ? "bg-emerald-500/15 text-emerald-300"
                  : f.status === "error" ? "bg-red-500/15 text-red-300"
                  : "bg-amber-500/15 text-amber-300"
                }`}
              >
                {f.status}
              </span>
              <span className="text-zinc-400">{f.type}</span>
              <span className="truncate text-zinc-200">{f.title ?? f.details ?? ""}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

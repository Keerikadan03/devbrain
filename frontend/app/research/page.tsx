"use client";
import { useCallback, useEffect, useState } from "react";
import { api, post, type ResearchSession } from "@/lib/api";
import Markdown from "@/components/Markdown";

const PHASES = ["PLANNING", "RESEARCHING", "ANALYZING", "SYNTHESIZING", "COMPLETED"] as const;

type Question = { id: number; question: string; sourceHints?: string; completed?: boolean };

function parseJSON<T>(s?: string | null): T | null {
  try { return s ? JSON.parse(s) : null; } catch { return null; }
}

export default function ResearchPage() {
  const [query, setQuery] = useState("");
  const [sessions, setSessions] = useState<ResearchSession[]>([]);
  const [current, setCurrent] = useState<ResearchSession | null>(null);
  const [error, setError] = useState("");

  const loadSessions = useCallback(
    () => api<ResearchSession[]>("/api/research/sessions").then(setSessions).catch((e) => setError(e.message)),
    [],
  );
  useEffect(() => { loadSessions(); }, [loadSessions]);

  // Poll the open session until it finishes.
  useEffect(() => {
    if (!current || current.phase === "COMPLETED" || current.phase === "FAILED") return;
    const t = setInterval(async () => {
      try {
        const s = await api<ResearchSession>(`/api/research/${current.id}`);
        setCurrent(s);
        if (s.phase === "COMPLETED" || s.phase === "FAILED") loadSessions();
      } catch { /* keep polling */ }
    }, 2000);
    return () => clearInterval(t);
  }, [current, loadSessions]);

  async function start() {
    if (!query.trim()) return;
    setError("");
    try {
      const r = await post<{ sessionId: string }>("/api/research/start", { query: query.trim() });
      setQuery("");
      setCurrent(await api<ResearchSession>(`/api/research/${r.sessionId}`));
      loadSessions();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const questions = parseJSON<Question[]>(current?.plan) ?? [];
  const phaseIdx = current ? PHASES.indexOf(current.phase as (typeof PHASES)[number]) : -1;

  return (
    <div className="mx-auto max-w-3xl">
      <h1 className="text-2xl font-semibold">Research</h1>
      <p className="text-sm text-zinc-400">Plans questions, searches your knowledge base, checks for gaps, then writes a report.</p>
      <div className="mt-4 flex gap-2">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && start()}
          placeholder="e.g. What are the main risks in the PHP vulnerabilities and how do they relate to AI-driven attacks?"
          className="flex-1 rounded-lg border border-white/10 bg-white/5 p-3 text-sm outline-none focus:border-violet-400"
        />
        <button onClick={start} className="rounded-lg bg-violet-500 px-5 text-sm font-medium hover:bg-violet-400">Start</button>
      </div>
      {error && <p className="mt-3 text-sm text-red-400">{error}</p>}

      {current && (
        <section className="mt-8">
          <h2 className="font-medium">{current.query}</h2>
          {current.phase === "FAILED" ? (
            <p className="mt-3 text-sm text-red-400">This session failed. Check the backend logs.</p>
          ) : (
            <ol className="mt-4 flex gap-2">
              {PHASES.map((p, i) => (
                <li key={p} className="flex-1">
                  <div className={`h-1.5 rounded-full ${i < phaseIdx || current.phase === "COMPLETED" ? "bg-violet-400" : i === phaseIdx ? "animate-pulse bg-violet-400/70" : "bg-white/10"}`} />
                  <div className="mt-1 text-[11px] uppercase tracking-wide text-zinc-500">{p.toLowerCase()}</div>
                </li>
              ))}
            </ol>
          )}

          {questions.length > 0 && (
            <div className="mt-6 rounded-lg border border-white/10 bg-white/[0.03] p-4">
              <div className="mb-2 text-sm font-medium text-zinc-300">Research plan</div>
              <ul className="space-y-1.5 text-sm text-zinc-400">
                {questions.map((q) => (
                  <li key={q.id} className="flex gap-2"><span className="text-zinc-600">{q.id}.</span>{q.question}</li>
                ))}
              </ul>
            </div>
          )}

          {current.findings && current.phase !== "COMPLETED" && (
            <details className="mt-4 rounded-lg border border-white/10 bg-white/[0.03] p-4 text-sm">
              <summary className="cursor-pointer text-zinc-300">Findings so far</summary>
              <div className="mt-2"><Markdown>{current.findings}</Markdown></div>
            </details>
          )}

          {current.report && (
            <article className="mt-6 rounded-lg border border-white/10 bg-white/[0.03] p-6">
              <Markdown>{current.report}</Markdown>
            </article>
          )}
        </section>
      )}

      <section className="mt-10">
        <h2 className="text-sm font-medium text-zinc-300">Past sessions</h2>
        <ul className="mt-2 space-y-1.5">
          {sessions.length === 0 && <li className="text-sm text-zinc-500">None yet.</li>}
          {sessions.map((s) => (
            <li key={s.id}>
              <button onClick={() => setCurrent(s)} className="flex w-full items-center gap-3 rounded-lg border border-white/10 px-3 py-2 text-left text-sm hover:bg-white/5">
                <span className="truncate">{s.query}</span>
                <span className={`ml-auto shrink-0 text-xs ${s.phase === "COMPLETED" ? "text-emerald-400" : s.phase === "FAILED" ? "text-red-400" : "text-amber-400"}`}>{s.phase.toLowerCase()}</span>
              </button>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

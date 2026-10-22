"use client";
import { useEffect, useState } from "react";
import { api, del, parseMeta, type KnowledgeNode, type SearchHit } from "@/lib/api";
import SourceBadge from "@/components/SourceBadge";
import DeleteButton from "@/components/DeleteButton";

export default function LibraryPage() {
  const [nodes, setNodes] = useState<KnowledgeNode[] | null>(null);
  const [hits, setHits] = useState<SearchHit[] | null>(null);
  const [q, setQ] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api<KnowledgeNode[]>("/api/knowledge/nodes").then(setNodes).catch((e) => setError(e.message));
  }, []);

  async function remove(id: string) {
    await del(`/api/knowledge/nodes/${id}`);
    setNodes((ns) => ns?.filter((n) => n.id !== id) ?? null);
    setHits((hs) => hs?.filter((h) => h.nodeId !== id) ?? null);
    setOpen((o) => (o === id ? null : o));
  }

  async function search() {
    if (!q.trim()) return setHits(null);
    try {
      setHits(await api<SearchHit[]>(`/api/knowledge/search?q=${encodeURIComponent(q)}&limit=10`));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="mx-auto max-w-3xl">
      <h1 className="text-2xl font-semibold">Library</h1>
      <div className="mt-4 flex gap-2">
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && search()}
          placeholder="Semantic search…"
          className="flex-1 rounded-lg border border-white/10 bg-white/5 p-2.5 text-sm outline-none focus:border-violet-400"
        />
        <button onClick={search} className="rounded-lg bg-white/10 px-4 text-sm hover:bg-white/15">Search</button>
      </div>
      {error && <p className="mt-3 text-sm text-red-400">Backend error: {error}</p>}

      {hits && (
        <div className="mt-6">
          <div className="mb-2 flex items-center justify-between text-sm text-zinc-400">
            <span>{hits.length} matches</span>
            <button onClick={() => { setHits(null); setQ(""); }} className="hover:text-white">Clear</button>
          </div>
          <ul className="space-y-2">
            {hits.map((h, i) => (
              <li key={i} className="rounded-lg border border-white/10 bg-white/[0.03] p-3">
                <div className="flex items-center gap-2">
                  <SourceBadge source={h.source} />
                  <span className="font-medium">{h.title}</span>
                  <span className="ml-auto text-xs text-zinc-500">{(h.score * 100).toFixed(0)}% match</span>
                </div>
                <p className="mt-1 text-sm text-zinc-400">{h.snippet}</p>
                <div className="mt-2"><DeleteButton onDelete={() => remove(h.nodeId)} /></div>
              </li>
            ))}
          </ul>
        </div>
      )}

      {!hits && (
        <ul className="mt-6 space-y-2">
          {nodes === null && !error && <li className="text-sm text-zinc-500">Loading…</li>}
          {nodes?.length === 0 && <li className="text-sm text-zinc-500">Empty so far — add something on the Add page.</li>}
          {nodes?.map((n) => {
            const meta = parseMeta(n.metadata);
            const expanded = open === n.id;
            return (
              <li key={n.id} className="rounded-lg border border-white/10 bg-white/[0.03] p-4">
                <button className="w-full text-left" onClick={() => setOpen(expanded ? null : n.id)}>
                  <div className="flex items-center gap-2">
                    <SourceBadge source={n.source} />
                    <span className="font-medium">{n.title}</span>
                    <span className="ml-auto text-xs text-zinc-500">{new Date(n.createdAt).toLocaleDateString()}</span>
                  </div>
                  <p className="mt-1 text-sm text-zinc-400">{n.summary}</p>
                  {meta.tags && (
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      {meta.tags.map((t) => (
                        <span key={t} className="rounded bg-white/5 px-1.5 py-0.5 text-xs text-zinc-400">#{t}</span>
                      ))}
                    </div>
                  )}
                </button>
                <div className="mt-3"><DeleteButton onDelete={() => remove(n.id)} /></div>
                {expanded && (
                  <div className="mt-3 border-t border-white/10 pt-3">
                    {n.sourceUrl && (
                      <a href={n.sourceUrl} target="_blank" rel="noreferrer" className="text-sm text-violet-400 hover:underline">
                        {n.sourceUrl}
                      </a>
                    )}
                    <pre className="mt-2 max-h-96 overflow-auto whitespace-pre-wrap text-sm text-zinc-300">{n.content}</pre>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

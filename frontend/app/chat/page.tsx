"use client";
import { useEffect, useRef, useState } from "react";
import { post, type ChatSource } from "@/lib/api";
import SourceBadge from "@/components/SourceBadge";
import Markdown from "@/components/Markdown";

type Msg = { role: "user" | "assistant"; text: string; sources?: ChatSource[] };

export default function ChatPage() {
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const sessionId = useRef("");
  const end = useRef<HTMLDivElement>(null);

  useEffect(() => {
    sessionId.current = crypto.randomUUID();
  }, []);
  useEffect(() => {
    end.current?.scrollIntoView({ behavior: "smooth" });
  }, [msgs, busy]);

  async function send() {
    const text = input.trim();
    if (!text || busy) return;
    setInput("");
    setMsgs((m) => [...m, { role: "user", text }]);
    setBusy(true);
    try {
      const r = await post<{ response: string; sources: ChatSource[] }>("/api/chat", { message: text, sessionId: sessionId.current });
      setMsgs((m) => [...m, { role: "assistant", text: r.response, sources: r.sources }]);
    } catch (e) {
      setMsgs((m) => [...m, { role: "assistant", text: `⚠️ Request failed: ${(e as Error).message}` }]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto flex h-[calc(100vh-4rem)] max-w-3xl flex-col">
      <h1 className="text-2xl font-semibold">Chat</h1>
      <p className="text-sm text-zinc-400">Answers come only from your knowledge base. If it can't answer, it asks before using outside knowledge.</p>
      <div className="mt-4 flex-1 space-y-4 overflow-y-auto pr-1">
        {msgs.length === 0 && <p className="text-sm text-zinc-500">Ask anything about your saved notes, videos and papers.</p>}
        {msgs.map((m, i) => (
          <div key={i} className={m.role === "user" ? "flex justify-end" : ""}>
            <div className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm ${m.role === "user" ? "bg-violet-500 text-white" : "border border-white/10 bg-white/[0.04]"}`}>
              {m.role === "user" ? m.text : <Markdown>{m.text}</Markdown>}
              {m.sources && m.sources.length > 0 && (
                <div className="mt-3 border-t border-white/10 pt-2">
                  <div className="mb-1.5 text-[11px] uppercase tracking-wide text-zinc-500">Sources</div>
                  <ul className="space-y-1.5">
                    {m.sources.map((s) => (
                      <li key={s.id} className="flex flex-wrap items-center gap-2 text-xs">
                        <SourceBadge source={s.source} />
                        {s.url ? (
                          <a href={s.url} target="_blank" rel="noreferrer" className="text-zinc-300 hover:text-violet-300 hover:underline">{s.title}</a>
                        ) : (
                          <span className="text-zinc-300">{s.title}</span>
                        )}
                        {s.relation !== "retrieved" && <span className="rounded bg-amber-500/15 px-1.5 py-0.5 text-amber-300">linked: {s.relation.replace(/^graph: related \(?/, "").replace(/\)$/, "")}</span>}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          </div>
        ))}
        {busy && <div className="text-sm text-zinc-500">Thinking…</div>}
        <div ref={end} />
      </div>
      <div className="mt-3 flex gap-2">
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && send()}
          placeholder="Ask your knowledge base…"
          className="flex-1 rounded-lg border border-white/10 bg-white/5 p-3 text-sm outline-none focus:border-violet-400"
        />
        <button onClick={send} disabled={busy} className="rounded-lg bg-violet-500 px-5 text-sm font-medium hover:bg-violet-400 disabled:opacity-50">Send</button>
      </div>
    </div>
  );
}

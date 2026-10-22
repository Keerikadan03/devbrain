"use client";
import { useEffect, useRef, useState } from "react";
import { api, del, sourceColor, type GraphData, type KnowledgeNode } from "@/lib/api";
import SourceBadge from "@/components/SourceBadge";
import DeleteButton from "@/components/DeleteButton";

type SimNode = { id: string; title: string; source: string; x: number; y: number; vx: number; vy: number };

export default function GraphPage() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [data, setData] = useState<GraphData | null>(null);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<KnowledgeNode | null>(null);
  const [hover, setHover] = useState<string | null>(null);
  const sim = useRef<{ nodes: SimNode[]; byId: Map<string, SimNode> }>({ nodes: [], byId: new Map() });
  const hoverRef = useRef<string | null>(null);
  const selectedId = useRef<string | null>(null);

  useEffect(() => {
    api<GraphData>("/api/knowledge/graph").then(setData).catch((e) => setError(e.message));
  }, []);

  useEffect(() => {
    if (!data) return;
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext("2d")!;
    const dpr = window.devicePixelRatio || 1;
    let w = 0, h = 0;
    const resize = () => {
      const r = canvas.getBoundingClientRect();
      w = r.width; h = r.height;
      canvas.width = w * dpr; canvas.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();
    window.addEventListener("resize", resize);

    const nodes: SimNode[] = data.nodes.map((n, i) => {
      const a = (i / Math.max(data.nodes.length, 1)) * Math.PI * 2;
      return { ...n, x: w / 2 + Math.cos(a) * 200, y: h / 2 + Math.sin(a) * 200, vx: 0, vy: 0 };
    });
    const byId = new Map(nodes.map((n) => [n.id, n]));
    sim.current = { nodes, byId };
    const edges = data.edges.filter((e) => byId.has(e.from) && byId.has(e.to));

    let raf = 0;
    let alpha = 1;
    const tick = () => {
      if (alpha > 0.01) {
        for (let i = 0; i < nodes.length; i++) {
          for (let j = i + 1; j < nodes.length; j++) {
            const a = nodes[i], b = nodes[j];
            let dx = a.x - b.x, dy = a.y - b.y;
            const d2 = dx * dx + dy * dy + 0.01;
            const f = (14000 / d2) * alpha;
            const d = Math.sqrt(d2);
            dx /= d; dy /= d;
            a.vx += dx * f; a.vy += dy * f; b.vx -= dx * f; b.vy -= dy * f;
          }
        }
        for (const e of edges) {
          const a = byId.get(e.from)!, b = byId.get(e.to)!;
          const dx = b.x - a.x, dy = b.y - a.y;
          const d = Math.sqrt(dx * dx + dy * dy) + 0.01;
          const f = (d - 170) * 0.02 * alpha;
          a.vx += (dx / d) * f; a.vy += (dy / d) * f; b.vx -= (dx / d) * f; b.vy -= (dy / d) * f;
        }
        for (const n of nodes) {
          n.vx += (w / 2 - n.x) * 0.0015 * alpha; n.vy += (h / 2 - n.y) * 0.0015 * alpha;
          n.vx *= 0.85; n.vy *= 0.85;
          n.x += n.vx; n.y += n.vy;
          n.x = Math.min(Math.max(n.x, 40), w - 160); // keep nodes (and their labels) inside the canvas
          n.y = Math.min(Math.max(n.y, 30), h - 30);
        }
        alpha *= 0.99;
      }
      ctx.clearRect(0, 0, w, h);
      for (const e of edges) {
        const a = byId.get(e.from)!, b = byId.get(e.to)!;
        ctx.strokeStyle = `rgba(167,139,250,${0.35 + e.score * 0.5})`;
        ctx.lineWidth = 1 + e.score * 2.5;
        ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
      }
      for (const n of nodes) {
        const active = n.id === hoverRef.current || n.id === selectedId.current;
        ctx.fillStyle = sourceColor(n.source);
        ctx.beginPath(); ctx.arc(n.x, n.y, active ? 10 : 7, 0, Math.PI * 2); ctx.fill();
        if (active || nodes.length <= 25) {
          ctx.fillStyle = "#e4e4e7"; ctx.font = "12px system-ui";
          ctx.fillText(n.title.length > 32 ? n.title.slice(0, 31) + "…" : n.title, n.x + 12, n.y + 4);
        }
      }
      raf = requestAnimationFrame(tick);
    };
    tick();
    return () => { cancelAnimationFrame(raf); window.removeEventListener("resize", resize); };
  }, [data]);

  function pick(ev: React.MouseEvent) {
    const r = canvasRef.current!.getBoundingClientRect();
    const x = ev.clientX - r.left, y = ev.clientY - r.top;
    return sim.current.nodes.find((n) => (n.x - x) ** 2 + (n.y - y) ** 2 < 256) ?? null;
  }

  async function removeSelected() {
    if (!selected) return;
    await del(`/api/knowledge/nodes/${selected.id}`);
    selectedId.current = null;
    setSelected(null);
    setData(await api<GraphData>("/api/knowledge/graph")); // re-layout without the removed node
  }

  async function onClick(ev: React.MouseEvent) {
    const n = pick(ev);
    selectedId.current = n?.id ?? null;
    if (!n) return setSelected(null);
    try { setSelected(await api<KnowledgeNode>(`/api/knowledge/nodes/${n.id}`)); } catch { /* ignore */ }
  }

  const sources = [...new Set(data?.nodes.map((n) => n.source) ?? [])];
  const titleOf = new Map(data?.nodes.map((n) => [n.id, n.title]) ?? []);
  const links = !selected || !data ? [] : data.edges
    .filter((e) => e.from === selected.id || e.to === selected.id)
    .map((e) => ({ title: titleOf.get(e.from === selected.id ? e.to : e.from) ?? "", kind: e.kind, score: e.score, via: e.via }));

  return (
    <div className="flex h-[calc(100vh-4rem)] flex-col">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">Knowledge graph</h1>
        {data && <span className="text-sm text-zinc-500">{data.nodes.length} nodes · {data.edges.length} links</span>}
        <div className="ml-auto flex flex-wrap gap-3 text-xs">
          <span className="flex items-center gap-1.5 text-zinc-400"><i className="inline-block h-0.5 w-5 bg-violet-400" />related (same subject)</span>
          {sources.map((s) => (
            <span key={s} className="flex items-center gap-1.5 text-zinc-400">
              <i className="h-2.5 w-2.5 rounded-full" style={{ background: sourceColor(s) }} />{s}
            </span>
          ))}
        </div>
      </div>
      {error && <p className="mt-3 text-sm text-red-400">Backend error: {error}</p>}
      {data && data.nodes.length === 0 && <p className="mt-6 text-sm text-zinc-500">No nodes yet. Add content first; related items get linked automatically.</p>}
      <div className="relative mt-4 flex-1 overflow-hidden rounded-xl border border-white/10 bg-white/[0.02]">
        <canvas
          ref={canvasRef}
          className="h-full w-full cursor-pointer"
          onMouseMove={(e) => { const n = pick(e); hoverRef.current = n?.id ?? null; setHover(n?.id ?? null); }}
          onClick={onClick}
          style={{ cursor: hover ? "pointer" : "default" }}
        />
        {selected && (
          <aside className="absolute right-3 top-3 max-h-[calc(100%-1.5rem)] w-80 overflow-auto rounded-lg border border-white/10 bg-zinc-900/95 p-4 text-sm shadow-xl">
            <div className="flex items-start justify-between gap-2">
              <SourceBadge source={selected.source} />
              <button onClick={() => { setSelected(null); selectedId.current = null; }} className="text-zinc-500 hover:text-white">✕</button>
            </div>
            <h2 className="mt-2 font-semibold">{selected.title}</h2>
            <p className="mt-2 text-zinc-400">{selected.summary}</p>
            {selected.sourceUrl && <a href={selected.sourceUrl} target="_blank" rel="noreferrer" className="mt-2 block truncate text-violet-400 hover:underline">{selected.sourceUrl}</a>}
            <div className="mt-3"><DeleteButton onDelete={removeSelected} label="Delete this source" /></div>
            {links.length > 0 && (
              <div className="mt-3 border-t border-white/10 pt-3">
                <div className="mb-1.5 text-[11px] uppercase tracking-wide text-zinc-500">Connected to</div>
                <ul className="space-y-1.5">
                  {links.map((l, i) => (
                    <li key={i} className="text-xs">
                      <span className="text-zinc-200">{l.title}</span>
                      <div className="mt-0.5 text-violet-300/90">{l.via}</div>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </aside>
        )}
      </div>
    </div>
  );
}

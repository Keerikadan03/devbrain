"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import * as d3 from "d3";
import {
  api,
  del,
  sourceColor,
  type GraphData,
  type GraphEdgeData,
  type GraphNodeData,
  type KnowledgeNode,
} from "@/lib/api";
import SourceBadge from "@/components/SourceBadge";
import DeleteButton from "@/components/DeleteButton";

type SimNode = GraphNodeData & d3.SimulationNodeDatum;
type SimLink = Omit<GraphEdgeData, "source" | "target"> & d3.SimulationLinkDatum<SimNode>;
type Tooltip = { x: number; y: number; title: string; body?: string; color?: string } | null;

const BG = "#1a1a2e";
const NODE_RING = "rgba(255,255,255,0.35)";
// All nodes are the same size; how connected a node is shows in its edges, not its size.
const NODE_RADIUS = 9;
const label = (code: string) => code.replace(/_/g, " ");

export default function GraphPage() {
  const wrapRef = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const [data, setData] = useState<GraphData | null>(null);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<KnowledgeNode | null>(null);
  const [tooltip, setTooltip] = useState<Tooltip>(null);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const hiddenRef = useRef(hidden);
  const selectedIdRef = useRef<string | null>(null);

  const load = useCallback(() => {
    api<GraphData>("/api/knowledge/graph").then(setData).catch((e) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  const select = useCallback(async (id: string | null) => {
    selectedIdRef.current = id;
    d3.select(svgRef.current).selectAll<SVGCircleElement, SimNode>("circle.node")
      .attr("stroke-width", (d) => (d.id === id ? 3 : 1.5))
      .attr("stroke", (d) => (d.id === id ? "#ffffff" : NODE_RING));
    if (!id) return setSelected(null);
    try {
      setSelected(await api<KnowledgeNode>(`/api/knowledge/nodes/${id}`));
    } catch {
      setSelected(null);
    }
  }, []);

  // Build the d3 scene whenever the data changes.
  useEffect(() => {
    if (!data || !svgRef.current || !wrapRef.current) return;
    const { width, height } = wrapRef.current.getBoundingClientRect();
    const svg = d3.select(svgRef.current).attr("viewBox", `0 0 ${width} ${height}`);
    svg.selectAll("*").remove();

    const nodes: SimNode[] = data.nodes.map((n) => ({ ...n }));
    const byId = new Map(nodes.map((n) => [n.id, n]));
    const links: SimLink[] = data.edges
      .filter((e) => byId.has(e.source) && byId.has(e.target))
      .map((e) => ({ ...e }));
    const clusterOf = new Map<string, number>();
    data.clusters.forEach((c, i) => c.nodeIds.forEach((id) => clusterOf.set(id, i)));

    // Arrowheads, one per relationship color, and a soft glow for nodes.
    const defs = svg.append("defs");
    for (const t of data.relationTypes) {
      defs.append("marker")
        .attr("id", `arrow-${t.code}`)
        .attr("viewBox", "0 -5 10 10")
        .attr("refX", 9).attr("refY", 0)
        .attr("markerWidth", 7).attr("markerHeight", 7)
        .attr("orient", "auto")
        .append("path").attr("d", "M0,-5L10,0L0,5").attr("fill", t.color);
    }
    const glow = defs.append("filter").attr("id", "glow").attr("x", "-50%").attr("y", "-50%").attr("width", "200%").attr("height", "200%");
    glow.append("feGaussianBlur").attr("stdDeviation", 2.5).attr("result", "blur");
    const merge = glow.append("feMerge");
    merge.append("feMergeNode").attr("in", "blur");
    merge.append("feMergeNode").attr("in", "SourceGraphic");

    const root = svg.append("g");
    // Semantic zoom: the zoom transform is applied to node *positions* only. Nodes, labels and lines keep
    // their on-screen size, so zooming in spreads crowded nodes apart instead of magnifying them.
    let transform = d3.zoomIdentity;
    let fullLabels = false;
    const X = (n: SimNode) => transform.applyX(n.x ?? 0);
    const Y = (n: SimNode) => transform.applyY(n.y ?? 0);
    svg.call(
      d3.zoom<SVGSVGElement, unknown>()
        .scaleExtent([0.1, 8])
        .on("zoom", (ev) => {
          transform = ev.transform;
          updateLabelText();
          render();
        }),
    ).on("dblclick.zoom", null);
    svg.on("click", (ev) => { if (ev.target === svgRef.current) select(null); });

    const hullLayer = root.append("g");
    const hulls = hullLayer.selectAll("path").data(data.clusters).join("path")
      .attr("fill", "rgba(200,200,220,0.1)").attr("stroke", "rgba(200,200,220,0.45)")
      .attr("stroke-dasharray", "6 4").attr("stroke-width", 1.2)
      .on("mousemove", (ev, c) => setTooltip({
        ...pointerIn(wrapRef.current, ev),
        title: `Cluster · ${c.size} documents`,
        body: "Grouped because the classifier judged them to be about the same concept (same concept, strength 80%+).",
      }))
      .on("mouseleave", () => setTooltip(null));
    const hullLabels = hullLayer.selectAll("text").data(data.clusters).join("text")
      .text((c) => `Cluster · ${c.label.length > 36 ? c.label.slice(0, 35) + "…" : c.label}`)
      .attr("fill", "rgba(220,220,235,0.75)").attr("font-size", 11).attr("text-anchor", "middle");

    const edge = root.append("g").selectAll<SVGLineElement, SimLink>("line").data(links).join("line")
      .attr("class", "edge")
      .attr("stroke", (d) => d.color)
      .attr("stroke-width", (d) => 1 + d.score * 3)
      .attr("stroke-opacity", (d) => 0.3 + d.score * 0.5)
      .attr("marker-end", (d) => (d.directional ? `url(#arrow-${d.relationType})` : null));

    // Wide invisible twin of each edge so thin lines are easy to hover.
    const edgeHit = root.append("g").selectAll<SVGLineElement, SimLink>("line").data(links).join("line")
      .attr("class", "edge-hit").attr("stroke", "transparent").attr("stroke-width", 12).style("cursor", "help")
      .on("mousemove", (ev, d) => {
        const s = d.source as SimNode, t = d.target as SimNode;
        setTooltip({
          ...pointerIn(wrapRef.current, ev),
          color: d.color,
          title: `${label(d.relationType)} — ${Math.round(d.score * 100)}%`,
          body: `${s.title}${d.directional ? ` → ${label(d.relationType)} → ` : " ↔ "}${t.title}\n${d.explanation}`,
        });
      })
      .on("mouseleave", () => setTooltip(null));

    const node = root.append("g").selectAll<SVGCircleElement, SimNode>("circle").data(nodes).join("circle")
      .attr("class", "node")
      .attr("r", NODE_RADIUS)
      .attr("fill", (d) => sourceColor(d.source))
      .attr("stroke", (d) => (d.id === selectedIdRef.current ? "#ffffff" : NODE_RING))
      .attr("stroke-width", (d) => (d.id === selectedIdRef.current ? 3 : 1.5))
      .attr("filter", "url(#glow)")
      .style("cursor", "pointer")
      .on("mousemove", (ev, d) => {
        const summary = d.summary ?? "";
        setTooltip({
          ...pointerIn(wrapRef.current, ev),
          title: d.title,
          body: `${d.source} · ${d.connectionCount} connection${d.connectionCount === 1 ? "" : "s"}\n${summary.length > 100 ? summary.slice(0, 100) + "…" : summary}`,
        });
      })
      .on("mouseleave", () => setTooltip(null))
      .on("click", (ev, d) => { ev.stopPropagation(); select(d.id); });

    const labels = root.append("g").selectAll<SVGTextElement, SimNode>("text").data(nodes).join("text")
      .text((d) => (d.title.length > 32 ? d.title.slice(0, 31) + "…" : d.title))
      .attr("fill", "#ffffff").attr("font-size", 10.5).attr("pointer-events", "none");

    // Members of a cluster are pulled toward their shared centroid.
    const clusterForce = (alpha: number) => {
      data.clusters.forEach((c) => {
        const members = c.nodeIds.map((id) => byId.get(id)).filter((n): n is SimNode => !!n);
        const cx = d3.mean(members, (n) => n.x) ?? 0, cy = d3.mean(members, (n) => n.y) ?? 0;
        for (const n of members) {
          n.vx = (n.vx ?? 0) + (cx - (n.x ?? 0)) * 0.15 * alpha;
          n.vy = (n.vy ?? 0) + (cy - (n.y ?? 0)) * 0.15 * alpha;
        }
      });
    };

    const sim = d3.forceSimulation<SimNode>(nodes)
      .force("link", d3.forceLink<SimNode, SimLink>(links).id((d) => d.id)
        // high score = short, stiff link; cluster members sit even closer
        .distance((l) => {
          const s = l.source as SimNode, t = l.target as SimNode;
          const same = clusterOf.has(s.id) && clusterOf.get(s.id) === clusterOf.get(t.id);
          return Math.max(40, (1 - l.score) * 300) * (same ? 0.6 : 1);
        })
        .strength((l) => l.score * 0.7))
      .force("charge", d3.forceManyBody().strength(-300))
      .force("center", d3.forceCenter(width / 2, height / 2))
      // keep unconnected nodes from drifting out of view
      .force("x", d3.forceX(width / 2).strength(0.04))
      .force("y", d3.forceY(height / 2).strength(0.04))
      .force("collide", d3.forceCollide<SimNode>(NODE_RADIUS + 6))
      .force("cluster", clusterForce);

    node.call(
      d3.drag<SVGCircleElement, SimNode>()
        .clickDistance(4)
        // drag in screen coordinates, then convert back to simulation coordinates for the current zoom
        .subject((_, d) => ({ x: X(d), y: Y(d) }))
        .on("start", (ev, d) => { if (!ev.active) sim.alphaTarget(0.3).restart(); d.fx = d.x; d.fy = d.y; })
        .on("drag", (ev, d) => { d.fx = transform.invertX(ev.x); d.fy = transform.invertY(ev.y); })
        .on("end", (ev, d) => { if (!ev.active) sim.alphaTarget(0); d.fx = null; d.fy = null; }),
    );

    // Long titles are truncated at normal zoom and shown in full once zoomed in.
    function updateLabelText() {
      const full = transform.k >= 1.6;
      if (full === fullLabels) return;
      fullLabels = full;
      labels.text((d) => (full || d.title.length <= 32 ? d.title : d.title.slice(0, 31) + "…"));
    }

    function render() {
      // Stop lines at the circle edge so arrowheads stay visible.
      const ends = (d: SimLink) => {
        const s = d.source as SimNode, t = d.target as SimNode;
        const sx = X(s), sy = Y(s), tx = X(t), ty = Y(t);
        const dx = tx - sx, dy = ty - sy;
        const len = Math.hypot(dx, dy) || 1;
        const rs = NODE_RADIUS + 1, rt = NODE_RADIUS + (d.directional ? 3 : 1);
        return { x1: sx + (dx / len) * rs, y1: sy + (dy / len) * rs, x2: tx - (dx / len) * rt, y2: ty - (dy / len) * rt };
      };
      for (const sel of [edge, edgeHit]) {
        sel.each(function (d) {
          const p = ends(d);
          d3.select(this).attr("x1", p.x1).attr("y1", p.y1).attr("x2", p.x2).attr("y2", p.y2);
        });
      }
      node.attr("cx", X).attr("cy", Y);
      labels.attr("x", (d) => X(d) + NODE_RADIUS + 4).attr("y", (d) => Y(d) + 4);

      hulls.attr("d", (c) => {
        const pts: [number, number][] = [];
        for (const id of c.nodeIds) {
          const n = byId.get(id);
          if (!n) continue;
          const r = NODE_RADIUS + 18;
          for (let k = 0; k < 10; k++) pts.push([X(n) + r * Math.cos((k / 10) * 2 * Math.PI), Y(n) + r * Math.sin((k / 10) * 2 * Math.PI)]);
        }
        const hull = d3.polygonHull(pts);
        return hull ? d3.line().curve(d3.curveCatmullRomClosed)(hull) : null;
      });
      hullLabels.each(function (c) {
        const members = c.nodeIds.map((id) => byId.get(id)).filter((n): n is SimNode => !!n);
        const cx = d3.mean(members, X) ?? 0;
        const top = (d3.min(members, (n) => Y(n) - NODE_RADIUS) ?? 0) - 26;
        d3.select(this).attr("x", cx).attr("y", top);
      });
    }

    sim.on("tick", render);

    applyHidden(svgRef.current, hiddenRef.current);
    return () => { sim.stop(); };
  }, [data, select]);

  // Legend filter: hide/show edges of a type without restarting the simulation.
  useEffect(() => {
    hiddenRef.current = hidden;
    applyHidden(svgRef.current, hidden);
  }, [hidden]);

  function toggle(code: string) {
    setHidden((h) => {
      const next = new Set(h);
      if (next.has(code)) next.delete(code); else next.add(code);
      return next;
    });
  }

  async function removeSelected() {
    if (!selected) return;
    await del(`/api/knowledge/nodes/${selected.id}`);
    selectedIdRef.current = null;
    setSelected(null);
    load();
  }

  const titleOf = new Map(data?.nodes.map((n) => [n.id, n.title]) ?? []);
  const sourceCounts = new Map<string, number>();
  data?.nodes.forEach((n) => sourceCounts.set(n.source, (sourceCounts.get(n.source) ?? 0) + 1));
  const sources = [...sourceCounts.keys()].sort();
  const counts = new Map<string, number>();
  data?.edges.forEach((e) => counts.set(e.relationType, (counts.get(e.relationType) ?? 0) + 1));
  const links = !selected || !data ? [] : data.edges
    .filter((e) => e.source === selected.id || e.target === selected.id)
    .map((e) => ({ ...e, other: titleOf.get(e.source === selected.id ? e.target : e.source) ?? "", outgoing: e.source === selected.id }));

  return (
    <div className="flex h-[calc(100vh-4rem)] flex-col">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
        <h1 className="text-2xl font-semibold">Knowledge graph</h1>
        {data && (
          <span className="text-sm text-zinc-500">
            {data.nodes.length} nodes · {data.edges.length} relationships · {data.clusters.length} clusters
          </span>
        )}
        <span className="text-xs text-zinc-500">Scroll to zoom (spreads nodes apart) · drag background to pan · drag nodes · hover edges for why</span>
      </div>
      {error && <p className="mt-3 text-sm text-red-400">Backend error: {error}</p>}

      <div ref={wrapRef} className="relative mt-4 flex-1 overflow-hidden rounded-xl border border-white/10" style={{ background: BG }}>
        <svg ref={svgRef} className="h-full w-full" />
        {data && data.nodes.length === 0 && (
          <p className="absolute inset-0 grid place-items-center text-sm text-zinc-400">No nodes yet. Add content; related items get linked automatically.</p>
        )}

        {data && (
          <div className="absolute left-3 top-3 w-56 rounded-lg border border-white/10 bg-[#2d2d44]/90 p-3 text-xs shadow-xl backdrop-blur">
            <div className="mb-2 flex items-center justify-between">
              <span className="font-medium text-zinc-200">Relationships</span>
              {hidden.size > 0 && <button onClick={() => setHidden(new Set())} className="text-zinc-400 hover:text-white">show all</button>}
            </div>
            <ul className="space-y-1">
              {data.relationTypes.map((t) => {
                const off = hidden.has(t.code);
                return (
                  <li key={t.code}>
                    <button
                      onClick={() => toggle(t.code)}
                      title={t.description}
                      className={`flex w-full items-center gap-2 rounded px-1 py-0.5 text-left hover:bg-white/5 ${off ? "opacity-35" : ""}`}
                    >
                      <span className="inline-block h-0.5 w-5 shrink-0" style={{ background: t.color }} />
                      <span className="text-zinc-200">{t.label}</span>
                      {t.directional && <span className="text-zinc-400">→</span>}
                      <span className="ml-auto text-zinc-500">{counts.get(t.code) ?? 0}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
            <div className="mt-2 border-t border-white/10 pt-2">
              <div className="mb-1.5 font-medium text-zinc-200">Sources</div>
              <ul className="space-y-1">
                {sources.map((src) => (
                  <li key={src} className="flex items-center gap-2 px-1">
                    <span className="inline-block h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: sourceColor(src) }} />
                    <span className="text-zinc-200">{src}</span>
                    <span className="ml-auto text-zinc-500">{sourceCounts.get(src)}</span>
                  </li>
                ))}
              </ul>
              <div className="mt-2 flex items-center gap-2 px-1 text-[11px] text-zinc-500">
                <span className="inline-block h-3 w-5 shrink-0 rounded-full border border-dashed border-zinc-400/60" />
                cluster = same concept
              </div>
            </div>
          </div>
        )}

        {tooltip && (
          <div
            className="pointer-events-none absolute z-10 max-w-xs whitespace-pre-line rounded-lg bg-[#2d2d44] px-3 py-2 text-xs text-white shadow-xl"
            style={{ left: tooltip.x, top: tooltip.y }}
          >
            <div className="font-medium" style={tooltip.color ? { color: tooltip.color } : undefined}>{tooltip.title}</div>
            {tooltip.body && <div className="mt-1 text-zinc-300">{tooltip.body}</div>}
          </div>
        )}

        {selected && (
          <aside className="absolute right-3 top-3 max-h-[calc(100%-1.5rem)] w-80 overflow-auto rounded-lg border border-white/10 bg-[#2d2d44]/95 p-4 text-sm shadow-xl">
            <div className="flex items-start justify-between gap-2">
              <SourceBadge source={selected.source} />
              <button onClick={() => select(null)} className="text-zinc-500 hover:text-white">✕</button>
            </div>
            <h2 className="mt-2 font-semibold">{selected.title}</h2>
            <p className="mt-2 text-zinc-400">{selected.summary}</p>
            {selected.sourceUrl && <a href={selected.sourceUrl} target="_blank" rel="noreferrer" className="mt-2 block truncate text-violet-400 hover:underline">{selected.sourceUrl}</a>}
            <div className="mt-3"><DeleteButton onDelete={removeSelected} label="Delete this source" /></div>
            {links.length > 0 && (
              <div className="mt-3 border-t border-white/10 pt-3">
                <div className="mb-1.5 text-[11px] uppercase tracking-wide text-zinc-500">Relationships</div>
                <ul className="space-y-2">
                  {links.map((l, i) => (
                    <li key={i} className="text-xs">
                      <div className="flex items-center gap-1.5">
                        <span className="inline-block h-2 w-2 shrink-0 rounded-full" style={{ background: l.color }} />
                        <span style={{ color: l.color }}>
                          {l.directional ? (l.outgoing ? `${label(l.relationType)} →` : `← ${label(l.relationType)}`) : label(l.relationType)}
                        </span>
                        <span className="truncate text-zinc-200">{l.other}</span>
                      </div>
                      <div className="mt-0.5 pl-3.5 text-zinc-400">{l.explanation}</div>
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

/** Tooltip position relative to the graph container. */
function pointerIn(wrap: HTMLDivElement | null, ev: MouseEvent) {
  const r = wrap?.getBoundingClientRect();
  return { x: ev.clientX - (r?.left ?? 0) + 14, y: ev.clientY - (r?.top ?? 0) + 14 };
}

function applyHidden(svg: SVGSVGElement | null, hidden: Set<string>) {
  if (!svg) return;
  d3.select(svg).selectAll<SVGLineElement, SimLink>("line.edge, line.edge-hit")
    .attr("display", (d) => (hidden.has(d.relationType) ? "none" : null));
}

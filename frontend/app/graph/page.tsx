"use client";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import * as d3 from "d3";
import {
  api,
  del,
  sourceColor,
  type GraphData,
  type GraphEdgeData,
  type GraphNodeData,
  type KnowledgeNode,
  type SearchHit,
  type Topic,
} from "@/lib/api";
import SourceBadge from "@/components/SourceBadge";
import DeleteButton from "@/components/DeleteButton";

/**
 * Knowledge graph with collapsible topic groups.
 *
 * Documents are grouped into a hierarchy of named topics (computed by the backend). A collapsed group is
 * one bubble; zooming in opens groups that are on screen (deeper groups need more zoom), clicking a bubble
 * opens it, and clicking an open group's name closes it. Zoom is semantic: positions spread apart, sizes
 * stay the same. Relationships between collapsed groups are merged into one edge per pair.
 */

type Item =
  | { kind: "doc"; id: string; doc: GraphNodeData }
  | { kind: "group"; id: string; topic: Topic };
type SimItem = Item & d3.SimulationNodeDatum;
type AggEdge = {
  source: string | SimItem;
  target: string | SimItem;
  edges: GraphEdgeData[];
  count: number;
  score: number;
  relationType: string;
  color: string;
  directional: boolean;
};
type SimLink = AggEdge & d3.SimulationLinkDatum<SimItem>;
type Tooltip = { x: number; y: number; title: string; body?: string; color?: string } | null;

const BG = "#1a1a2e";
// All nodes are the same size; how connected a node is shows in its edges, not its size.
const NODE_RADIUS = 9;
const NODE_RING = "rgba(255,255,255,0.35)";
const HIT_RING = "#fbbf24"; // semantic search matches
const GROUP_FILL = "#3a3a5c";
/** Zoom needed to auto-open an on-screen group, by its depth (0 = top-level group). */
const AUTO_OPEN_AT = [1.6, 2.6, 4, 6];
/** Length of the open/close animation, ms. */
const ANIM_MS = 450;
const label = (code: string) => code.replace(/_/g, " ");

export default function GraphPage() {
  const wrapRef = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const [data, setData] = useState<GraphData | null>(null);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<KnowledgeNode | null>(null);
  const [tooltip, setTooltip] = useState<Tooltip>(null);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [autoOpen, setAutoOpen] = useState<Set<string>>(new Set());
  // group id -> zoom it was opened at by clicking; zooming back out below that closes it again
  const [manualOpen, setManualOpen] = useState<Map<string, number>>(new Map());
  const [manualClosed, setManualClosed] = useState<Set<string>>(new Set());
  const selectedIdRef = useRef<string | null>(null);
  // Semantic search: the query, the hits for the last query sent, and which documents to ring.
  const [query, setQuery] = useState("");
  const [result, setResult] = useState<{ q: string; hits: SearchHit[] }>({ q: "", hits: [] });
  const [searchFocused, setSearchFocused] = useState(false);
  const hitIds = useRef(new Set<string>());
  const positions = useRef(new Map<string, { x: number; y: number }>());
  // Items drawn in the last scene, so a rebuild knows what just opened or closed.
  const shown = useRef(new Set<string>());
  // group id -> where its members sat relative to it when it closed, so reopening restores that layout
  const layouts = useRef(new Map<string, Map<string, { x: number; y: number }>>());
  // Camera that glides to (and then follows) a clicked item; resolves an id against the current scene.
  const camera = useRef<d3.Timer | null>(null);
  const focusPoint = useRef<(id: string) => { x: number; y: number } | null>(() => null);
  const zoomEval = useRef<() => void>(() => {});
  // One zoom behaviour for the page's lifetime: d3 delivers zoom events (including animated ones)
  // to the behaviour that started them, so each scene rebuild only swaps the handler it calls.
  const onZoom = useRef<(t: d3.ZoomTransform) => void>(() => {});
  const zoomRef = useRef<d3.ZoomBehavior<SVGSVGElement, unknown> | null>(null);

  const load = useCallback(() => {
    api<GraphData>("/api/knowledge/graph").then(setData).catch((e) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  // ---- topic lookups: by id, depth (0 = top level), all documents inside ----
  const topics = useMemo(() => {
    const byId = new Map<string, Topic>();
    data?.topics.clusters.forEach((t) => byId.set(t.id, t));
    const depth = new Map<string, number>();
    const docsOf = new Map<string, string[]>();
    const depthOf = (t: Topic): number => {
      if (depth.has(t.id)) return depth.get(t.id)!;
      const d = t.parentId && byId.has(t.parentId) ? depthOf(byId.get(t.parentId)!) + 1 : 0;
      depth.set(t.id, d);
      return d;
    };
    const allDocs = (t: Topic): string[] => {
      if (docsOf.has(t.id)) return docsOf.get(t.id)!;
      const all = [...t.docIds, ...t.childClusterIds.flatMap((c) => (byId.has(c) ? allDocs(byId.get(c)!) : []))];
      docsOf.set(t.id, all);
      return all;
    };
    byId.forEach((t) => { depthOf(t); allDocs(t); });
    return { byId, depth, docsOf };
  }, [data]);

  const expanded = useMemo(() => {
    const s = new Set([...autoOpen, ...manualOpen.keys()]);
    manualClosed.forEach((id) => s.delete(id));
    return s;
  }, [autoOpen, manualOpen, manualClosed]);

  // ---- what is visible: documents and collapsed groups, plus the open groups around them ----
  const view = useMemo(() => {
    const items: Item[] = [];
    const repOf = new Map<string, string>(); // document id -> visible item that stands for it
    const openGroups: Topic[] = [];
    if (!data) return { items, repOf, openGroups };
    const docById = new Map(data.nodes.map((n) => [n.id, n]));
    const walk = (groupIds: string[], docIds: string[]) => {
      for (const gid of groupIds) {
        const t = topics.byId.get(gid);
        if (!t) continue;
        if (expanded.has(gid)) {
          openGroups.push(t);
          walk(t.childClusterIds, t.docIds);
        } else {
          items.push({ kind: "group", id: gid, topic: t });
          topics.docsOf.get(gid)?.forEach((d) => repOf.set(d, gid));
        }
      }
      for (const d of docIds) {
        const doc = docById.get(d);
        if (!doc) continue;
        items.push({ kind: "doc", id: d, doc });
        repOf.set(d, d);
      }
    };
    walk(data.topics.rootClusterIds, data.topics.rootDocIds);
    return { items, repOf, openGroups };
  }, [data, topics, expanded]);

  /** Outlines: white for the selected document, amber for search hits and for bubbles that contain one. */
  const paintRings = useCallback(() => {
    const sel = selectedIdRef.current, hits = hitIds.current;
    const svg = d3.select(svgRef.current);
    svg.selectAll<SVGCircleElement, SimItem>("circle.doc")
      .attr("stroke", (d) => (d.id === sel ? "#ffffff" : hits.has(d.id) ? HIT_RING : NODE_RING))
      .attr("stroke-width", (d) => (d.id === sel ? 3 : hits.has(d.id) ? 3 : 1.5));
    svg.selectAll<SVGCircleElement, SimItem>("circle.group-ring")
      .attr("stroke", (d) => ((topics.docsOf.get(d.id) ?? []).some((x) => hits.has(x)) ? HIT_RING : "#e8e8f0"));
  }, [topics]);

  const select = useCallback(async (id: string | null) => {
    selectedIdRef.current = id;
    paintRings();
    if (!id) return setSelected(null);
    try {
      setSelected(await api<KnowledgeNode>(`/api/knowledge/nodes/${id}`));
    } catch {
      setSelected(null);
    }
  }, [paintRings]);

  const openGroup = useCallback((id: string, atZoom: number) => {
    setManualClosed((s) => { const n = new Set(s); n.delete(id); return n; });
    setManualOpen((s) => new Map(s).set(id, atZoom));
  }, []);
  const closeGroup = useCallback((id: string) => {
    setManualOpen((s) => { const n = new Map(s); n.delete(id); return n; });
    setManualClosed((s) => new Set(s).add(id));
  }, []);

  /**
   * Glides the view so an item (document, bubble or open group) ends up in the centre, zooming in to at
   * least `minZoom`, then keeps it centred while the layout settles. Any user pan/zoom cancels it.
   */
  const focus = useCallback((id: string, minZoom = 1) => {
    camera.current?.stop();
    const el = svgRef.current, zoom = zoomRef.current;
    if (!el || !zoom) return;
    const k1 = Math.max(d3.zoomTransform(el).k, minZoom);
    const timer = d3.timer((ms) => {
      const p = focusPoint.current(id);
      if (ms > 4000) return timer.stop();
      if (!p) return ms > 1000 ? timer.stop() : undefined; // give a just-opened group a moment to show it
      const { width, height } = el.viewBox.baseVal;
      const t = d3.zoomTransform(el);
      const sx = t.applyX(p.x), sy = t.applyY(p.y);
      if (ms > 600 && Math.hypot(width / 2 - sx, height / 2 - sy) < 0.5 && Math.abs(k1 - t.k) < 0.01) return timer.stop();
      // ease the item's screen position toward the centre and the zoom toward its target
      const k = t.k + (k1 - t.k) * 0.12;
      const x = sx + (width / 2 - sx) * 0.12, y = sy + (height / 2 - sy) * 0.12;
      d3.select(el).call(zoom.transform, d3.zoomIdentity.translate(x - k * p.x, y - k * p.y).scale(k));
    });
    camera.current = timer;
  }, []);

  // ---- build the scene whenever what's visible (or the edge filter) changes ----
  useEffect(() => {
    if (!data || !svgRef.current || !wrapRef.current) return;
    const { width, height } = wrapRef.current.getBoundingClientRect();
    const svg = d3.select(svgRef.current).attr("viewBox", `0 0 ${width} ${height}`);
    svg.selectAll("*").remove();
    let transform = d3.zoomTransform(svgRef.current);
    let fullLabels = transform.k >= 1.6;
    const X = (n: SimItem) => transform.applyX(n.x ?? 0);
    const Y = (n: SimItem) => transform.applyY(n.y ?? 0);

    // Positions carry over between rebuilds. A group that just closed starts where its members were (they
    // are drawn sliding into it); items of a group that just opened grow out of its bubble, back to where
    // they sat when it last closed.
    const prev = positions.current;
    const before = [...shown.current].filter((id) => prev.has(id));
    const docById = new Map(data.nodes.map((n) => [n.id, n]));
    const docSets = new Map<string, Set<string>>();
    const inside = (id: string, gid: string) => {
      if (id === gid || !topics.byId.has(gid)) return false;
      if (!docSets.has(gid)) docSets.set(gid, new Set(topics.docsOf.get(gid)));
      const docs = topics.docsOf.get(id) ?? [id];
      return docs.length > 0 && docs.every((d) => docSets.get(gid)!.has(d));
    };
    const jitter = () => (Math.random() - 0.5) * 12;
    const ghosts: { from: { x: number; y: number }; to: SimItem; color: string }[] = [];
    const intro: { node: SimItem; from: { x: number; y: number }; to: { x: number; y: number } }[] = [];
    const nodes: SimItem[] = view.items.map((it) => {
      if (shown.current.has(it.id) && prev.has(it.id)) return { ...it, ...prev.get(it.id)! };
      const members = before.filter((id) => inside(id, it.id));
      if (members.length) {
        const pts = members.map((id) => prev.get(id)!);
        const c = { x: d3.mean(pts, (p) => p.x)!, y: d3.mean(pts, (p) => p.y)! };
        layouts.current.set(it.id, new Map(members.map((id, i) => [id, { x: pts[i].x - c.x, y: pts[i].y - c.y }])));
        const n: SimItem = { ...it, ...c };
        members.forEach((id, i) => ghosts.push({ from: pts[i], to: n, color: docById.has(id) ? sourceColor(docById.get(id)!.source) : GROUP_FILL }));
        return n;
      }
      const parent = before.find((id) => inside(it.id, id));
      if (parent) {
        const from = prev.get(parent)!, off = layouts.current.get(parent)?.get(it.id);
        const n: SimItem = { ...it, x: from.x + jitter(), y: from.y + jitter() };
        if (off) intro.push({ node: n, from, to: { x: from.x + off.x, y: from.y + off.y } });
        return n;
      }
      const p = prev.get(it.id);
      return { ...it, x: (p?.x ?? width / 2) + (p ? 0 : jitter()), y: (p?.y ?? height / 2) + (p ? 0 : jitter()) };
    });
    shown.current = new Set(nodes.map((n) => n.id));

    // Merge relationships between the visible representatives of their documents.
    const typeColor = new Map(data.relationTypes.map((t) => [t.code, t.color]));
    const agg = new Map<string, AggEdge>();
    for (const e of data.edges) {
      if (hidden.has(e.relationType)) continue;
      const a = view.repOf.get(e.source), b = view.repOf.get(e.target);
      if (!a || !b || a === b) continue;
      const key = a < b ? `${a}|${b}` : `${b}|${a}`;
      const cur = agg.get(key) ?? { source: a, target: b, edges: [], count: 0, score: 0, relationType: "", color: "", directional: false };
      cur.edges.push(e);
      agg.set(key, cur);
    }
    const links: SimLink[] = [...agg.values()].map((g) => {
      const tally = d3.rollup(g.edges, (v) => v.length, (e) => e.relationType);
      const top = [...tally.entries()].sort((x, y) => y[1] - x[1])[0][0];
      const single = g.edges.length === 1 ? g.edges[0] : null;
      return {
        ...g,
        // a single relationship keeps its own direction
        source: single ? view.repOf.get(single.source)! : g.source,
        target: single ? view.repOf.get(single.target)! : g.target,
        count: g.edges.length,
        score: d3.mean(g.edges, (e) => e.score) ?? 0.5,
        relationType: top,
        color: typeColor.get(top) ?? "#888",
        directional: !!single?.directional,
      };
    });

    // Which visible items sit inside each open group (for outlines and attraction).
    const membersOf = new Map<string, SimItem[]>();
    for (const g of view.openGroups) {
      const docs = new Set(topics.docsOf.get(g.id) ?? []);
      membersOf.set(g.id, nodes.filter((n) =>
        n.kind === "doc" ? docs.has(n.id) : (topics.docsOf.get(n.id) ?? []).every((d) => docs.has(d))));
    }

    // ---- defs ----
    const defs = svg.append("defs");
    for (const t of data.relationTypes) {
      defs.append("marker").attr("id", `arrow-${t.code}`).attr("viewBox", "0 -5 10 10")
        .attr("refX", 9).attr("refY", 0).attr("markerWidth", 7).attr("markerHeight", 7).attr("orient", "auto")
        .append("path").attr("d", "M0,-5L10,0L0,5").attr("fill", t.color);
    }
    const glow = defs.append("filter").attr("id", "glow").attr("x", "-50%").attr("y", "-50%").attr("width", "200%").attr("height", "200%");
    glow.append("feGaussianBlur").attr("stdDeviation", 2.5).attr("result", "blur");
    const merge = glow.append("feMerge");
    merge.append("feMergeNode").attr("in", "blur");
    merge.append("feMergeNode").attr("in", "SourceGraphic");

    const root = svg.append("g");

    // ---- open groups: outline + name + level ----
    const hullLayer = root.append("g");
    const openSorted = [...view.openGroups].sort((a, b) => b.level - a.level); // outer first
    const hulls = hullLayer.selectAll<SVGPathElement, Topic>("path").data(openSorted).join("path")
      .attr("fill", (t) => `rgba(200,200,230,${0.04 + 0.02 * Math.max(0, 4 - t.level)})`)
      .attr("stroke", "rgba(200,200,230,0.45)").attr("stroke-dasharray", "6 4").attr("stroke-width", 1.2)
      .on("mousemove", (ev, t) => setTooltip({
        ...pointerIn(wrapRef.current, ev),
        title: t.name,
        body: `level ${t.level} · ${t.size} documents${t.terms.length ? `\nKey concepts: ${t.terms.join(", ")}` : ""}\nClick the name to collapse`,
      }))
      .on("mouseleave", () => setTooltip(null));
    const hullLabels = hullLayer.selectAll<SVGTextElement, Topic>("text").data(openSorted).join("text")
      .attr("text-anchor", "middle").style("cursor", "pointer")
      .on("click", (ev, t) => {
        ev.stopPropagation();
        setTooltip(null);
        focus(t.id, 0); // the bubble comes back where its members are
        closeGroup(t.id);
      });
    hullLabels.append("tspan").attr("fill", "rgba(230,230,245,0.9)").attr("font-size", 12).attr("font-weight", 600)
      .text((t) => `▾ ${t.name}`);
    hullLabels.append("tspan").attr("fill", "rgba(200,200,220,0.6)").attr("font-size", 9.5).attr("dy", 13)
      .text((t) => `level ${t.level} · ${t.size} docs`);

    // ---- edges ----
    const edge = root.append("g").selectAll<SVGLineElement, SimLink>("line").data(links).join("line")
      .attr("class", "edge").attr("stroke", (d) => d.color)
      .attr("stroke-width", (d) => (d.count > 1 ? Math.min(8, 1.5 + Math.log2(d.count) * 1.4) : 1 + d.score * 3))
      .attr("stroke-opacity", (d) => (d.count > 1 ? 0.75 : 0.3 + d.score * 0.5))
      .attr("marker-end", (d) => (d.directional ? `url(#arrow-${d.relationType})` : null));
    const edgeHit = root.append("g").selectAll<SVGLineElement, SimLink>("line").data(links).join("line")
      .attr("stroke", "transparent").attr("stroke-width", 12).style("cursor", "help")
      .on("mousemove", (ev, d) => {
        const sName = nameOf(d.source as SimItem), tName = nameOf(d.target as SimItem);
        if (d.count === 1) {
          const e = d.edges[0];
          setTooltip({
            ...pointerIn(wrapRef.current, ev), color: d.color,
            title: `${label(e.relationType)} — ${Math.round(e.score * 100)}%`,
            body: `${sName}${e.directional ? ` → ${label(e.relationType)} → ` : " ↔ "}${tName}\n${e.explanation}`,
          });
        } else {
          const tally = [...d3.rollup(d.edges, (v) => v.length, (e) => e.relationType).entries()].sort((a, b) => b[1] - a[1]);
          setTooltip({
            ...pointerIn(wrapRef.current, ev), color: d.color,
            title: `${d.count} relationships`,
            body: `${sName} ↔ ${tName}\n${tally.map(([k, v]) => `${v} ${label(k)}`).join(" · ")}`,
          });
        }
      })
      .on("mouseleave", () => setTooltip(null));

    // Members of a group that just closed, sliding into its bubble (removed once they arrive).
    const ghostDots = root.append("g").attr("pointer-events", "none")
      .selectAll<SVGCircleElement, (typeof ghosts)[number]>("circle").data(ghosts).join("circle")
      .attr("r", NODE_RADIUS).attr("fill", (g) => g.color).attr("stroke", NODE_RING).attr("stroke-width", 1.5)
      .attr("cx", (g) => transform.applyX(g.from.x)).attr("cy", (g) => transform.applyY(g.from.y));

    // ---- documents and collapsed groups ----
    const docs = nodes.filter((n) => n.kind === "doc");
    const groups = nodes.filter((n) => n.kind === "group");
    const docCircle = root.append("g").selectAll<SVGCircleElement, SimItem>("circle").data(docs).join("circle")
      .attr("class", "doc").attr("r", NODE_RADIUS)
      .attr("fill", (d) => (d.kind === "doc" ? sourceColor(d.doc.source) : GROUP_FILL))
      .attr("filter", "url(#glow)").style("cursor", "pointer")
      .on("mousemove", (ev, d) => {
        if (d.kind !== "doc") return;
        const summary = d.doc.summary ?? "";
        setTooltip({
          ...pointerIn(wrapRef.current, ev), title: d.doc.title,
          body: `${d.doc.source} · level 0 · ${d.doc.connectionCount} connection${d.doc.connectionCount === 1 ? "" : "s"}\n${summary.length > 100 ? summary.slice(0, 100) + "…" : summary}`,
        });
      })
      .on("mouseleave", () => setTooltip(null))
      .on("click", (ev, d) => { ev.stopPropagation(); focus(d.id); select(d.id); });

    // A collapsed group: same size as a document, drawn as a dashed ring with a dot ("contains things").
    const groupG = root.append("g").selectAll<SVGGElement, SimItem>("g").data(groups).join("g")
      .attr("class", "group").style("cursor", "pointer")
      .on("mousemove", (ev, d) => {
        if (d.kind !== "group") return;
        const t = d.topic;
        setTooltip({
          ...pointerIn(wrapRef.current, ev), title: t.name,
          body: `level ${t.level} · ${t.size} documents${t.terms.length ? `\nKey concepts: ${t.terms.join(", ")}` : ""}\nClick or zoom in to open`,
        });
      })
      .on("mouseleave", () => setTooltip(null))
      .on("click", (ev, d) => {
        ev.stopPropagation();
        setTooltip(null);
        focus(d.id); // after it opens, the id resolves to the centre of its members
        openGroup(d.id, Math.max(transform.k, 1));
      });
    groupG.append("circle").attr("class", "group-ring").attr("r", NODE_RADIUS).attr("fill", GROUP_FILL)
      .attr("stroke-width", 2).attr("stroke-dasharray", "3 2").attr("filter", "url(#glow)");
    groupG.append("circle").attr("r", 3).attr("fill", "#e8e8f0");

    const docLabels = root.append("g").selectAll<SVGTextElement, SimItem>("text").data(docs).join("text")
      .attr("fill", "#ffffff").attr("font-size", 10.5).attr("pointer-events", "none");
    const groupLabels = root.append("g").selectAll<SVGTextElement, SimItem>("text").data(groups).join("text")
      .attr("pointer-events", "none");
    groupLabels.append("tspan").attr("fill", "#ffffff").attr("font-size", 11.5).attr("font-weight", 600)
      .text((d) => (d.kind === "group" ? d.topic.name : ""));
    groupLabels.append("tspan").attr("fill", "rgba(210,210,230,0.65)").attr("font-size", 9.5).attr("dy", 12)
      .text((d) => (d.kind === "group" ? `level ${d.topic.level} · ${d.topic.size} docs` : ""));

    const setDocText = () => docLabels.text((d) => {
      const t = d.kind === "doc" ? d.doc.title : "";
      return fullLabels || t.length <= 32 ? t : t.slice(0, 31) + "…";
    });
    setDocText();

    // ---- forces ----
    const groupPull = (alpha: number) => {
      for (const members of membersOf.values()) {
        if (members.length < 2) continue;
        const cx = d3.mean(members, (n) => n.x) ?? 0, cy = d3.mean(members, (n) => n.y) ?? 0;
        for (const n of members) {
          n.vx = (n.vx ?? 0) + (cx - (n.x ?? 0)) * 0.08 * alpha;
          n.vy = (n.vy ?? 0) + (cy - (n.y ?? 0)) * 0.08 * alpha;
        }
      }
    };
    const sim = d3.forceSimulation<SimItem>(nodes)
      .force("link", d3.forceLink<SimItem, SimLink>(links).id((d) => d.id)
        .distance((l) => Math.max(50, (1 - l.score) * 300))
        .strength((l) => Math.min(1, l.score * 0.7 * (l.count > 1 ? 0.5 : 1))))
      .force("charge", d3.forceManyBody().strength(-320))
      .force("center", d3.forceCenter(width / 2, height / 2))
      .force("x", d3.forceX(width / 2).strength(0.04))
      .force("y", d3.forceY(height / 2).strength(0.04))
      // group bubbles carry a two-line label, so give them more room
      .force("collide", d3.forceCollide<SimItem>((d) => (d.kind === "group" ? NODE_RADIUS + 26 : NODE_RADIUS + 6)))
      .force("groups", groupPull)
      .alpha(prev.size ? 0.5 : 1);

    const drag = d3.drag<SVGElement, SimItem>()
      .clickDistance(4)
      // drag in screen coordinates, then convert back to simulation coordinates for the current zoom
      .subject((_, d) => ({ x: X(d), y: Y(d) }))
      // only reheat the layout once the pointer actually moves, so a plain click doesn't shake the graph
      .on("start", () => camera.current?.stop())
      .on("drag", (ev, d) => { sim.alphaTarget(0.3).restart(); d.fx = transform.invertX(ev.x); d.fy = transform.invertY(ev.y); })
      .on("end", (ev, d) => { if (!ev.active) sim.alphaTarget(0); d.fx = null; d.fy = null; });
    (docCircle as unknown as d3.Selection<SVGElement, SimItem, SVGGElement, unknown>).call(drag);
    (groupG as unknown as d3.Selection<SVGElement, SimItem, SVGGElement, unknown>).call(drag);

    // The camera's target: a visible item, or an open group's members.
    focusPoint.current = (id) => {
      const n = nodes.find((m) => m.id === id);
      if (n) return { x: n.x ?? 0, y: n.y ?? 0 };
      const members = membersOf.get(id);
      return members?.length ? { x: d3.mean(members, (m) => m.x)!, y: d3.mean(members, (m) => m.y)! } : null;
    };

    // ---- open/close animation: ghosts slide into a closing bubble; an opening group's items grow out ----
    const closing = new Set(ghosts.map((g) => g.to.id));
    d3.selectAll([...groupG.nodes(), ...groupLabels.nodes()])
      .filter((d) => closing.has((d as SimItem).id)).attr("opacity", 0)
      .transition().delay(ANIM_MS * 0.6).duration(ANIM_MS * 0.4).attr("opacity", 1);
    const anim = ghosts.length || intro.length ? d3.timer((ms) => {
      const t = d3.easeCubicInOut(Math.min(1, ms / ANIM_MS));
      for (const a of intro) {
        a.node.fx = a.node.x = a.from.x + (a.to.x - a.from.x) * t;
        a.node.fy = a.node.y = a.from.y + (a.to.y - a.from.y) * t;
      }
      ghostDots
        .attr("cx", (g) => transform.applyX(g.from.x + ((g.to.x ?? 0) - g.from.x) * t))
        .attr("cy", (g) => transform.applyY(g.from.y + ((g.to.y ?? 0) - g.from.y) * t))
        .attr("opacity", 1 - 0.7 * t);
      if (t === 1) {
        for (const a of intro) { a.node.fx = null; a.node.fy = null; }
        ghostDots.remove();
        anim?.stop();
      }
      render();
    }) : null;

    // ---- render: positions are zoomed, sizes are not ----
    function render() {
      const ends = (d: SimLink) => {
        const s = d.source as SimItem, t = d.target as SimItem;
        const sx = X(s), sy = Y(s), tx = X(t), ty = Y(t);
        const dx = tx - sx, dy = ty - sy, len = Math.hypot(dx, dy) || 1;
        const rs = NODE_RADIUS + 1, rt = NODE_RADIUS + (d.directional ? 3 : 1);
        return { x1: sx + (dx / len) * rs, y1: sy + (dy / len) * rs, x2: tx - (dx / len) * rt, y2: ty - (dy / len) * rt };
      };
      for (const sel of [edge, edgeHit]) {
        sel.each(function (d) {
          const p = ends(d);
          d3.select(this).attr("x1", p.x1).attr("y1", p.y1).attr("x2", p.x2).attr("y2", p.y2);
        });
      }
      docCircle.attr("cx", X).attr("cy", Y);
      groupG.attr("transform", (d) => `translate(${X(d)},${Y(d)})`);
      docLabels.attr("x", (d) => X(d) + NODE_RADIUS + 4).attr("y", (d) => Y(d) + 4);
      groupLabels.each(function (d) {
        const x = X(d) + NODE_RADIUS + 5;
        d3.select(this).attr("x", x).attr("y", Y(d) + 1).selectAll("tspan").attr("x", x);
      });

      hulls.attr("d", (t) => {
        const members = membersOf.get(t.id) ?? [];
        const pad = 16 + 10 * t.level; // outer (higher-level) groups wrap inner ones
        const pts: [number, number][] = [];
        for (const n of members) {
          for (let k = 0; k < 10; k++) pts.push([X(n) + pad * Math.cos((k / 10) * 2 * Math.PI), Y(n) + pad * Math.sin((k / 10) * 2 * Math.PI)]);
        }
        const hull = pts.length >= 3 ? d3.polygonHull(pts) : null;
        return hull ? d3.line().curve(d3.curveCatmullRomClosed)(hull) : null;
      });
      hullLabels.each(function (t) {
        const members = membersOf.get(t.id) ?? [];
        const cx = d3.mean(members, X) ?? 0;
        const top = (d3.min(members, Y) ?? 0) - (16 + 10 * t.level) - 18;
        d3.select(this).attr("x", cx).attr("y", top).selectAll("tspan").attr("x", cx);
      });

      for (const n of nodes) positions.current.set(n.id, { x: n.x ?? 0, y: n.y ?? 0 });
      // An open group remembers where its members are, so collapsing puts the bubble back there.
      for (const [gid, members] of membersOf) {
        if (members.length) positions.current.set(gid, { x: d3.mean(members, (n) => n.x)!, y: d3.mean(members, (n) => n.y)! });
      }
    }
    sim.on("tick", render);

    // Zoom: re-render positions; after a short pause, decide which groups to auto-open or close.
    let evalTimer: ReturnType<typeof setTimeout> | undefined;
    onZoom.current = (t) => {
      transform = t;
      const full = transform.k >= 1.6;
      if (full !== fullLabels) { fullLabels = full; setDocText(); }
      render();
      clearTimeout(evalTimer);
      evalTimer = setTimeout(() => zoomEval.current(), 180);
    };
    zoomRef.current ??= d3.zoom<SVGSVGElement, unknown>().scaleExtent([0.1, 8]).on("zoom", (ev) => {
      if (ev.sourceEvent) camera.current?.stop(); // the user took over the view
      onZoom.current(ev.transform);
    });
    const zoomBehavior = zoomRef.current;
    svg.call(zoomBehavior).on("dblclick.zoom", null);
    svg.on("click", (ev) => { if (ev.target === svgRef.current) select(null); });

    // Open on-screen groups once zoomed in enough for their depth. Zooming back out closes groups again,
    // however they were opened (hysteresis avoids flicker), and forgets manual closes so they reopen next time.
    zoomEval.current = () => {
      const k = transform.k;
      const threshold = (id: string) => AUTO_OPEN_AT[Math.min(topics.depth.get(id) ?? 0, AUTO_OPEN_AT.length - 1)];
      setManualOpen((cur) => {
        const next = new Map([...cur].filter(([id, k0]) => k >= Math.min(k0, threshold(id)) * 0.85));
        return next.size === cur.size ? cur : next;
      });
      setManualClosed((cur) => {
        const next = new Set([...cur].filter((id) => k >= threshold(id) * 0.85));
        return next.size === cur.size ? cur : next;
      });
      setAutoOpen((cur) => {
        const next = new Set<string>();
        for (const id of cur) if (k >= threshold(id) * 0.85) next.add(id);
        for (const n of groups) {
          const x = X(n), y = Y(n);
          if (x > 0 && y > 0 && x < width && y < height && k >= threshold(n.id)) next.add(n.id);
        }
        const same = next.size === cur.size && [...next].every((id) => cur.has(id));
        return same ? cur : next;
      });
    };

    paintRings();
    render();
    return () => { sim.stop(); anim?.stop(); clearTimeout(evalTimer); };
  }, [data, view, hidden, topics, select, openGroup, closeGroup, focus, paintRings]);

  // ---- semantic search: fetch as you type (debounced), ring the hits, Enter jumps to the best one ----
  useEffect(() => {
    const q = query.trim();
    if (!q) return;
    const timer = setTimeout(() => {
      api<SearchHit[]>(`/api/knowledge/search?q=${encodeURIComponent(q)}&limit=16`)
        .then((rows) => {
          // results are chunks, so one document can match several times; keep its best
          const seen = new Set<string>();
          const hits = rows.filter((h) => h.nodeId && !seen.has(h.nodeId) && !!seen.add(h.nodeId)).slice(0, 8);
          setResult({ q, hits });
        })
        .catch(() => setResult({ q, hits: [] }));
    }, 250);
    return () => clearTimeout(timer);
  }, [query]);
  const hits = useMemo(() => {
    const known = new Set(data?.nodes.map((n) => n.id));
    return query.trim() ? result.hits.filter((h) => known.has(h.nodeId)) : [];
  }, [query, result, data]);
  useEffect(() => {
    hitIds.current = new Set(hits.map((h) => h.nodeId));
    paintRings();
  }, [hits, paintRings]);

  /** Opens every group around a document, then glides to it and selects it. */
  function reveal(id: string) {
    if (!data || !svgRef.current) return;
    const k = Math.max(d3.zoomTransform(svgRef.current).k, 1);
    for (let t = topics.byId.get(data.topics.docParent[id] ?? ""); t; t = t.parentId ? topics.byId.get(t.parentId) : undefined) {
      if (!expanded.has(t.id)) openGroup(t.id, k);
    }
    focus(id);
    select(id);
  }

  function toggle(code: string) {
    setHidden((h) => {
      const next = new Set(h);
      if (next.has(code)) next.delete(code); else next.add(code);
      return next;
    });
  }

  function collapseAll() {
    camera.current?.stop();
    setManualOpen(new Map());
    setManualClosed(new Set());
    setAutoOpen(new Set());
    if (svgRef.current && zoomRef.current) d3.select(svgRef.current).call(zoomRef.current.transform, d3.zoomIdentity);
  }

  async function removeSelected() {
    if (!selected) return;
    await del(`/api/knowledge/nodes/${selected.id}`);
    selectedIdRef.current = null;
    setSelected(null);
    load();
  }

  const titleOf = new Map(data?.nodes.map((n) => [n.id, n.title]) ?? []);
  const counts = new Map<string, number>();
  data?.edges.forEach((e) => counts.set(e.relationType, (counts.get(e.relationType) ?? 0) + 1));
  const sourceCounts = new Map<string, number>();
  data?.nodes.forEach((n) => sourceCounts.set(n.source, (sourceCounts.get(n.source) ?? 0) + 1));
  const sources = [...sourceCounts.keys()].sort();
  const links = !selected || !data ? [] : data.edges
    .filter((e) => e.source === selected.id || e.target === selected.id)
    .map((e) => ({ ...e, other: titleOf.get(e.source === selected.id ? e.target : e.source) ?? "", outgoing: e.source === selected.id }));
  // Topics containing the selected document, broadest first.
  const trail: Topic[] = [];
  if (selected && data) {
    for (let t = topics.byId.get(data.topics.docParent[selected.id] ?? ""); t; t = t.parentId ? topics.byId.get(t.parentId) : undefined) trail.unshift(t);
  }

  return (
    <div className="flex h-[calc(100vh-4rem)] flex-col">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
        <h1 className="text-2xl font-semibold">Knowledge graph</h1>
        {data && (
          <span className="text-sm text-zinc-500">
            {data.nodes.length} documents · {data.edges.length} relationships · {data.topics.clusters.length} topics
          </span>
        )}
        <span className="text-xs text-zinc-500">Zoom in or click a topic to open it · zoom out to close it again</span>
        <div className="relative ml-auto w-full sm:w-80">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onFocus={() => setSearchFocused(true)}
            onBlur={() => setSearchFocused(false)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && hits[0]) { reveal(hits[0].nodeId); e.currentTarget.blur(); }
              if (e.key === "Escape") { setQuery(""); e.currentTarget.blur(); }
            }}
            placeholder="Search by meaning…"
            title="Semantic search · Enter jumps to the best match · Esc clears"
            className="w-full rounded-md border border-white/10 bg-white/5 px-3 py-1.5 pr-7 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-violet-400/60 focus:outline-none"
          />
          {query && (
            <button onClick={() => setQuery("")} aria-label="Clear search"
              className="absolute right-2 top-1/2 -translate-y-1/2 text-xs text-zinc-500 hover:text-white">✕</button>
          )}
          {searchFocused && query.trim() && (
            <ul className="absolute right-0 z-20 mt-1 max-h-96 w-full overflow-auto rounded-lg border border-white/10 bg-[#2d2d44] py-1 text-sm shadow-xl">
              {hits.length === 0 && (
                <li className="px-3 py-2 text-xs text-zinc-400">{result.q === query.trim() ? "No matches" : "Searching…"}</li>
              )}
              {hits.map((h) => (
                <li key={h.nodeId}>
                  {/* onMouseDown so the pick lands before the input's blur hides the list */}
                  <button onMouseDown={(e) => { e.preventDefault(); reveal(h.nodeId); (document.activeElement as HTMLElement)?.blur(); }}
                    className="block w-full px-3 py-1.5 text-left hover:bg-white/5">
                    <div className="flex items-center gap-2">
                      <span className="inline-block h-2 w-2 shrink-0 rounded-full" style={{ background: sourceColor(h.source) }} />
                      <span className="truncate text-zinc-100">{h.title}</span>
                      <span className="ml-auto shrink-0 text-[11px] text-zinc-500">{Math.round(h.score * 100)}%</span>
                    </div>
                    <div className="truncate pl-4 text-xs text-zinc-400">{h.snippet}</div>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        <button onClick={collapseAll} className="rounded-md border border-white/10 px-2.5 py-1 text-xs text-zinc-300 hover:bg-white/5">
          Collapse all
        </button>
      </div>
      {error && <p className="mt-3 text-sm text-red-400">Backend error: {error}</p>}

      <div ref={wrapRef} className="relative mt-4 flex-1 overflow-hidden rounded-xl border border-white/10" style={{ background: BG }}>
        <svg ref={svgRef} className="h-full w-full" />
        {data && data.nodes.length === 0 && (
          <p className="absolute inset-0 grid place-items-center text-sm text-zinc-400">No documents yet. Add content; related items get linked automatically.</p>
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
                    <button onClick={() => toggle(t.code)} title={t.description}
                      className={`flex w-full items-center gap-2 rounded px-1 py-0.5 text-left hover:bg-white/5 ${off ? "opacity-35" : ""}`}>
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
              <div className="mt-2 space-y-1 px-1 text-[11px] text-zinc-400">
                <div className="flex items-center gap-2">
                  <span className="inline-block h-2.5 w-2.5 shrink-0 rounded-full border border-dashed border-zinc-200" style={{ background: GROUP_FILL }} />
                  topic, collapsed (level = depth)
                </div>
                <div className="flex items-center gap-2">
                  <span className="inline-block h-3 w-5 shrink-0 rounded-full border border-dashed border-zinc-400/60" />
                  topic, open
                </div>
                <div className="text-zinc-500">Thick line = several relationships merged</div>
              </div>
            </div>
          </div>
        )}

        {tooltip && (
          <div className="pointer-events-none absolute z-10 max-w-xs whitespace-pre-line rounded-lg bg-[#2d2d44] px-3 py-2 text-xs text-white shadow-xl"
            style={{ left: tooltip.x, top: tooltip.y }}>
            <div className="font-medium" style={tooltip.color ? { color: tooltip.color } : undefined}>{tooltip.title}</div>
            {tooltip.body && <div className="mt-1 text-zinc-300">{tooltip.body}</div>}
          </div>
        )}

        {selected && (
          <aside className="absolute right-3 top-3 max-h-[calc(100%-1.5rem)] w-80 overflow-auto rounded-lg border border-white/10 bg-[#2d2d44]/95 p-4 text-sm shadow-xl">
            <div className="flex items-start justify-between gap-2">
              <div className="flex items-center gap-2">
                <SourceBadge source={selected.source} />
                <span className="text-[11px] text-zinc-500">level 0</span>
              </div>
              <button onClick={() => select(null)} className="text-zinc-500 hover:text-white">✕</button>
            </div>
            {trail.length > 0 && (
              <div className="mt-2 text-[11px] text-zinc-400">
                {trail.map((t, i) => (
                  <span key={t.id}>{i > 0 && " › "}{t.name} <span className="text-zinc-500">(L{t.level})</span></span>
                ))}
              </div>
            )}
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

function nameOf(n: SimItem) {
  return n.kind === "doc" ? n.doc.title : `${n.topic.name} (topic)`;
}

/** Tooltip position relative to the graph container. */
function pointerIn(wrap: HTMLDivElement | null, ev: MouseEvent) {
  const r = wrap?.getBoundingClientRect();
  return { x: ev.clientX - (r?.left ?? 0) + 14, y: ev.clientY - (r?.top ?? 0) + 14 };
}

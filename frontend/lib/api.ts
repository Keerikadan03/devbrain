export const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export type KnowledgeNode = {
  id: string;
  title: string;
  content: string;
  summary: string;
  source: string;
  sourceUrl?: string | null;
  metadata?: string | null;
  createdAt: string;
};

export type SearchHit = {
  score: number;
  nodeId: string;
  title: string;
  source: string;
  snippet: string;
};

export type GraphData = {
  nodes: { id: string; title: string; source: string }[];
  edges: { from: string; to: string; kind: "related"; score: number; via: string }[];
};

export type ChatSource = {
  id: string;
  title: string;
  source: string;
  url?: string | null;
  relation: string;
};

export type ResearchSession = {
  id: string;
  query: string;
  phase: "PLANNING" | "RESEARCHING" | "ANALYZING" | "SYNTHESIZING" | "COMPLETED" | "FAILED";
  plan?: string | null;
  findings?: string | null;
  report?: string | null;
  createdAt: string;
};

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
}

export const post = <T,>(path: string, body: unknown) =>
  api<T>(path, { method: "POST", body: JSON.stringify(body) });

export const del = <T,>(path: string) => api<T>(path, { method: "DELETE" });

export function parseMeta(meta?: string | null): { tags?: string[]; concepts?: string[] } {
  try {
    return meta ? JSON.parse(meta) : {};
  } catch {
    return {};
  }
}

export const SOURCE_COLORS: Record<string, string> = {
  text: "#a78bfa",
  url: "#38bdf8",
  pdf: "#fb923c",
  youtube: "#f87171",
  instagram: "#f472b6",
};
export const sourceColor = (s: string) => SOURCE_COLORS[s] ?? "#94a3b8";

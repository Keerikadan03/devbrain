import { sourceColor } from "@/lib/api";

export default function SourceBadge({ source }: { source: string }) {
  const c = sourceColor(source);
  return (
    <span
      className="rounded-full px-2 py-0.5 text-[11px] font-medium uppercase tracking-wide"
      style={{ color: c, background: `${c}22` }}
    >
      {source}
    </span>
  );
}

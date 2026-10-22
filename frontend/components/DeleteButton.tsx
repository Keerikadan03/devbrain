"use client";
import { useState } from "react";

/** Two-step delete: first click asks, second click confirms. Avoids window.confirm. */
export default function DeleteButton({ onDelete, label = "Delete" }: { onDelete: () => Promise<void>; label?: string }) {
  const [asking, setAsking] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function confirm() {
    setBusy(true);
    setError("");
    try {
      await onDelete();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
      setAsking(false);
    }
  }

  if (!asking) {
    return (
      <span className="inline-flex items-center gap-2">
        <button
          onClick={(e) => { e.stopPropagation(); setAsking(true); }}
          className="rounded-md border border-white/10 px-2.5 py-1 text-xs text-zinc-400 transition hover:border-red-400/50 hover:text-red-300"
        >
          {label}
        </button>
        {error && <span className="text-xs text-red-400">Failed: {error}</span>}
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-2 text-xs" onClick={(e) => e.stopPropagation()}>
      <span className="text-zinc-400">Remove from everywhere?</span>
      <button onClick={confirm} disabled={busy} className="rounded-md bg-red-500/90 px-2.5 py-1 font-medium text-white hover:bg-red-500 disabled:opacity-50">
        {busy ? "Deleting…" : "Yes, delete"}
      </button>
      <button onClick={() => setAsking(false)} disabled={busy} className="rounded-md px-2 py-1 text-zinc-400 hover:text-white">Cancel</button>
    </span>
  );
}

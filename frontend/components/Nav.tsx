"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";

const LINKS = [
  { href: "/", label: "Add" },
  { href: "/library", label: "Library" },
  { href: "/graph", label: "Graph" },
  { href: "/chat", label: "Chat" },
  { href: "/research", label: "Research" },
];

export default function Nav() {
  const path = usePathname();
  return (
    <nav className="flex gap-1 border-b border-white/10 px-4 md:h-screen md:w-52 md:flex-col md:border-b-0 md:border-r md:px-3 md:py-6">
      <div className="py-3 pr-4 text-lg font-semibold tracking-tight md:px-3 md:pb-6">
        Dev<span className="text-violet-400">Brain</span>
      </div>
      {LINKS.map((l) => {
        const active = l.href === "/" ? path === "/" : path.startsWith(l.href);
        return (
          <Link
            key={l.href}
            href={l.href}
            className={`rounded-md px-3 py-2 text-sm transition ${
              active ? "bg-white/10 text-white" : "text-zinc-400 hover:bg-white/5 hover:text-zinc-200"
            }`}
          >
            {l.label}
          </Link>
        );
      })}
    </nav>
  );
}

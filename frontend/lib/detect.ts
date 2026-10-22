// Client-side preview of what the server will pick. The server is authoritative (it can also
// check a web address's Content-Type), so this is only used to show a label while typing.
export type SourceType = "text" | "url" | "pdf" | "youtube" | "instagram" | "image";

const YT = /^https?:\/\/((www|m|music)\.)?(youtube\.com|youtu\.be)\/\S+$/;
const IG = /^https?:\/\/(www\.)?instagram\.com\/(p|reel|reels|tv)\/\S+$/;

export function detectType(raw: string): SourceType | null {
  const c = raw.trim();
  if (!c) return null;
  if (c.startsWith("data:application/pdf") || c.startsWith("JVBERi0")) return "pdf";
  if (c.startsWith("data:image/")) return "image";
  if (!/^https?:\/\/\S+$/.test(c)) return "text";
  if (YT.test(c)) return "youtube";
  if (IG.test(c)) return "instagram";
  if (/^[^?#]*\.pdf([?#].*)?$/i.test(c) || /^https?:\/\/(www\.)?arxiv\.org\/pdf\//i.test(c)) return "pdf";
  return "url";
}

export const TYPE_LABELS: Record<SourceType, string> = {
  text: "Text note",
  url: "Web page",
  pdf: "PDF",
  youtube: "YouTube video",
  instagram: "Instagram post",
  image: "Image (not supported yet)",
};

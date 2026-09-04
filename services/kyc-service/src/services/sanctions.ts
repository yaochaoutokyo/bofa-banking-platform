/**
 * Simulated OFAC SDN / consolidated-list screening. Real deployments call the
 * enterprise screening engine; this in-memory list exercises the matching logic.
 *
 * Untested: fuzzy-match thresholds, transliteration, DOB tolerance,
 * comprehensively-sanctioned jurisdictions, and false-positive suppression.
 */

export interface SanctionsHit {
  listName: string;
  matchedName: string;
  score: number;
  program: string;
}

export const COMPREHENSIVELY_SANCTIONED = new Set(["IR", "KP", "SY", "CU"]);
export const HIGH_RISK_JURISDICTIONS = new Set(["AF", "MM", "YE", "VE", "RU", "BY", "ZW", "SS", "LY", "IQ"]);

interface SdnEntry {
  name: string;
  aliases: string[];
  dob?: string;
  program: string;
}

const SDN: SdnEntry[] = [
  { name: "IVAN PETROV", aliases: ["IVAN PETROFF", "I. PETROV"], dob: "1970-04-12", program: "RUSSIA-EO14024" },
  { name: "GLOBAL TRADING FZE", aliases: ["GLOBAL TRADING FREE ZONE"], program: "IRAN" },
  { name: "CARLOS MENDOZA RIVERA", aliases: ["CARLOS MENDOZA"], dob: "1982-11-03", program: "SDNTK" },
  { name: "SUNRISE SHIPPING CO", aliases: [], program: "DPRK" },
];

export function normalizeName(name: string): string {
  return name
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toUpperCase()
    .replace(/[^A-Z0-9 ]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** Jaro-Winkler-lite: token overlap weighted by prefix agreement. */
export function similarity(a: string, b: string): number {
  const ta = new Set(normalizeName(a).split(" "));
  const tb = new Set(normalizeName(b).split(" "));
  if (ta.size === 0 || tb.size === 0) return 0;
  let overlap = 0;
  for (const token of ta) {
    if (tb.has(token)) {
      overlap += 1;
    } else {
      for (const other of tb) {
        if (token.length >= 4 && other.startsWith(token.slice(0, 4))) {
          overlap += 0.5;
          break;
        }
      }
    }
  }
  return overlap / Math.max(ta.size, tb.size);
}

export function screenName(fullName: string, dob?: string, threshold = 0.85): SanctionsHit[] {
  const hits: SanctionsHit[] = [];
  for (const entry of SDN) {
    const candidates = [entry.name, ...entry.aliases];
    let best = 0;
    let bestName = entry.name;
    for (const candidate of candidates) {
      const score = similarity(fullName, candidate);
      if (score > best) {
        best = score;
        bestName = candidate;
      }
    }
    if (entry.dob && dob && entry.dob !== dob) {
      best -= 0.15;
    }
    if (best >= threshold) {
      hits.push({ listName: "OFAC-SDN", matchedName: bestName, score: Number(best.toFixed(2)), program: entry.program });
    }
  }
  return hits.sort((x, y) => y.score - x.score);
}

#!/usr/bin/env python3
"""Aggregate per-service coverage into the README scoreboard.

Reads whatever coverage artifacts exist on disk (JaCoCo XML, coverage.py XML,
Jest coverage-summary.json), falls back to the committed baseline in
scripts/coverage-baseline.json for services that were not run locally, and
renders:

  * a Shields.io badge row
  * the "Current Test Coverage" table
  * the "Compliance Readiness" table

Usage
  python3 scripts/coverage-summary.py                 # print markdown
  python3 scripts/coverage-summary.py --write         # rewrite README.md sections + baseline
  python3 scripts/coverage-summary.py --service X --github-summary   # CI step summary
  python3 scripts/coverage-summary.py --json          # machine-readable

Only the standard library is used so the script runs anywhere python3 exists.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVICES_DIR = ROOT / "services"
README = ROOT / "README.md"
BASELINE = ROOT / "scripts" / "coverage-baseline.json"


@dataclass(frozen=True)
class ServiceSpec:
    name: str
    stack: str
    tier: str  # compliance-critical | core | supporting
    target: int
    has_runner: bool
    in_ci: bool
    controls: str
    domain: str


SERVICES: list[ServiceSpec] = [
    ServiceSpec("transaction-service", "Java", "compliance-critical", 90, True, False, "OCC exam · SOX 404 · Reg E · BSA/AML", "Transaction processing"),
    ServiceSpec("auth-service", "Java", "compliance-critical", 90, True, False, "FFIEC Authentication · SOX ITGC · PCI-DSS 8", "Authentication / JWT"),
    ServiceSpec("payments-gateway", "Java", "compliance-critical", 90, False, False, "PCI-DSS · NACHA · OCC payments risk", "Payment routing"),
    ServiceSpec("ledger-service", "Java", "core", 80, True, True, "SOX 404 · GAAP", "Double-entry ledger"),
    ServiceSpec("pii-vault-service", "Python", "compliance-critical", 95, False, False, "GLBA 501(b) · PCI-DSS 3.4 · Reg P", "PII tokenization / masking"),
    ServiceSpec("audit-logging-service", "Python", "compliance-critical", 95, True, False, "SOX 404 · OCC auditability · BSA recordkeeping", "Append-only audit trail"),
    ServiceSpec("fraud-detection-service", "Python", "core", 80, True, True, "BSA/AML · SAR filing", "Risk scoring"),
    ServiceSpec("notification-service", "Python", "supporting", 80, True, True, "Reg E alerts · TCPA", "Alerts / email"),
    ServiceSpec("customer-portal-api", "TypeScript", "core", 80, True, False, "Reg E · UDAAP", "Customer-facing API"),
    ServiceSpec("account-service", "TypeScript", "core", 80, True, True, "Reg DD · Escheatment", "Account CRUD (golden reference)"),
    ServiceSpec("statement-service", "TypeScript", "supporting", 80, True, True, "Reg DD · Reg E disclosures", "Statement generation"),
    ServiceSpec("kyc-service", "TypeScript", "compliance-critical", 90, True, True, "BSA/AML CIP · FinCEN CDD · OFAC", "KYC / identity"),
]

COMPLIANCE_PATHS: list[tuple[str, list[str]]] = [
    ("Transaction processing", ["transaction-service", "payments-gateway"]),
    ("Authentication", ["auth-service"]),
    ("PII handling", ["pii-vault-service", "kyc-service"]),
    ("Audit logging", ["audit-logging-service"]),
]


@dataclass
class Coverage:
    covered: int
    total: int
    source: str

    @property
    def pct(self) -> float:
        return 0.0 if self.total == 0 else 100.0 * self.covered / self.total


# ----------------------------------------------------------------- readers --

def read_jacoco(service_dir: Path) -> Coverage | None:
    path = service_dir / "target" / "site" / "jacoco" / "jacoco.xml"
    if not path.exists():
        return None
    root = ET.parse(path).getroot()
    for counter in root.findall("counter"):
        if counter.get("type") == "LINE":
            missed = int(counter.get("missed", "0"))
            covered = int(counter.get("covered", "0"))
            return Coverage(covered, covered + missed, "jacoco.xml")
    return None


def read_coveragepy(service_dir: Path) -> Coverage | None:
    path = service_dir / "coverage.xml"
    if not path.exists():
        return None
    root = ET.parse(path).getroot()
    valid = int(root.get("lines-valid", "0"))
    covered = int(root.get("lines-covered", "0"))
    return Coverage(covered, valid, "coverage.xml")


def read_jest(service_dir: Path) -> Coverage | None:
    path = service_dir / "coverage" / "coverage-summary.json"
    if not path.exists():
        return None
    data = json.loads(path.read_text())
    lines = data["total"]["lines"]
    return Coverage(int(lines["covered"]), int(lines["total"]), "coverage-summary.json")


def count_source_lines(spec: ServiceSpec) -> int:
    """Approximate executable line count for services with no coverage report."""
    service_dir = SERVICES_DIR / spec.name
    patterns = {"Java": ("src/main", "*.java"), "Python": ("app", "*.py"), "TypeScript": ("src", "*.ts")}
    base, glob = patterns[spec.stack]
    total = 0
    for file in (service_dir / base).rglob(glob):
        for line in file.read_text(encoding="utf-8", errors="ignore").splitlines():
            stripped = line.strip()
            if stripped and not stripped.startswith(("//", "#", "*", "/*", "import ", "from ", "package ")):
                total += 1
    return total


def read_coverage(spec: ServiceSpec, baseline: dict[str, dict[str, int]]) -> Coverage:
    service_dir = SERVICES_DIR / spec.name
    reader = {"Java": read_jacoco, "Python": read_coveragepy, "TypeScript": read_jest}[spec.stack]
    found = reader(service_dir)
    if found is not None:
        return found
    if not spec.has_runner:
        return Coverage(0, count_source_lines(spec), "no test runner")
    if spec.name in baseline:
        entry = baseline[spec.name]
        return Coverage(entry["covered"], entry["total"], "baseline")
    return Coverage(0, count_source_lines(spec), "not run")


# --------------------------------------------------------------- rendering --

def badge_color(spec: ServiceSpec, pct: float) -> str:
    if not spec.has_runner:
        return "lightgrey"
    if pct >= 80:
        return "brightgreen"
    if pct >= 60:
        return "green"
    if pct >= 40:
        return "yellow"
    if pct >= 20:
        return "orange"
    return "red"


def badge(label: str, message: str, color: str) -> str:
    def enc(s: str) -> str:
        return s.replace("-", "--").replace("_", "__").replace("%", "%25").replace(" ", "%20").replace("(", "%28").replace(")", "%29")

    return f"![{label}](https://img.shields.io/badge/{enc(label)}-{enc(message)}-{color})"


def overall(results: dict[str, Coverage]) -> Coverage:
    covered = sum(c.covered for c in results.values())
    total = sum(c.total for c in results.values())
    return Coverage(covered, total, "aggregate")


def mean_pct(results: dict[str, Coverage]) -> float:
    return sum(c.pct for c in results.values()) / len(results)


def render_badges(results: dict[str, Coverage]) -> str:
    avg = mean_pct(results)
    total = overall(results)
    color = "red" if avg < 40 else "yellow" if avg < 70 else "brightgreen"
    parts = [
        badge("overall coverage", f"{avg:.0f}% avg", color),
        badge("lines covered", f"{total.covered} of {total.total} ({total.pct:.0f}%)", color),
    ]
    lines = [" ".join(parts), ""]
    for tier, title in (("compliance-critical", "Compliance-critical"), ("core", "Core"), ("supporting", "Supporting")):
        row = []
        for spec in SERVICES:
            if spec.tier != tier:
                continue
            cov = results[spec.name]
            message = "tests not configured" if not spec.has_runner else f"{cov.pct:.0f}%"
            row.append(badge(spec.name, message, badge_color(spec, cov.pct)))
        lines.append(f"**{title}:** " + " ".join(row))
        lines.append("")
    return "\n".join(lines).rstrip()


def status_icon(spec: ServiceSpec, pct: float) -> str:
    if not spec.has_runner:
        return "⚫ no runner"
    if pct >= spec.target:
        return "🟢 at target"
    if pct >= 40:
        return "🟡 below target"
    return "🔴 critical gap"


def render_coverage_table(results: dict[str, Coverage]) -> str:
    total = overall(results)
    avg = mean_pct(results)
    in_ci = sum(1 for spec in SERVICES if spec.in_ci)
    lines = [
        "| Service | Stack | Tier | Line coverage | Target | Gap | CI test job | Status |",
        "|---|---|---|---:|---:|---:|:---:|---|",
        f"| **Overall — average across 12 services** | — | — | **{avg:.1f}%** | 80% | -{max(0.0, 80 - avg):.0f} pts | {in_ci} of {len(SERVICES)} | {'🔴 critical gap' if avg < 40 else '🟡 below target'} |",
        f"| **Overall — line-weighted** ({total.covered:,} / {total.total:,} lines) | — | — | **{total.pct:.1f}%** | 80% | -{max(0.0, 80 - total.pct):.0f} pts | | |",
    ]
    for spec in SERVICES:
        cov = results[spec.name]
        pct = f"{cov.pct:.1f}%" if spec.has_runner else "0.0%"
        gap = f"-{max(0.0, spec.target - cov.pct):.0f} pts"
        lines.append(
            f"| [`{spec.name}`](services/{spec.name}) | {spec.stack} | {spec.tier} | {pct} | {spec.target}% | {gap} "
            f"| {'✅' if spec.in_ci else '—'} | {status_icon(spec, cov.pct)} |"
        )
    return "\n".join(lines)


def render_compliance_table(results: dict[str, Coverage]) -> str:
    lines = [
        "| Compliance-critical path | Services | Regulatory controls | Target | Actual | Gap | Exam readiness |",
        "|---|---|---|---:|---:|---:|---|",
    ]
    for path, names in COMPLIANCE_PATHS:
        specs = [s for s in SERVICES if s.name in names]
        covered = sum(results[s.name].covered for s in specs)
        total = sum(results[s.name].total for s in specs)
        actual = 0.0 if total == 0 else 100.0 * covered / total
        target = max(s.target for s in specs)
        controls = " · ".join(dict.fromkeys(c.strip() for s in specs for c in s.controls.split("·")))
        readiness = "🔴 Not exam-ready" if actual < 50 else "🟡 Partial" if actual < target else "🟢 Ready"
        services = ", ".join(f"`{s.name}`" for s in specs)
        lines.append(f"| {path} | {services} | {controls} | {target}% | {actual:.1f}% | -{max(0.0, target - actual):.0f} pts | {readiness} |")
    return "\n".join(lines)


def render_service_summary(spec: ServiceSpec, cov: Coverage) -> str:
    return "\n".join(
        [
            f"### {spec.name} coverage",
            "",
            "| Metric | Value |",
            "|---|---:|",
            f"| Line coverage | **{cov.pct:.1f}%** |",
            f"| Lines covered | {cov.covered} / {cov.total} |",
            f"| Target ({spec.tier}) | {spec.target}% |",
            f"| Gap to target | {max(0.0, spec.target - cov.pct):.1f} pts |",
            f"| Source | {cov.source} |",
            "",
        ]
    )


def replace_section(text: str, marker: str, body: str) -> str:
    start, end = f"<!-- {marker}:start -->", f"<!-- {marker}:end -->"
    pattern = re.compile(re.escape(start) + r".*?" + re.escape(end), re.DOTALL)
    if not pattern.search(text):
        raise SystemExit(f"README is missing markers for '{marker}'")
    return pattern.sub(f"{start}\n{body}\n{end}", text)


# -------------------------------------------------------------------- main --

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", action="store_true", help="rewrite README tables and refresh the baseline")
    parser.add_argument("--service", help="only report on one service")
    parser.add_argument("--github-summary", action="store_true", help="emit a $GITHUB_STEP_SUMMARY fragment")
    parser.add_argument("--json", action="store_true", help="emit JSON")
    args = parser.parse_args()

    baseline: dict[str, dict[str, int]] = json.loads(BASELINE.read_text()) if BASELINE.exists() else {}
    results = {spec.name: read_coverage(spec, baseline) for spec in SERVICES}
    by_name = {spec.name: spec for spec in SERVICES}

    if args.service:
        spec = by_name.get(args.service)
        if spec is None:
            print(f"unknown service {args.service}", file=sys.stderr)
            return 2
        cov = results[spec.name]
        if args.json:
            print(json.dumps({"service": spec.name, "pct": round(cov.pct, 1), "covered": cov.covered, "total": cov.total}))
        else:
            print(render_service_summary(spec, cov))
        return 0

    if args.json:
        payload = {
            "overall_average": round(mean_pct(results), 1),
            "overall_line_weighted": round(overall(results).pct, 1),
            "services": {n: {"pct": round(c.pct, 1), "covered": c.covered, "total": c.total, "source": c.source} for n, c in results.items()},
        }
        print(json.dumps(payload, indent=2))
        return 0

    badges = render_badges(results)
    coverage_table = render_coverage_table(results)
    compliance_table = render_compliance_table(results)

    if args.write:
        text = README.read_text()
        text = replace_section(text, "coverage-badges", badges)
        text = replace_section(text, "coverage-table", coverage_table)
        text = replace_section(text, "compliance-table", compliance_table)
        README.write_text(text)
        BASELINE.write_text(
            json.dumps({n: {"covered": c.covered, "total": c.total} for n, c in results.items()}, indent=2) + "\n"
        )
        print(f"README updated. Average service coverage: {mean_pct(results):.1f}% (line-weighted {overall(results).pct:.1f}%)")
        return 0

    print(badges, "", coverage_table, "", compliance_table, sep="\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
build_blocklist.py — Discover vendor subdomains and merge into the blocklist.

Reads  : scripts/vendors.txt  (seed domains, one per line; # lines are comments)
Merges : app/src/main/assets/blocklists/manipulation-blocklist.txt
Writes : app/src/main/assets/blocklists/manipulation-blocklist.txt (updated in-place)

Usage:
    python scripts/build_blocklist.py [--dry-run] [--verbose] [--workers N] [--timeout S]

    --dry-run    Print discovered domains but do not write to the blocklist.
    --verbose    Log every probe result, not just new discoveries.
    --workers N  Parallel DNS workers (default: 50).
    --timeout S  DNS query timeout in seconds (default: 3).

Optional dependency (recommended for accurate NXDOMAIN detection):
    pip install dnspython
"""

import argparse
import datetime
import pathlib
import socket
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

# ── Paths ─────────────────────────────────────────────────────────────────────

SCRIPT_DIR    = pathlib.Path(__file__).parent.resolve()
REPO_ROOT     = SCRIPT_DIR.parent
VENDORS_FILE  = SCRIPT_DIR / "vendors.txt"
BLOCKLIST_FILE = REPO_ROOT / "app/src/main/assets/blocklists/manipulation-blocklist.txt"

# ── Subdomain prefixes to probe ───────────────────────────────────────────────
# Common patterns used by tracking, analytics, pricing, and ad-tech companies.

PREFIXES = [
    # Static assets and CDN
    "cdn", "static", "assets", "js", "script", "scripts", "img", "images",
    "media", "files", "s", "a",
    # API and app
    "api", "app", "apps", "v1", "v2", "v3", "platform", "service", "services",
    "gateway", "edge", "proxy",
    # Tracking and collection
    "track", "tracking", "t", "tr", "collect", "collector", "ingest",
    "pixel", "px", "p", "imp", "beacon", "hit", "hits",
    # Analytics and events
    "analytics", "stats", "metrics", "log", "logs", "event", "events",
    "e", "telemetry", "report", "reports",
    # Identity and sync
    "id", "ids", "sync", "match", "uid", "user",
    # Tags
    "tag", "tags", "gtm", "container",
    # SDK and mobile
    "sdk", "mobile", "web", "push",
    # Data
    "data", "rt", "realtime", "stream",
    # Ads and RTB
    "ads", "ad", "rtb", "bid", "ssp", "dsp", "exchange",
    # Personalisation
    "rec", "recs", "recommend", "recommendation", "engine",
    "personalize", "personalise", "segment",
    # Dashboard and auth
    "dashboard", "admin", "auth", "login", "app",
    # Generic subdomains that many SaaS companies use
    "www", "mail", "us", "eu", "uk", "de", "fr", "au",
    "us1", "us2", "eu1", "eu2",
]

# ── DNS helpers ───────────────────────────────────────────────────────────────

try:
    import dns.resolver
    import dns.exception
    _HAS_DNSPYTHON = True
except ImportError:
    _HAS_DNSPYTHON = False


def _resolves_dnspython(fqdn: str, timeout: float) -> bool:
    resolver = dns.resolver.Resolver()
    resolver.lifetime = timeout
    for rtype in ("A", "AAAA", "CNAME"):
        try:
            resolver.resolve(fqdn, rtype)
            return True
        except dns.resolver.NXDOMAIN:
            return False          # definitively does not exist
        except dns.resolver.NoAnswer:
            continue              # no record of this type; try next
        except Exception:
            continue
    return False


def _resolves_socket(fqdn: str, timeout: float) -> bool:
    old = socket.getdefaulttimeout()
    try:
        socket.setdefaulttimeout(timeout)
        socket.getaddrinfo(fqdn, None)
        return True
    except socket.gaierror:
        return False
    finally:
        socket.setdefaulttimeout(old)


def resolves(fqdn: str, timeout: float) -> bool:
    if _HAS_DNSPYTHON:
        return _resolves_dnspython(fqdn, timeout)
    return _resolves_socket(fqdn, timeout)

# ── Blocklist parsing ─────────────────────────────────────────────────────────

def parse_blocklist(path: pathlib.Path) -> tuple[list[str], set[str]]:
    """
    Returns (lines, known_domains).

    `lines` preserves the full file structure (comments, blank lines, domain
    entries) but silently drops any line whose domain already appeared earlier
    in the file (intra-file deduplication).

    `known_domains` is the lowercased set of all domain names in the file.
    """
    lines: list[str] = []
    known: set[str] = set()

    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            stripped = line.strip()

            if not stripped or stripped.startswith("#"):
                lines.append(line)
                continue

            # Support both "domain.com" and "0.0.0.0 domain.com" formats.
            parts = stripped.split()
            domain = parts[-1].lower()

            if domain in known:
                # Drop the duplicate silently.
                continue

            known.add(domain)
            lines.append(line)

    return lines, known


def parse_vendors(path: pathlib.Path) -> list[str]:
    """Return seed domains from vendors.txt, skipping blanks and comments."""
    seeds: list[str] = []
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if line and not line.startswith("#"):
                seeds.append(line.lower())
    return seeds

# ── Discovery ─────────────────────────────────────────────────────────────────

def build_probe_list(seeds: list[str]) -> list[str]:
    """Expand each seed into (seed itself) + (prefix.seed) for every prefix."""
    probes: list[str] = []
    seen: set[str] = set()

    def add(d: str) -> None:
        if d not in seen:
            seen.add(d)
            probes.append(d)

    for seed in seeds:
        add(seed)
        for prefix in PREFIXES:
            add(f"{prefix}.{seed}")

    return probes


def discover(
    seeds: list[str],
    known: set[str],
    workers: int,
    timeout: float,
    verbose: bool,
) -> list[str]:
    """
    Probe all (prefix, seed) combinations in parallel.
    Returns newly discovered domains not already in `known`, sorted.
    """
    probes = [p for p in build_probe_list(seeds) if p not in known]
    total  = len(probes)
    found: list[str] = []

    print(f"  Probing {total:,} candidates with {workers} workers "
          f"(timeout {timeout}s each)…")

    if not _HAS_DNSPYTHON:
        print("  ⚠  dnspython not installed — using socket fallback "
              "(less accurate NXDOMAIN detection).")
        print("     Install with: pip install dnspython")

    completed = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(resolves, fqdn, timeout): fqdn for fqdn in probes}
        for future in as_completed(futures):
            fqdn = futures[future]
            completed += 1
            try:
                live = future.result()
            except Exception as exc:
                if verbose:
                    print(f"  [error] {fqdn}: {exc}")
                continue

            if live:
                found.append(fqdn)
                print(f"  [new]   {fqdn}")
            elif verbose:
                print(f"  [miss]  {fqdn}")

            if completed % 500 == 0:
                print(f"  … {completed:,}/{total:,} probed, {len(found)} new so far")

    return sorted(found)

# ── Output ────────────────────────────────────────────────────────────────────

DISCOVERY_HEADER_TEMPLATE = """\


# ══════════════════════════════════════════════════════════════════════════════
# DISCOVERED DOMAINS — generated {date}
#
# These entries were found via DNS subdomain enumeration of the seed domains
# listed in scripts/vendors.txt. Re-run scripts/build_blocklist.py to refresh.
# ══════════════════════════════════════════════════════════════════════════════
"""

DISCOVERY_FOOTER = """
# ── End of discovered domains ─────────────────────────────────────────────────
"""


def build_output(
    existing_lines: list[str],
    new_domains: list[str],
) -> str:
    """
    Reconstruct the blocklist:
    - Existing content (deduped) verbatim.
    - Remove any previous generated section if present.
    - Append a fresh generated section for new_domains.
    """
    # Strip any existing generated section (from a previous run).
    cutoff = None
    for i, line in enumerate(existing_lines):
        if "DISCOVERED DOMAINS — generated" in line:
            # Walk back to remove the preceding blank lines too.
            cutoff = i
            while cutoff > 0 and existing_lines[cutoff - 1].strip() == "":
                cutoff -= 1
            break

    base_lines = existing_lines[:cutoff] if cutoff is not None else existing_lines

    parts: list[str] = ["\n".join(base_lines)]

    if new_domains:
        date_str = datetime.date.today().isoformat()
        parts.append(DISCOVERY_HEADER_TEMPLATE.format(date=date_str))
        for domain in new_domains:
            parts.append(f"0.0.0.0 {domain}")
        parts.append(DISCOVERY_FOOTER)

    return "\n".join(parts) + "\n"

# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run",  action="store_true",
                        help="Discover and print but do not write to the blocklist.")
    parser.add_argument("--verbose",  action="store_true",
                        help="Log every DNS probe result.")
    parser.add_argument("--workers",  type=int, default=50, metavar="N",
                        help="Parallel DNS workers (default: 50).")
    parser.add_argument("--timeout",  type=float, default=3.0, metavar="S",
                        help="DNS query timeout in seconds (default: 3).")
    args = parser.parse_args()

    # ── Validate inputs ───────────────────────────────────────────────────────
    if not VENDORS_FILE.exists():
        sys.exit(f"Error: vendors file not found at {VENDORS_FILE}")
    if not BLOCKLIST_FILE.exists():
        sys.exit(f"Error: blocklist not found at {BLOCKLIST_FILE}")

    # ── Parse inputs ──────────────────────────────────────────────────────────
    print(f"Reading seeds from       {VENDORS_FILE.relative_to(REPO_ROOT)}")
    seeds = parse_vendors(VENDORS_FILE)
    print(f"  {len(seeds)} seed domains loaded.")

    print(f"Reading blocklist from   {BLOCKLIST_FILE.relative_to(REPO_ROOT)}")
    existing_lines, known_domains = parse_blocklist(BLOCKLIST_FILE)
    print(f"  {len(known_domains)} domains already in blocklist.")

    # ── Discover ──────────────────────────────────────────────────────────────
    print("\nStarting DNS discovery…")
    new_domains = discover(seeds, known_domains, args.workers, args.timeout, args.verbose)
    print(f"\nDiscovery complete: {len(new_domains)} new domain(s) found.")

    if not new_domains:
        print("Nothing new to add. Blocklist is up to date.")
        return

    # ── Write ─────────────────────────────────────────────────────────────────
    output = build_output(existing_lines, new_domains)

    if args.dry_run:
        print("\n[dry-run] Would append the following domains:")
        for d in new_domains:
            print(f"  0.0.0.0 {d}")
        print(f"\n[dry-run] Blocklist NOT modified.")
        return

    BLOCKLIST_FILE.write_text(output, encoding="utf-8")
    print(f"\nBlocklist updated: {BLOCKLIST_FILE.relative_to(REPO_ROOT)}")
    print(f"  Total domains now: {len(known_domains) + len(new_domains):,}")
    print(f"\nNext step: run scripts/sign_blocklist.py to re-sign the blocklist.")


if __name__ == "__main__":
    main()

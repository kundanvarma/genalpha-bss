#!/usr/bin/env python3
"""docs/billing-revenue-desk.md proves itself against the code it describes.

The Billing & Revenue arc's document makes five structural claims that a reader
has no way to verify and every reason to trust. Each one is read off the source
here instead of being typed from memory:

  situations   the eight situations and their precedence — from the ORDER of the
               returns in BillSituations.java, resolved through the constants in
               BillSituation.java. The precedence IS that branch order, so the
               prose cannot drift from the calculator without this going red.
  channels     the four channel modules the document names exist, each knows all
               eight situations, and NONE of them owns a clock. A channel that
               wanted to invent its own lateness would need date arithmetic; a
               bare `new Date()` or `Date.now()` in one of these four files is
               that invention starting, and it is the whole architectural claim
               of the arc ("invoice.overdue is never a UX input again").
  destinations the six destinations, in the order the money moves — from the
               `groups` of the Billing & Revenue workspace in the console's
               nav.js, compared against BOTH the document's bolded list and its
               table, in order.
  pages        how many pages sit under the department — from that workspace's
               own `tabs`, compared against the number the document spells out.
  navceiling   nav.js sits exactly on the front-end line ceiling. Both numbers
               are read from source: `wc -l` on the file, and MAX_FE_LINES from
               ops/arch/ratchet.sh. A document that says "at the ceiling" must
               still be right after either one moves.

Prints one line per drift on stdout, or nothing. ops/arch/claims.sh fails on any
output — AND requires the sentinel this prints on stderr, because a checker that
crashes prints nothing at all, which reads exactly like a pass. That is the
failure this repository has been bitten by often enough to gate against.

Run it directly to see the same answer:  python3 ops/arch/billing_claims.py
"""
import re
import sys

DOC = "docs/billing-revenue-desk.md"
NAV = "apps/admin-console/site/core/nav.js"
RATCHET = "ops/arch/ratchet.sh"
SITU_DTO = "services/billing/src/main/java/com/bss/billing/dto/BillSituation.java"
SITU_SVC = "services/billing/src/main/java/com/bss/billing/service/BillSituations.java"
WORKSPACE = "Billing & Revenue"

WORDS = {
    "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11,
    "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15, "sixteen": 16,
}


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


# --------------------------------------------------------------- the code ----
def code_precedence():
    """The eight situation values, highest precedence first.

    BillSituations.of() returns down a fixed ladder; that order IS the
    precedence. The constants carry the wire values.
    """
    values = dict(re.findall(r'String ([A-Z_]+)\s*=\s*"([A-Za-z]+)"', read(SITU_DTO)))
    order = re.findall(r"BillSituation\.([A-Z_]+)", read(SITU_SVC))
    out = []
    for name in order:
        if name in values and values[name] not in out:
            out.append(values[name])
    return out, sorted(values.values())


def workspace_block():
    """The Billing & Revenue entry of nav.js's WORKSPACES, as text."""
    nav = read(NAV)
    start = nav.find(f"label: '{WORKSPACE}'")
    if start < 0:
        return None
    start = nav.rfind("{", 0, start)
    depth, i = 0, start
    while i < len(nav):
        if nav[i] in "{[":
            depth += 1
        elif nav[i] in "}]":
            depth -= 1
            if depth == 0:
                return nav[start:i + 1]
        i += 1
    return None


def nav_shape():
    """(destination labels in order, number of pages under the department)."""
    block = workspace_block()
    if not block:
        return None, None
    first = re.search(r"tabs:\s*\[(.*?)\]", block, re.S)
    pages = len(re.findall(r"'([^']+)'", first.group(1))) if first else None
    groups = block.find("groups:")
    labels = re.findall(r"label:\s*'([^']+)'", block[groups:]) if groups >= 0 else []
    return labels, pages


def ceiling():
    """MAX_FE_LINES — the front-end line ceiling the ratchet enforces."""
    hit = re.search(r"^MAX_FE_LINES=(\d+)", read(RATCHET), re.M)
    return int(hit.group(1)) if hit else None


# ---------------------------------------------------------------- the doc ----
def doc_precedence(doc):
    """The precedence sentence's eight backticked values, in order."""
    hit = re.search(r"precedence is fixed\. Highest first:\s*(.*?)\.\n", doc, re.S)
    return re.findall(r"`([A-Za-z]+)`", hit.group(1)) if hit else None


def doc_channels(doc):
    """The channel table's module paths, in the order the document lists them."""
    return re.findall(r"^\|\s*[^|]+\|\s*`(apps/[^`]+)`\s*\|\s*$", doc, re.M)


def doc_destinations(doc):
    """The bolded six-list in the intro, and the destination table's own rows."""
    listed = re.search(r"^\*\*((?:\w+ · )+\w+)\*\*\.\s*$", doc, re.M)
    listed = [x.strip() for x in listed.group(1).split("·")] if listed else None
    table = re.findall(r"^\|\s*\*\*([A-Z][a-z]+)\*\*\s*\|", doc, re.M)
    return listed, table


def doc_number(doc, noun):
    """A number the document spells out in words, e.g. 'fourteen pages'."""
    hit = re.search(r"\b(" + "|".join(WORDS) + r")\s+" + noun, doc)
    return WORDS[hit.group(1)] if hit else None


# --------------------------------------------------------------- the gate ----
def drift():
    doc, out, checked = read(DOC), [], 0

    # 1 — the eight situations and their precedence
    checked += 1
    order, known = code_precedence()
    claimed = doc_precedence(doc)
    if claimed is None:
        out.append(f"{DOC} states no situation precedence — the sentence the gate reads is gone")
    elif claimed != order:
        out.append(f"{DOC} says the precedence is {' · '.join(claimed)}; "
                   f"BillSituations.of() returns {' · '.join(order)}")
    if len(order) != len(known):
        out.append(f"BillSituations.of() reaches {len(order)} of the {len(known)} "
                   f"situations BillSituation.java declares — one is unreachable or undocumented")

    # 2 — four channels, each knowing all eight, none owning a clock
    checked += 1
    modules = doc_channels(doc)
    if len(modules) != 4:
        out.append(f"{DOC} names {len(modules)} channel situation modules, expected 4 "
                   "(storefront · mobile · CSR console · back office)")
    for path in modules:
        try:
            src = read(path)
        except OSError:
            out.append(f"{DOC} names channel module {path}, which does not exist")
            continue
        missing = [v for v in known if not re.search(r"\b" + v + r"\b", src)]
        if missing:
            out.append(f"{path} does not know situation(s) {' · '.join(missing)} — "
                       "a channel that cannot name a situation will render it as nothing")
        clock = re.search(r"Date\.now\(\)|new Date\(\s*\)", src)
        if clock:
            out.append(f"{path} reads the clock ({clock.group(0)}) — a channel must not "
                       "compute lateness; the situation is billing's answer")

    # 3 — the six destinations, in the order the money moves
    checked += 1
    labels, pages = nav_shape()
    listed, table = doc_destinations(doc)
    if labels is None:
        out.append(f"{NAV} has no '{WORKSPACE}' workspace — the desk this document describes is gone")
    else:
        for what, claim in (("bolded list", listed), ("destination table", table)):
            if claim is None:
                out.append(f"{DOC} has no {what} of destinations for the gate to read")
            elif claim != labels:
                out.append(f"{DOC}'s {what} says {' · '.join(claim)}; "
                           f"{NAV} groups them {' · '.join(labels)}")

    # 4 — how many pages sit under the department
    checked += 1
    said = doc_number(doc, "pages")
    if said is None:
        out.append(f"{DOC} does not say how many pages sit under the department")
    elif pages is not None and said != pages:
        out.append(f"{DOC} says {said} pages under {WORKSPACE}; {NAV} lists {pages}")

    # 5 — the spelled-out counts, WHEREVER they are claimed. The README makes
    # the same two claims in a shorter voice, and a row nobody checks is how a
    # stale number survives: the doc gets fixed and the summary does not.
    checked += 1
    for noun, truth, source in (("situations", len(known), SITU_DTO),
                                ("destinations", len(labels or []), NAV)):
        for path in ("README.md", DOC):
            for word in set(re.findall(r"\b(" + "|".join(WORDS) + r")\s+" + noun, read(path))):
                if WORDS[word] != truth:
                    out.append(f"{path} says {word} {noun}; {source} has {truth}")

    # 6 — nav.js sits exactly on the front-end ceiling
    checked += 1
    lines = len(read(NAV).splitlines())
    limit = ceiling()
    hit = re.search(r"`" + re.escape(NAV) + r"` is (\d+) lines and the front-end\s*\n?\s*ceiling is (\d+) lines", doc)
    if not hit:
        out.append(f"{DOC} no longer states {NAV}'s length and the front-end ceiling in the "
                   "sentence the gate reads")
    else:
        if int(hit.group(1)) != lines:
            out.append(f"{DOC} says {NAV} is {hit.group(1)} lines; it is {lines}")
        if limit is not None and int(hit.group(2)) != limit:
            out.append(f"{DOC} says the front-end ceiling is {hit.group(2)} lines; "
                       f"{RATCHET} enforces {limit}")

    return out, checked


if __name__ == "__main__":
    lines, n = drift()
    for line in lines:
        print(line)
    # the sentinel: claims.sh refuses a run that does not produce it, so a
    # crash or a silently empty run cannot read as a pass
    print(f"billing-claims: checked {n} claims", file=sys.stderr)
    sys.exit(0)

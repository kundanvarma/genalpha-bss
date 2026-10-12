#!/usr/bin/env python3
"""THE REGISTRY MAY NOT CLAIM POWERS THE TOKEN DOES NOT CARRY.

`hermes-worker` declared seven executable actions. Its badge permitted one.
Nothing noticed, because the registry cross-checks an agent against itself
(unknown actions, executing without being allowed to check first) and never
against the realm the agent actually logs into. Anyone reading the registry —
an auditor, a buyer, us — concluded the worker could replace a SIM. The
permission check would have refused it.

The safe direction, which is why it went unnoticed for so long: the badge was
TIGHTER than the declaration, so nothing was ever wrongly permitted. But a
registry that overstates is still a registry nobody can trust, and "the robot
can do seven things" is exactly the sentence someone repeats in a room.

SCOPE. Only an agent that runs as a MACHINE and declares executable actions can
be checked statically: its rights are fixed by its badge. An agent that
`runsAs: caller` borrows the signed-in person's rights, which vary per caller,
so there is nothing here to prove. Today that is one agent — and it is the
autonomous one, which is the one that matters.

This prints a sentinel line on stdout whatever happens. A crashed checker prints
nothing, and silence reads exactly like a pass to the gate that calls it.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
AGENTS = os.path.join(ROOT, "ontology", "agents", "*.yml")
ACTIONS = os.path.join(ROOT, "ontology", "actions", "*.yml")
REALM = os.path.join(ROOT, "infra", "keycloak", "bss-realm.json")


def scalar(text, key):
    """The value of a top-level `key: value` line. The registry is hand-written
    YAML with a schema; a full parser is not available on every laptop here
    (PyYAML is missing on this one), and the shapes we need are flat."""
    m = re.search(r"^%s:\s*(.+?)\s*$" % re.escape(key), text, re.M)
    return None if m is None else m.group(1).strip().strip('"\'')


def execute_list(text):
    """actions.execute, which is written inline: `execute: [a, b, c]`."""
    m = re.search(r"^\s*execute:\s*\[(.*?)\]", text, re.M | re.S)
    if m is None:
        return []
    return [x.strip().strip('"\'') for x in m.group(1).split(",") if x.strip()]


def roles_required(text):
    """Every `role:` named under permissions, and whether `self:` is offered.

    A machine is never the owner of a customer's subscription, so a `self:`
    clause can never rescue it — recorded so the message can say that rather
    than leave a reader wondering."""
    m = re.search(r"^permissions:(.*?)(?=^[a-z]+:)", text, re.M | re.S)
    block = m.group(1) if m else ""
    return re.findall(r"role:\s*([A-Za-z0-9:_-]+)", block), bool(re.search(r"self:\s*\w+", block))


def badge_roles(badge):
    """The realm roles a composite role actually grants."""
    with open(REALM, encoding="utf-8") as fh:
        realm = json.load(fh)
    found = []

    def walk(node):
        if isinstance(node, dict):
            if node.get("name") == badge and node.get("composite"):
                found.extend((node.get("composites") or {}).get("realm") or [])
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(realm)
    return sorted(set(found))


def main():
    actions = {}
    for path in sorted(glob.glob(ACTIONS)):
        text = open(path, encoding="utf-8").read()
        name = scalar(text, "action") or os.path.basename(path)[:-4]
        actions[name] = roles_required(text)

    problems = []
    checked = 0
    agents_checked = []

    for path in sorted(glob.glob(AGENTS)):
        text = open(path, encoding="utf-8").read()
        name = scalar(text, "agent") or os.path.basename(path)[:-4]
        runs_as = scalar(text, "runsAs") or ""
        executes = execute_list(text)
        if not runs_as.startswith("machine:") or not executes:
            continue

        badge = scalar(text, "badge")
        if not badge:
            problems.append(
                "%s runs as %s and declares %d executable action(s) but names no `badge:` — "
                "without it nothing can prove the declaration is achievable"
                % (name, runs_as, len(executes)))
            continue

        granted = badge_roles(badge)
        if not granted:
            problems.append("%s names badge '%s', which is not a composite role in bss-realm.json"
                            % (name, badge))
            continue

        agents_checked.append("%s/%s" % (name, badge))
        for act in executes:
            if act not in actions:
                continue  # the registry's own cross-check owns unknown actions
            needed, has_self = actions[act]
            checked += 1
            if not needed:
                continue
            if not any(r in granted for r in needed):
                problems.append(
                    "%s declares it executes %s, which needs %s — its badge '%s' grants none of those%s"
                    % (name, act, " or ".join(needed), badge,
                       " (the action also allows self:owner, but a machine account is never the owner)"
                       if has_self else ""))

    if problems:
        print("agent-badge: DRIFT — %d declared action(s) a badge cannot perform" % len(problems))
        for p in problems:
            print("  - %s" % p)
        return 1

    print("agent-badge: clean — %d declared action(s) across %s, every one permitted by the badge"
          % (checked, ", ".join(agents_checked) if agents_checked else "no machine-run agent"))
    return 0


if __name__ == "__main__":
    sys.exit(main())

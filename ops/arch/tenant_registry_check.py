#!/usr/bin/env python3
"""The tracked tenant registry may only declare tenants somebody decided on.

infra/tenants/tenants.yml is a git-tracked fixture the onboarding suites
rewrite through the API, and ops/run-all-suites.sh restores it from git
afterwards -- so the committed file is the truth the fleet boots from. It
governs every tenant's issuer, JWKS URI, machine credentials, seam URLs and
brand.

Three tenants with GENERATED ids had accumulated in it by 8 Oct 2026
(sc229066, ib249958, parity148420 -- from shadow_clone, import_base and
price_parity, each of which derives its id from Date.now()). Nothing could
ever reference them by name, and they had leaked into docker-compose.yml's
OCS_NOTIFY_SECRETS too. Left alone, each run of those suites adds another.

Checked in BOTH directions against ops/arch/tenants-declared.txt, because a
one-way check rots: a tenant in the registry and not on the list fails, and a
name on the list the registry no longer declares fails too.

Run: python3 ops/arch/tenant_registry_check.py
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "infra/tenants/tenants.yml"
DECLARED = ROOT / "ops/arch/tenants-declared.txt"
COMPOSE = ROOT / "docker-compose.yml"

# ids that are generated per run — the shape that caused this
GENERATED = re.compile(r"^(sc\d{4,}|ib\d{4,}|parity\d{4,}|[a-z]+\d{6,})$")


def declared_names():
    names = []
    for raw in DECLARED.read_text().splitlines():
        # entries start at column 0; an indented line is a wrapped reason, and
        # reading those as names is how the first version of this reported
        # tenants called "ai-visibility" and "run-all-suites.sh"
        if not raw or raw[0].isspace():
            continue
        line = raw.split("#", 1)[0].strip()
        if line:
            names.append(line.split()[0])
    return names


def main():
    if not REGISTRY.exists():
        print("tenant-registry: FAIL — infra/tenants/tenants.yml is missing")
        return 1

    in_registry = re.findall(r"^\s+- id: ([a-z0-9-]+)", REGISTRY.read_text(), re.M)
    declared = declared_names()

    problems = []
    for t in sorted(set(in_registry) - set(declared)):
        hint = "  (a generated id — residue from a suite run)" if GENERATED.match(t) else ""
        problems.append(f"the registry declares '{t}', which is not in tenants-declared.txt{hint}")
    for t in sorted(set(declared) - set(in_registry)):
        problems.append(f"tenants-declared.txt lists '{t}', which the registry no longer declares")

    # the same residue leaked into the compose secrets map once; hold that too
    if COMPOSE.exists():
        compose = COMPOSE.read_text()
        for t in sorted(set(re.findall(r'"([a-z0-9-]+)":"ocs-notify-', compose))):
            if t not in declared:
                problems.append(f"docker-compose.yml names '{t}' in OCS_NOTIFY_SECRETS, "
                                f"and it is not a declared tenant")

    if problems:
        print(f"tenant-registry: FAIL — {len(problems)} problem(s)")
        for p in problems:
            print(f"  {p}")
        print("\nA tenant in the tracked registry is a decision, not a side effect of a")
        print("suite run. Add it to ops/arch/tenants-declared.txt with a reason, or")
        print("remove it from infra/tenants/tenants.yml.")
        return 1

    print(f"tenant-registry: clean — {len(in_registry)} tenants, every one declared")
    return 0


if __name__ == "__main__":
    sys.exit(main())

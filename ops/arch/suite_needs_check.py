#!/usr/bin/env python3
"""Every downstream a suite names must be a real compose service.

The proof runner reads ops/e2e/suite-needs.txt and starts a suite's downstreams
with one `docker compose up -d --no-deps <names>`. One name that is not a
service makes that whole command fail, and the runner swallows the failure on
purpose (a fleet that is already up must not be an error). The suite then runs
against a stopped downstream and reports a bug that is not there — which is
exactly the failure the file's own header says it exists to prevent.

Prints one line naming every bad entry, or nothing. ops/arch/claims.sh fails
on any output. Run it directly to see the same answer.
"""
import re
import sys

SERVICE = re.compile(r"^  ([a-z0-9][a-z0-9._-]*):\s*$")


def services(path="docker-compose.yml"):
    """Top-level service keys — two spaces of indent under `services:`."""
    found = set()
    for line in open(path, encoding="utf-8"):
        match = SERVICE.match(line)
        if match:
            found.add(match.group(1))
    return found


def bad_needs(known, path="ops/e2e/suite-needs.txt"):
    out = []
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        for need in parts[1:]:
            if need not in known:
                out.append(f"{parts[0]} needs '{need}'")
    return out


if __name__ == "__main__":
    known = services()
    if not known:
        print("docker-compose.yml defines no services — is this the repo root?")
        sys.exit(0)
    print("; ".join(bad_needs(known)))

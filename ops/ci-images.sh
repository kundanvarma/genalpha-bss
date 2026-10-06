#!/usr/bin/env bash
# WHICH IMAGES THIS REPOSITORY SHIPS — one list, derived, never typed.
#
# The image job used to walk `services/*/`, which quietly meant NO channel
# image was ever built in CI: the storefront, the four consoles, the CSR desk
# and the mobile app got no build, no SBOM, no vulnerability scan, no signature
# and no provenance, while all 42 services got the lot. The most
# internet-exposed artifact in the repository was the one with no evidence
# attached to it.
#
# A glob could not have fixed it either. Three things make the list a
# derivation rather than a pattern:
#   - a compose service's name is not always its directory: `console` is built
#     from apps/admin-console, `mobile-app` from apps/mobile;
#   - apps/storefront is the context for TWO images, `storefront` (nginx) and
#     `storefront-ssr` (the renderer), so the list keys on the NAME;
#   - storefront-ssr's Dockerfile is `Dockerfile.ssr`, which no `*/Dockerfile`
#     glob will ever see.
#
# So docker-compose is the source of truth — the same file the fleet boots from,
# which is what stops a shipped image from existing in one place and not the
# other. Prints one line per image:
#
#   <name>\t<context>\t<dockerfile>
#
# WHAT A CHANNEL'S SBOM ACTUALLY COVERS, measured rather than assumed, because
# "every image has an SBOM" is the kind of claim that reads as more than it is.
# For the six nginx-served channels it is the BASE IMAGE ONLY — 70 Alpine/nginx
# libraries, one OS, and zero npm components. Their JavaScript is compiled into
# vite bundles, and a bundle carries no package manifest for syft to read, so
# the dependency tree that produced it is invisible to an image scan.
#
# storefront-ssr is the exception and shows what the others are missing: it
# ships node_modules, so react, react-dom and react-router appear by name.
#
# This is still worth having — the base image is where an nginx container's CVEs
# live, and those are now visible and signed for the internet-facing artifacts.
# But a real dependency SBOM for a static channel has to come from the SOURCE
# (`syft dir:apps/<channel>`, or the lockfile) rather than the image, and that
# is a follow-up, not something this script quietly pretends to do.
#
# WHAT IS DELIBERATELY NOT HERE: integrations/* — the vendor stand-ins. They are
# test doubles, not artifacts anyone deploys, and publishing 38 signed mocks to
# GHCR would say they are releases. The cost of leaving them out is real and
# worth naming: a mock's Dockerfile breaks only when a fleet boots it, which is
# the storefront slice for the few in that tier and the nightly full proof for
# the rest.
set -uo pipefail
cd "$(dirname "$0")/.."

docker compose config --format json 2>/dev/null | python3 -c '
import json, os, sys

cfg = json.load(sys.stdin)
cwd = os.getcwd()
rows, by_name = [], {}
for name, svc in sorted((cfg.get("services") or {}).items()):
    build = svc.get("build")
    if not build:
        continue                      # an upstream image (postgres, kafka, …)
    if isinstance(build, dict):
        ctx = build.get("context") or "."
        dockerfile = build.get("dockerfile") or "Dockerfile"
    else:
        ctx, dockerfile = build, "Dockerfile"
    ctx = os.path.relpath(ctx, cwd)
    if not (ctx.startswith("services/") or ctx.startswith("apps/")):
        continue                      # integrations/* are stand-ins, see above
    rows.append((name, ctx, dockerfile))
    by_name[name] = ctx

# THE INVARIANT, checked rather than assumed: every service with a Dockerfile on
# disk is in the fleet file. Without this the derivation would silently stop
# building a service that someone adds to services/ and forgets to add to
# compose — swapping one blind spot for another, which is how the channels went
# unbuilt in the first place.
on_disk = {d for d in os.listdir("services")
           if os.path.isfile(os.path.join("services", d, "Dockerfile"))}
contexts = set(by_name.values())
orphans = sorted(d for d in on_disk if f"services/{d}" not in contexts)
if orphans:
    sys.stderr.write(
        "ci-images: these services have a Dockerfile but no docker-compose entry, "
        "so nothing would build them: " + ", ".join(orphans) + "\n"
        "  fix: add them to docker-compose.yml, or delete the Dockerfile\n")
    sys.exit(1)

for name, ctx, dockerfile in rows:
    print(f"{name}\t{ctx}\t{dockerfile}")
'

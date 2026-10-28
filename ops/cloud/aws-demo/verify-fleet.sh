#!/usr/bin/env bash
# The fleet that is running IS the fleet that was built (#380).
#
# On 28 Oct 2026 install.sh exited 0 while the box was not what it claimed:
# `docker compose up -d >/dev/null 2>&1` had been cut short, ~80 containers still
# ran their OLD images, 78 renamed `<hash>_bss-*` leftovers crash-looped beside
# the real services, the gateway sat in `Created` (the site was down), and six
# containers had been on old images since an earlier deploy. An exit code proves
# none of that is false; this reads the running fleet instead:
#
#   1. every running container's image is its tag's CURRENT image
#   2. no `<12 hex>_name` leftovers from an interrupted recreate
#   3. nothing `Created` (never started), `Restarting` or unhealthy
#
# It first waits for the fleet to settle — health checks still `starting` are
# not yet a verdict — up to SETTLE_SECONDS (default 900).
#
#   verify-fleet.sh            # wait, check, exit 1 naming every offender
#   verify-fleet.sh --repair   # first: remove leftovers, force-recreate stale
#                              # containers, start never-started ones; then check
#   verify-fleet.sh --now      # check at once, no settle wait
set -uo pipefail
cd "$(dirname "$0")/../../.."
export COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml:docker-compose.cloud.yml}"
PROJECT="${COMPOSE_PROJECT:-$(basename "$PWD" | tr '[:upper:]' '[:lower:]')}"
SETTLE="${SETTLE_SECONDS:-900}"
REPAIR=0; NOW=0
for a in "$@"; do
  case "$a" in --repair) REPAIR=1 ;; --now) NOW=1 ;; *) echo "unknown option $a" >&2; exit 2 ;; esac
done

# containers of THIS compose project, by name
ours() { docker ps -a --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}}'; }

leftovers() { ours | grep -E '^[0-9a-f]{12}_' || true; }

stale() {
  for c in $(docker ps --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}}'); do
    ref=$(docker inspect -f '{{.Config.Image}}' "$c")
    cur=$(docker inspect -f '{{.Image}}' "$c")
    new=$(docker image inspect -f '{{.Id}}' "$ref" 2>/dev/null || true)
    [ -n "$new" ] && [ "$cur" != "$new" ] && echo "$c"
  done
}

stuck() {
  docker ps -a --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}} {{.State}} {{.Status}}' \
    | grep -vE '^[0-9a-f]{12}_' \
    | awk '$2=="created" || $2=="restarting" || /\(unhealthy\)/ {print $1" ("$2")"}'
}

starting() {
  docker ps --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}} {{.Status}}' \
    | grep -c 'health: starting' || true
}

service_of() { docker inspect -f '{{index .Config.Labels "com.docker.compose.service"}}' "$1"; }

if [ $REPAIR -eq 1 ]; then
  l=$(leftovers)
  if [ -n "$l" ]; then
    echo "verify-fleet: removing $(wc -l <<<"$l" | tr -d ' ') leftover(s) of an interrupted recreate"
    # containers only — volumes are untouched (no -v)
    xargs -r docker rm -f <<<"$l" >/dev/null
  fi
  for c in $(stale); do
    s=$(service_of "$c"); echo "verify-fleet: $c runs an old image — recreating $s"
    docker compose up -d --no-deps --force-recreate "$s" 2>&1 | grep -iE 'error|conflict' || true
  done
  for c in $(docker ps -a --filter "label=com.docker.compose.project=$PROJECT" --filter status=created --format '{{.Names}}' | grep -vE '^[0-9a-f]{12}_'); do
    echo "verify-fleet: $c was created but never started — starting it"
    docker start "$c" >/dev/null
  done
fi

if [ $NOW -eq 0 ]; then
  for ((waited = 0; waited < SETTLE; waited += 15)); do
    [ "$(starting)" -eq 0 ] && break
    sleep 15
  done
fi

bad=0
l=$(leftovers); s=$(stale); k=$(stuck)
[ -n "$l" ] && { echo "verify-fleet: LEFTOVERS of an interrupted recreate:" $l; bad=1; }
[ -n "$s" ] && { echo "verify-fleet: STALE — running an old image:" $s; bad=1; }
[ -n "$k" ] && { echo "verify-fleet: NOT RUNNING HEALTHY:" $(tr '\n' ' ' <<<"$k"); bad=1; }
running=$(docker ps --filter "label=com.docker.compose.project=$PROJECT" -q | wc -l | tr -d ' ')
if [ $bad -eq 0 ]; then
  echo "verify-fleet: ok — $running containers, every one on its current image, none stuck, no leftovers"
  exit 0
fi
exit 1

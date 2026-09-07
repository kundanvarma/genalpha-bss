#!/usr/bin/env bash
# Taranga hosted demo — one-box install on Ubuntu 24.04 (x86_64), idempotent.
#
#   sudo -E ops/cloud/aws-demo/install.sh            # first run and re-runs
#
# Reads /etc/taranga-demo.env (created on first run from the DEMO_* variables
# below, then edited by hand). Stages, each skipped when already done:
#   1. packages: docker + compose plugin, JDK 17 + maven, caddy, swap
#   2. repo: deploy key → clone/pull (pauses once for you to add the key on GitHub)
#   3. secrets: .env with generated Postgres/Keycloak passwords
#   4. build: mvn package (host-built jars, ~15 min on 8 vCPU) + docker compose build
#   5. cloud override: public issuer + Keycloak hostname for every service
#   6. fleet up → demo slice → seeds (taranga always; enet when DEMO_ENET=1)
#   7. front door: Caddy with automatic HTTPS for every public host
set -euo pipefail
CONF=/etc/taranga-demo.env
if [ ! -f "$CONF" ]; then
  cat > "$CONF" <<EOF
# ---- Taranga hosted demo configuration (edit, then re-run install.sh) ----
DEMO_DOMAIN=${DEMO_DOMAIN:-taranga.no}
DEMO_ENET=${DEMO_ENET:-1}                 # 1 = also seed + expose the ENet tenant as *-enet.\$DEMO_DOMAIN
GIT_URL=${GIT_URL:-git@github.com:kundanvarma/genalpha-bss.git}
GIT_REF=${GIT_REF:-main}
LETSENCRYPT_EMAIL=${LETSENCRYPT_EMAIL:-}
DEMO_USER=ubuntu                          # unix user that owns the checkout
APP_DIR=/opt/taranga/bss
# optional: gate the staff consoles (console-, csr-, biz-) behind HTTP basic auth
DEMO_GATE_USER=${DEMO_GATE_USER:-}
DEMO_GATE_PASSWORD=${DEMO_GATE_PASSWORD:-}
# optional: real-model copilots (kept out of git; Anthropic-compatible)
AI_PROVIDER=${AI_PROVIDER:-stub}
AI_BASE_URL=${AI_BASE_URL:-}
AI_API_KEY=${AI_API_KEY:-}
AI_MODEL=${AI_MODEL:-}
EOF
  echo "[install] wrote $CONF — review it, then re-run."; exit 0
fi
# shellcheck disable=SC1090
source "$CONF"
[ -n "${LETSENCRYPT_EMAIL:-}" ] || { echo "[install] set LETSENCRYPT_EMAIL in $CONF"; exit 1; }
HOME_DIR=$(getent passwd "$DEMO_USER" | cut -d: -f6)
log() { echo "[install] $*"; }
as_user() { sudo -u "$DEMO_USER" -H bash -lc "$*"; }

# ---------- 1. packages ----------
if ! command -v docker >/dev/null; then
  log "docker"
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
  apt-get update -qq && apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin
  usermod -aG docker "$DEMO_USER"
fi
if ! command -v caddy >/dev/null; then
  log "caddy"
  apt-get install -y -qq debian-keyring debian-archive-keyring apt-transport-https curl
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -qq && apt-get install -y -qq caddy
fi
command -v mvn >/dev/null || { log "jdk + maven"; apt-get install -y -qq openjdk-17-jdk-headless maven git python3 python3-yaml apache2-utils; }
if [ ! -f /swapfile ]; then
  log "8 GB swap (headroom for the Maven build)"
  fallocate -l 8G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile && echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
sysctl -q -w vm.max_map_count=262144; grep -q max_map_count /etc/sysctl.conf || echo 'vm.max_map_count=262144' >> /etc/sysctl.conf

# ---------- 2. repo ----------
# GIT_URL=local: the checkout was copied onto the box (rsync from a laptop);
# skip the deploy key and the clone/pull, use what is in APP_DIR as-is.
if [ "${GIT_URL:-}" = "local" ]; then
  [ -d "$APP_DIR" ] || { echo "[install] GIT_URL=local but $APP_DIR is missing — rsync the repo first"; exit 1; }
  log "repo: local checkout at $APP_DIR ($(cd "$APP_DIR" && git rev-parse --short HEAD 2>/dev/null || echo 'no git'))"
elif [ ! -f "$HOME_DIR/.ssh/id_ed25519" ]; then
  as_user "ssh-keygen -q -t ed25519 -N '' -C 'taranga-demo-deploy' -f ~/.ssh/id_ed25519"
  as_user "ssh-keyscan github.com >> ~/.ssh/known_hosts 2>/dev/null"
  echo; echo "Add this READ-ONLY deploy key to the GitHub repo (Settings → Deploy keys), then re-run:"; echo
  cat "$HOME_DIR/.ssh/id_ed25519.pub"; echo; exit 0
fi
mkdir -p "$(dirname "$APP_DIR")" && chown "$DEMO_USER" "$(dirname "$APP_DIR")"
if [ "${GIT_URL:-}" != "local" ]; then
  if [ ! -d "$APP_DIR/.git" ]; then
    log "clone $GIT_URL ($GIT_REF)"; as_user "git clone -q --branch '$GIT_REF' '$GIT_URL' '$APP_DIR'"
  else
    log "pull $GIT_REF"; as_user "cd '$APP_DIR' && git fetch -q && git checkout -q '$GIT_REF' && git pull -q --ff-only"
  fi
fi
cd "$APP_DIR"

# ---------- 3. secrets ----------
if [ ! -f .env ]; then
  log ".env with generated passwords"
  PG=$(openssl rand -hex 16); KC=$(openssl rand -hex 16)
  sed -e "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$PG/" -e "s/^KEYCLOAK_ADMIN_PASSWORD=.*/KEYCLOAK_ADMIN_PASSWORD=$KC/" \
      -e "s/^GRAFANA_ADMIN_PASSWORD=.*/GRAFANA_ADMIN_PASSWORD=$KC/" \
      -e "s|^AI_PROVIDER=.*|AI_PROVIDER=$AI_PROVIDER|" -e "s|^AI_BASE_URL=.*|AI_BASE_URL=$AI_BASE_URL|" \
      -e "s|^AI_API_KEY=.*|AI_API_KEY=$AI_API_KEY|" -e "s|^AI_MODEL=.*|AI_MODEL=$AI_MODEL|" .env.example > .env
  chown "$DEMO_USER" .env; chmod 600 .env
fi

# ---------- 4. build ----------
STAMP=.cloud-build-$(git rev-parse --short HEAD 2>/dev/null || date +%s)
if [ ! -f "$STAMP" ]; then
  log "maven package (all 40 modules, tests skipped) — 10–20 min"
  as_user "cd '$APP_DIR' && mvn -q -T 1C package -DskipTests"
  log "docker compose build"
  as_user "cd '$APP_DIR' && docker compose build -q"
  rm -f .cloud-build-*; touch "$STAMP"
fi

# ---------- 5. cloud override ----------
log "docker-compose.cloud.yml for $DEMO_DOMAIN"
DEMO_DOMAIN="$DEMO_DOMAIN" python3 ops/cloud/aws-demo/gen-override.py > docker-compose.cloud.yml
chown "$DEMO_USER" docker-compose.cloud.yml
grep -q COMPOSE_FILE "$HOME_DIR/.bashrc" || echo 'export COMPOSE_FILE=docker-compose.yml:docker-compose.cloud.yml' >> "$HOME_DIR/.bashrc"
export COMPOSE_FILE=docker-compose.yml:docker-compose.cloud.yml

# ---------- 6. fleet + seeds ----------
if [ ! -f .cloud-seeded ]; then
  log "fleet up (first boot ~5 min), then the demo slice"
  as_user "cd '$APP_DIR' && COMPOSE_FILE=$COMPOSE_FILE ops/fleet.sh up"
  as_user "cd '$APP_DIR' && COMPOSE_FILE=$COMPOSE_FILE ops/fleet.sh demo"
  log "seeds: taranga"
  as_user "cd '$APP_DIR' && python3 ops/seed/seed_taranga.py && python3 ops/seed/seed_taranga_growth.py"
  if [ "${DEMO_ENET:-0}" = "1" ]; then
    log "seeds: enet"
    as_user "cd '$APP_DIR' && python3 ops/seed/seed_enet.py && python3 ops/seed/seed_enet_growth.py && python3 ops/seed/seed_enet_history.py"
  fi
  touch .cloud-seeded; chown "$DEMO_USER" .cloud-seeded
else
  log "fleet already seeded — starting what is down"
  as_user "cd '$APP_DIR' && COMPOSE_FILE=$COMPOSE_FILE docker compose up -d >/dev/null 2>&1; COMPOSE_FILE=$COMPOSE_FILE ops/fleet.sh demo"
fi

# ---------- 6b. boot hook: after a stop/start, shed to the demo slice on its own ----------
# Docker restarts the FULL fleet on boot (restart policies); on a 32 GB box that
# sits at the memory ceiling. This oneshot waits for Keycloak, then runs the same
# 'fleet.sh demo' the install ran, so a plain start is hands-off.
cat > /etc/systemd/system/taranga-demo-slice.service <<UNIT
[Unit]
Description=Taranga demo: shed the fleet to the demo slice after boot
After=docker.service network-online.target
Wants=docker.service

[Service]
Type=oneshot
User=$DEMO_USER
WorkingDirectory=$APP_DIR
Environment=COMPOSE_FILE=docker-compose.yml:docker-compose.cloud.yml
ExecStartPre=/bin/bash -c 'for i in \$(seq 1 120); do curl -sf -o /dev/null http://localhost:8085/realms/bss/.well-known/openid-configuration && exit 0; sleep 5; done; exit 1'
ExecStart=$APP_DIR/ops/fleet.sh demo
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload && systemctl enable taranga-demo-slice.service >/dev/null 2>&1 && log "boot hook: demo slice after every start"

# ---------- 7. front door ----------
log "Caddyfile"
DEMO_DOMAIN="$DEMO_DOMAIN" DEMO_ENET="$DEMO_ENET" LETSENCRYPT_EMAIL="$LETSENCRYPT_EMAIL" \
DEMO_GATE_USER="${DEMO_GATE_USER:-}" DEMO_GATE_PASSWORD="${DEMO_GATE_PASSWORD:-}" \
  bash ops/cloud/aws-demo/gen-caddyfile.sh > /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile >/dev/null && systemctl reload caddy || systemctl restart caddy
systemctl enable --now caddy >/dev/null
log "done. https://shop.$DEMO_DOMAIN  ·  https://console.$DEMO_DOMAIN  ·  https://csr.$DEMO_DOMAIN  ·  https://id.$DEMO_DOMAIN"
[ "${DEMO_ENET:-0}" = "1" ] && log "ENet: https://shop-enet.$DEMO_DOMAIN  ·  https://console-enet.$DEMO_DOMAIN  ·  https://csr-enet.$DEMO_DOMAIN"
exit 0

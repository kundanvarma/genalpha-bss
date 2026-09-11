#!/usr/bin/env bash
# fleet.sh — run the genalpha fleet the way a hyperscaler would run a cell:
# tiered boot, sized slices, and a memory relief valve. One file, no magic.
#
#   ops/fleet.sh up        boot in tiers (infra -> platform -> experience),
#                          then the identity warmup — the calm cold start
#   ops/fleet.sh demo      full journeys, minus optional weight (~16GB fits)
#   ops/fleet.sh full      everything on (wants 24-32GB)
#   ops/fleet.sh growth    the consumer-operator demo shape on a 21GB VM: the
#                          marketing slice (campaign insight event-hub flow) ON,
#                          the B2B/care/ledger weight OFF — shop, checkout,
#                          journeys, audiences, landing pages, loyalty all work
#   ops/fleet.sh refresh   relief valve: restart the heaviest JVMs + re-warm
#                          every route — run after hours of uptime, or ~15
#                          minutes before a live demo
#   ops/fleet.sh status    load, memory, front doors
#   ops/fleet.sh down      stop the whole fleet (data volumes persist)
#
# SERVICE TIERS (what must run where):
#   T0 spine        postgres kafka keycloak redis minio azurite gateway
#                   -- nothing works without these; production: managed
#                      equivalents (RDS/MSK/managed IdP) + HA gateway
#   T1 commerce     catalog/ordering/party/billing/usage/payment/fulfilment...
#                   -- the product; production: always on, 2+ replicas each
#                      (crash-resume + idempotency are suite-proven)
#   T2 experience   storefront + consoles -- static UIs, cheap; always on
#   T3 domains      wholesale, migration, device-commerce, martech, loyalty...
#                   -- run what you sell; each is independently stoppable
#   T3+ real seams  sigscale-ocs -- a REAL open-source OCS (Erlang node, ~150MB);
#                   tenants opt in via tenants.yml ocs-provider
#   T4 dev mocks    mock-* -- DEV ONLY; production replaces each with a real
#                      adapter behind the same seam (see docs: seams)
#   T5 ops extras   grafana/prometheus (prod: MUST; dev: optional),
#                   workforce + AI extras (optional everywhere)
set -u
cd "$(dirname "$0")/.."
export PATH=/opt/homebrew/bin:$PATH
DC="docker compose"

# The optional slice: everything a full customer-journey demo does NOT need.
# demo == full minus these. Stopping them buys ~2-3GB — the difference
# between a calm fleet and page-cache collapse on a 16-21GB machine.
OPTIONAL="grafana prometheus mock-llm market-provider-claude mock-whisper
          base-migration device-commerce mock-efaktura mock-digipost
          mock-avtalegiro mock-legacy"
# (worker-controller is profile-gated already; hermes-image-keeper is not
#  compose-managed — both handled below where relevant.)

INFRA="postgres redis minio azurite kafka keycloak"

kc_ready() {
  for i in $(seq 1 100); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' -m 8 \
      http://localhost:8085/realms/bss/.well-known/openid-configuration)" = "200" ] && return 0
    sleep 5
  done
  return 1
}

warm() {
  # nobody pays the cold-start tax mid-click: one cheap hit per door
  TOKEN=$(curl -s -m 10 -X POST http://localhost:8085/realms/bss/protocol/openid-connect/token \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d 'grant_type=password&client_id=bss-demo&username=demo&password=demo' \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null)
  for p in "/tmf-api/productCatalogManagement/v4/productOffering?limit=1" \
           "/tmf-api/customerBillManagement/v4/customerBill?limit=1" \
           "/tmf-api/productOrderingManagement/v4/productOrder?limit=1" \
           "/tmf-api/party/v4/individual?limit=1" \
           "/tmf-api/usageManagement/v4/usage?limit=1" \
           "/tmf-api/troubleTicket/v4/troubleTicket?limit=1" \
           "/tmf-api/communicationManagement/v4/communicationMessage?limit=1" \
           "/tmf-api/quoteManagement/v4/quote?limit=1"; do
    curl -s -o /dev/null -m 45 -H "Authorization: Bearer $TOKEN" "http://localhost:8080$p" &
  done
  for ui in /shop/ /console/ /csr/ /partner/ /biz/ /app/; do
    curl -s -o /dev/null -m 45 "http://localhost:8080$ui" &
  done
  wait
  echo "[fleet] routes warmed"
}

recover() {
  # identity boots last to be trusted: restart the machine-token holders
  # once Keycloak's realms are servable, or early calls 401
  docker restart bss-user-roles >/dev/null 2>&1; sleep 25
  docker restart bss-product-ordering bss-som >/dev/null 2>&1
  echo "[fleet] identity warmup done"
}

case "${1:-}" in
  up)
    echo "[fleet] tier 0: infrastructure"
    $DC up -d $INFRA >/dev/null 2>&1
    kc_ready || { echo "[fleet] keycloak never came up"; exit 1; }
    echo "[fleet] tiers 1-3: platform + experience"
    $DC up -d >/dev/null 2>&1
    sleep 20; $DC up -d >/dev/null 2>&1   # second pass catches stragglers
    recover
    echo "[fleet] settling 3 min, then warming"
    sleep 180
    warm
    echo "[fleet] UP. 'ops/fleet.sh demo' to shed optional weight."
    ;;
  demo)
    $DC stop $OPTIONAL >/dev/null 2>&1
    docker stop hermes-image-keeper >/dev/null 2>&1 || true
    echo "[fleet] demo slice: optional services stopped (~2-3GB freed)."
    echo "[fleet] every customer journey still works: shop, consoles, CSR,"
    echo "[fleet] B2B, wholesale, billing, care. 'full' brings the rest back."
    ;;
  full)
    $DC up -d >/dev/null 2>&1
    echo "[fleet] full fleet on (wants 24-32GB free for comfort)."
    ;;
  growth)
    # RAM is the lever, tenants are rows: swap weight the consumer demo never
    # touches (B2B quotes, care tickets, ledger, assurance, martech bridge) for
    # the marketing slice. Reversible with 'up'.
    docker stop bss-quote bss-trouble-ticket bss-party-interaction bss-knowledge bss-bridge bss-revenue bss-assurance >/dev/null 2>&1
    docker start bss-campaign bss-insight bss-event-hub bss-flow >/dev/null 2>&1
    for c in bss-campaign bss-insight; do
      for i in $(seq 1 40); do [ "$(docker inspect -f '{{.State.Health.Status}}' $c 2>/dev/null)" = healthy ] && break; sleep 5; done
    done
    echo "[fleet] growth shape: marketing slice on, B2B/care/ledger off. Journeys, audiences, landing pages, loyalty live."
    ;;

  refresh)
    echo "[fleet] relief valve: restarting the heaviest JVMs"
    KEEP="postgres|kafka|keycloak|gateway|prometheus|console|storefront|mobile-app|mock-|keeper|worker-controller|redis|minio|azurite"
    TARGETS=$(docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}' \
      | grep '^bss-' | grep -Ev "$KEEP" \
      | sort -t"$(printf '\t')" -k2 -hr | head -10 | cut -f1)
    for t in $TARGETS; do docker restart "$t" >/dev/null 2>&1; done
    sleep 90
    warm
    echo "[fleet] refreshed. Best run ~15 min before a live demo."
    ;;
  status)
    docker info >/dev/null 2>&1 || { echo "[fleet] docker is not running"; exit 1; }
    up_n=$(docker ps -q | wc -l | tr -d ' ')
    echo "containers up: $up_n"
    if command -v colima >/dev/null && colima status >/dev/null 2>&1; then
      colima ssh -- sh -c 'echo "vm load: $(cut -d" " -f1-3 /proc/loadavg)"; free -m | awk "NR==2{print \"vm mem avail: \"\$7\" MB\"}"' 2>/dev/null
    fi
    for probe in "idp http://localhost:8085/realms/bss/.well-known/openid-configuration" \
                 "api http://localhost:8080/tmf-api/productCatalogManagement/v4/productOffering?limit=1" \
                 "shop http://localhost:8080/shop/"; do
      name=${probe%% *}; url=${probe#* }
      echo "$name: $(curl -s -o /dev/null -w '%{http_code}' -m 8 "$url")"
    done
    ;;
  down)
    $DC stop >/dev/null 2>&1
    docker stop hermes-image-keeper >/dev/null 2>&1 || true
    echo "[fleet] all stopped. Data volumes persist; 'up' restores everything."
    ;;
  *)
    grep '^#' "$0" | sed -n '2,20p'; exit 1 ;;
esac

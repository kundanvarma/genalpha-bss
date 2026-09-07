#!/usr/bin/env bash
# Emit /etc/caddy/Caddyfile: automatic HTTPS (HTTP-01, one cert per host — no
# Route 53 needed), the gateway behind every tenant host, Keycloak behind id.
set -euo pipefail
D="${DEMO_DOMAIN:?}"; EMAIL="${LETSENCRYPT_EMAIL:?}"
HOSTS="shop.$D csr.$D console.$D biz.$D demo.$D"
[ "${DEMO_ENET:-0}" = "1" ] && HOSTS="$HOSTS shop-enet.$D csr-enet.$D console-enet.$D biz-enet.$D"
GATE=""
if [ -n "${DEMO_GATE_USER:-}" ] && [ -n "${DEMO_GATE_PASSWORD:-}" ]; then
  HASH=$(caddy hash-password --plaintext "$DEMO_GATE_PASSWORD")
  GATE="	basic_auth {
		$DEMO_GATE_USER $HASH
	}"
fi
echo "{
	email $EMAIL
}"
app_of() { case "$1" in shop*|demo*) echo shop;; csr*) echo csr;; console*) echo console;; biz*) echo biz;; esac; }
for h in $HOSTS; do
  echo "$h {"
  case "$h" in console.*|csr.*|biz.*|console-enet.*|csr-enet.*|biz-enet.*) [ -n "$GATE" ] && echo "$GATE";; esac
  echo "	redir / /$(app_of "$h")/ 302
	encode zstd gzip
	reverse_proxy localhost:8080
}"
done
echo "id.$D {
	reverse_proxy localhost:8085
}"

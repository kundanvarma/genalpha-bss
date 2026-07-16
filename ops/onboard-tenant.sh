#!/usr/bin/env bash
# THE THIRD OPERATOR IN AN AFTERNOON: stand up a NEW operator (an MVNO)
# on the running deployment — a realm, a tenant block, a restart, a seed.
# No image is rebuilt. Usage: ops/onboard-tenant.sh <id> "<Brand Name>" <locale> <currency> [#color]
set -euo pipefail
export PATH="/opt/homebrew/bin:$PATH"
ID="$1"; NAME="$2"; LOCALE="${3:-en}"; CURRENCY="${4:-EUR}"; COLOR="${5:-#B85C38}"
cd "$(dirname "$0")/.."
DOCKER=${DOCKER:-docker}

echo "== 1/4 realm: cloning nova's realm shape as '$ID' (clients, roles, service accounts)"
python3 - "$ID" "$NAME" <<'EOF'
import json, sys
tid, name = sys.argv[1], sys.argv[2]
raw = open('infra/keycloak/nova-realm.json').read()
d = json.loads(raw)
# keep the operator's staff login + every machine service account; drop personas
d['users'] = [u for u in d.get('users', []) if u['username'] == 'demo'
              or u['username'].startswith('service-account-')]
out = json.dumps(d)
out = out.replace('shop.nova.localhost', f'shop.{tid}.localhost') \
         .replace('csr.nova.localhost', f'csr.{tid}.localhost') \
         .replace('console.nova.localhost', f'console.{tid}.localhost') \
         .replace('biz.nova.localhost', f'biz.{tid}.localhost')
d = json.loads(out)
d['realm'] = tid
d['displayName'] = name
# nova's export carries ITS object UUIDs — a clone must mint its own
def strip_ids(node):
    if isinstance(node, dict):
        node.pop('id', None)
        node.pop('containerId', None)
        for v in node.values():
            strip_ids(v)
    elif isinstance(node, list):
        for v in node:
            strip_ids(v)
strip_ids(d)
json.dump(d, open(f'/tmp/{tid}-realm.json', 'w'))
print(f"   realm json ready: /tmp/{tid}-realm.json ({len(d['clients'])} clients)")
EOF
$DOCKER cp "/tmp/$ID-realm.json" bss-keycloak:/tmp/realm.json
$DOCKER exec bss-keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master --user admin --password admin >/dev/null
$DOCKER exec bss-keycloak /opt/keycloak/bin/kcadm.sh delete "realms/$ID" >/dev/null 2>&1 || true
$DOCKER exec bss-keycloak /opt/keycloak/bin/kcadm.sh create realms -f /tmp/realm.json
echo "   realm '$ID' created"

echo "== 2/4 registry: appending the tenant block to infra/tenants/tenants.yml"
python3 - "$ID" "$NAME" "$LOCALE" "$CURRENCY" "$COLOR" <<'EOF'
import re, sys
tid, name, locale, currency, color = sys.argv[1:6]
f = 'infra/tenants/tenants.yml'
s = open(f).read()
# idempotent rerun: strip a previous block for this id
s = re.sub(rf'      - id: {tid}\n(?:        .*\n)*', '', s)
m = re.search(r'(      - id: nova\n(?:        .*\n)*)', s)
block = m.group(1)
block = block.replace('- id: nova', f'- id: {tid}')
block = block.replace('realms/nova', f'realms/{tid}')
block = re.sub(r'\$\{(\w+)_NOVA:([^}]*)\}', rf'${{\1_{tid.upper()}:\2}}', block)
# backchannel endpoints must be container-reachable (keycloak:8080),
# exactly what compose env gives the built-in tenants
block = re.sub(r'jwks-uri: \$\{[^}]*\}',
               f'jwks-uri: http://keycloak:8080/realms/{tid}/protocol/openid-connect/certs', block)
block = re.sub(r'token-uri: \$\{[^}]*\}',
               f'token-uri: http://keycloak:8080/realms/{tid}/protocol/openid-connect/token', block)
block = re.sub(r'brand-name: .*', f'brand-name: {name}', block)
block = re.sub(r'brand-color: .*', f'brand-color: "{color}"', block)
block = re.sub(r'locale: .*', f'locale: "{locale}"', block)
block = re.sub(r'currency: .*', f'currency: {currency}', block)
block = re.sub(r'hosts: .*', f'hosts: [shop.{tid}.localhost, csr.{tid}.localhost, '
                             f'console.{tid}.localhost, biz.{tid}.localhost]', block)
# fresh operators start with quiet defaults on the outbound seams
block = block.replace(':ehf}', ':peppol}').replace(':einvoice}', ':einvoice}')
s += block
open(f, 'w').write(s)
print(f'   tenant block for {tid} appended ({currency}, {locale})')
EOF

echo "== 3/4 fleet: restarting onto the new registry (config only — nothing rebuilt)"
$DOCKER compose restart $($DOCKER compose config --services | grep -vE 'postgres|kafka|keycloak|mock-|console|storefront|mobile-app|csr-console|business-console|dealer-console') >/dev/null
until curl -sf -o /dev/null http://localhost:8080/actuator/health \
   && curl -sf -o /dev/null http://localhost:8081/actuator/health; do sleep 3; done
sleep 10

echo "== 4/4 seed: staff token, starter catalog"
TOK=""
for i in $(seq 1 30); do
  TOK=$(curl -s -X POST "http://localhost:8085/realms/$ID/protocol/openid-connect/token" \
    -d "grant_type=password&client_id=bss-demo&username=demo&password=demo" \
    | python3 -c "import json,sys;print(json.load(sys.stdin).get('access_token',''))" || true)
  [ -n "$TOK" ] && break
  sleep 3
done
[ -n "$TOK" ] || { echo "no staff token from realm $ID"; exit 1; }
CAT=http://localhost:8080/tmf-api/productCatalogManagement/v4
PRICE=$(curl -s -X POST "$CAT/productOfferingPrice" -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" -d "{\"name\":\"$NAME Mobile M monthly\",
   \"priceType\":\"recurring\",\"recurringChargePeriodType\":\"month\",
   \"recurringChargePeriodLength\":1,\"lifecycleStatus\":\"Active\",
   \"price\":{\"unit\":\"$CURRENCY\",\"value\":249.0}}" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
OFFER=$(curl -s -X POST "$CAT/productOffering" -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" -d "{\"name\":\"$NAME Mobile M\",
   \"lifecycleStatus\":\"Active\",\"isBundle\":false,
   \"productOfferingPrice\":[{\"id\":\"$PRICE\",\"name\":\"$NAME Mobile M monthly\"}]}" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
echo "   seeded offering: $OFFER"
echo ""
echo "OPERATOR '$NAME' ($ID) IS LIVE — realm, registry, catalog. Storefront host: shop.$ID.localhost"

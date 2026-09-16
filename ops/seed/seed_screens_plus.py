#!/usr/bin/env python3
"""Screens Plus — the reference configurable product every channel must render alike.

One offering that uses every TMF620 modelling lever the catalog serves:
  - enumerated choices with surcharges  (screens: 1-2 included, 3-4 +50, 5+ +100)
  - a numeric RANGE choice with a named algorithm (extraProfiles 0..10: +10 per profile above 2)
  - a per-unit price (seats: 20 per seat, the product is fungible so 3 seats = one product × 3)
  - a variant with its own stock (colour of the streaming box: Black in stock, Icy Blue sold out)
  - a price window (a launch pass line, charged only until the end of next month)
  - a 12-month term with a declining early-termination price (priceType penalty)
  - relationships: requires the operator's broadband (prompt), excludes the plain TV product
Idempotent by name: re-running updates nothing that already exists.
Usage: seed_screens_plus.py [tenant-realm] [gateway]   (default: bss realm on http://localhost:8080)
"""
import json, sys, urllib.request, urllib.parse
from datetime import datetime, timedelta, timezone

REALM = sys.argv[1] if len(sys.argv) > 1 else 'bss'
API = sys.argv[2] if len(sys.argv) > 2 else 'http://localhost:8080'
KC = f"http://localhost:8085/realms/{REALM}/protocol/openid-connect/token"
USER, PASS = ('demo', 'demo')
C = f'{API}/tmf-api/productCatalogManagement/v4'
S = f'{API}/tmf-api/productStockManagement/v4'
HOST = {'taranga': 'console.taranga.no', 'enet': 'console-enet.taranga.no'}.get(REALM)

tok = json.load(urllib.request.urlopen(urllib.request.Request(KC, data=urllib.parse.urlencode(dict(grant_type='password', client_id='bss-demo', username=USER, password=PASS)).encode()), timeout=20))['access_token']
def req(m, url, body=None):
    h = {'Authorization': 'Bearer ' + tok, 'Content-Type': 'application/json'}
    if HOST: h['Host'] = HOST
    r = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None, headers=h, method=m)
    try:
        resp = urllib.request.urlopen(r, timeout=40); t = resp.read().decode(); return resp.status, (json.loads(t) if t else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:300].decode()

def page(url):
    out = []
    for off in range(0, 2000, 100):
        st, p = req('GET', f'{url}?limit=100&offset={off}')
        if st != 200 or not p: break
        out += p
        if len(p) < 100: break
    return out

offerings = page(f'{C}/productOffering')
by_name = {o['name']: o for o in offerings}
if 'Screens Plus' in by_name:
    print('Screens Plus already seeded:', by_name['Screens Plus']['id']); sys.exit(0)

currency = 'NOK' if REALM in ('taranga', 'nova') else 'EUR'
broadband = next((o for o in offerings if 'fiber' in o['name'].lower() or 'fibre' in o['name'].lower() or 'broadband' in o['name'].lower()), None)
plain_tv = next((o for o in offerings if o['name'].lower().endswith(' tv') or o['name'].lower() == 'tv'), None)

# 1. the specification: choices, a range, a fungible fact, a variant characteristic
st, spec = req('POST', f'{C}/productSpecification', {
    'name': 'Screens Plus Spec', 'lifecycleStatus': 'Active',
    'productSpecCharacteristic': [
        {'name': 'screens', 'configurable': True, 'valueType': 'string',
         'productSpecCharacteristicValue': [{'value': '1-2', 'isDefault': True}, {'value': '3-4'}, {'value': '5+'}]},
        {'name': 'extraProfiles', 'configurable': True, 'valueType': 'number',
         'productSpecCharacteristicValue': [{'valueFrom': 0, 'valueTo': 10, 'rangeInterval': 'closed', 'unitOfMeasure': 'profiles'}]},
        {'name': 'boxColour', 'configurable': True, 'valueType': 'string',
         'productSpecCharacteristicValue': [{'value': 'Black', 'isDefault': True}, {'value': 'Icy Blue'}]},
        {'name': 'fungible', 'configurable': False, 'productSpecCharacteristicValue': [{'value': 'true'}]},
        {'name': 'resolution', 'configurable': False, 'productSpecCharacteristicValue': [{'value': '4K HDR'}]},
    ]})
assert st == 201, spec

def price(body):
    st, p = req('POST', f'{C}/productOfferingPrice', {**body, 'lifecycleStatus': 'Active'})
    assert st == 201, p
    return p
month_end = (datetime.now(timezone.utc).replace(day=1) + timedelta(days=62)).replace(day=1) - timedelta(seconds=1)
prices = [
    price({'name': 'Screens Plus per seat', 'priceType': 'recurring', 'recurringChargePeriodType': 'month',
           'price': {'unit': currency, 'value': 20}, 'unitOfMeasure': {'amount': 1, 'units': 'seat'}}),
    price({'name': 'Screens 3-4 surcharge', 'priceType': 'recurring', 'recurringChargePeriodType': 'month',
           'price': {'unit': currency, 'value': 50},
           'prodSpecCharValueUse': [{'name': 'screens', 'productSpecCharacteristicValue': [{'value': '3-4'}]}]}),
    price({'name': 'Screens 5+ surcharge', 'priceType': 'recurring', 'recurringChargePeriodType': 'month',
           'price': {'unit': currency, 'value': 100},
           'prodSpecCharValueUse': [{'name': 'screens', 'productSpecCharacteristicValue': [{'value': '5+'}]}]}),
    price({'name': 'Extra profiles', 'priceType': 'recurring', 'recurringChargePeriodType': 'month',
           'price': {'unit': currency, 'value': 10},
           'pricingLogicAlgorithm': [{'name': 'per profile above two', 'plaSpecId': 'perUnitAbove', 'characteristic': 'extraProfiles', 'threshold': 2, 'unitPrice': 10}]}),
    price({'name': 'Launch pass (this month only)', 'priceType': 'recurring', 'recurringChargePeriodType': 'month',
           'price': {'unit': currency, 'value': 5},
           'validFor': {'startDateTime': '2000-01-01T00:00:00Z', 'endDateTime': month_end.isoformat().replace('+00:00', 'Z')}}),
    price({'name': 'Streaming box', 'priceType': 'oneTime', 'price': {'unit': currency, 'value': 490}}),
    price({'name': 'Early termination (declining)', 'priceType': 'penalty',
           'price': {'unit': currency, 'value': 490}, 'unitOfMeasure': {'amount': 12, 'units': 'month'}}),
]
relationships = []
if broadband: relationships.append({'id': broadband['id'], 'name': broadband['name'], 'relationshipType': 'requires', 'role': 'prompt'})
if plain_tv: relationships.append({'id': plain_tv['id'], 'name': plain_tv['name'], 'relationshipType': 'excludes'})
st, off = req('POST', f'{C}/productOffering', {
    'name': 'Screens Plus', 'description': 'Streaming for every screen in the house: choose your screens, add profiles, pick the box colour, pay per seat.',
    'lifecycleStatus': 'Active', 'isBundle': False, 'category': [{'name': 'TV & Add-ons'}],
    'productSpecification': {'id': spec['id'], 'name': spec['name']},
    'productOfferingPrice': [{'id': p['id'], 'name': p['name']} for p in prices],
    'productOfferingTerm': [{'name': '12-month commitment', 'duration': {'amount': 12, 'units': 'month'}}],
    'productOfferingRelationship': relationships,
})
assert st == 201, off
# 2. stock per variant: Black in stock, Icy Blue sold out
for colour, qty in (('Black', 25), ('Icy Blue', 0)):
    st, s = req('POST', f'{S}/productStock', {
        'name': f'Screens Plus box {colour}', 'productOffering': {'id': off['id'], 'name': off['name']},
        'stockedProduct': {'productOffering': {'id': off['id']}, 'productCharacteristic': [{'name': 'boxColour', 'value': colour}]},
        'stockedQuantity': {'amount': qty, 'units': 'unit'}, 'productStockLevel': {'amount': qty}})
    assert st == 201, s
# 3. a usage allowance with a stepped overage table: 10 streaming hours included, then 2.00/h for the first 5, 1.00/h after
st, al = req('POST', f'{API}/tmf-api/usageManagement/v4/usageAllowance', {
    'productOffering': {'id': off['id'], 'name': off['name']}, 'usageType': 'Streaming hours', 'allowance': {'value': 10, 'units': 'h'},
    'overagePrice': {'unit': currency, 'value': 1.0}, 'overageTier': [{'valueFrom': 1, 'valueTo': 5, 'price': 2.0}, {'valueFrom': 6, 'valueTo': 20, 'price': 1.0}]})
assert st == 201, al
print('Screens Plus seeded:', off['id'], '| requires', broadband and broadband['name'], '| excludes', plain_tv and plain_tv['name'], '| tiered allowance', al['id'][:8])

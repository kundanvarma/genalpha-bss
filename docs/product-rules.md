# Product rules — how to use them

Business rules in genalpha-bss are **data, not code**. An operator authors a rule in the
back-office console (or over the API), and the very next order or price computation obeys it.
Disable or delete the rule and the behaviour is gone — **no deployment, ever**.

There are two families, served by the same engine (the `policy` component, port 8113,
`/tmf-api/policyManagement/v4`):

| Family | `domain` | `effect` | What it does | Where it acts |
|---|---|---|---|---|
| **Order rules** | `order` | `deny` | Blocks an order when the rule matches | Order creation (422 `POLICY_DENIED` with your message) |
| **Pricing rules** | `pricing` | `adjust` | Adds a discount/surcharge when the rule matches | Cart preview + the monthly bill (labelled line) |

Every rule is a **JSON-logic condition** — pure data, evaluated against the request context.
There is no code execution: the worst a malformed rule can do is evaluate to false.

---

## Quick start: the console

Open the back office (`/console`, dev login `demo`/`demo`) → **Rules** tab.
Pick **"What kind of rule"** and only that kind's fields appear:

### Block rules (order-time)

| Kind | You fill in | Effect |
|---|---|---|
| **Limit how many of an item can be ordered** | Item (optional — blank = any single item), Max quantity | An order asking for more is refused |
| **Two items cannot be bought together** | Item + Second item | An order containing both is refused |
| **Item requires a verified identity (BankID)** | Item | Refused unless the buyer's token carries a verified identity |
| **Advanced — raw JSON-logic** | The condition yourself | Anything the context can express |

Always set the **Message** — it is exactly what the customer sees when the order is refused.

### Pricing rules

| Kind | You fill in | Effect |
|---|---|---|
| **Discount / surcharge for verified customers** | Adjustment type + value | Applies when `verifiedIdentity` is true |
| **Discount / surcharge when the cart has an item** | Item + adjustment | Applies when that offering is in the cart/bill |
| **Discount / surcharge for everyone** | Adjustment | Always applies |
| **Advanced** | Raw condition + adjustment | Anything the context can express |

Adjustment type is **percent of subtotal** or **fixed amount**; the value is signed —
**negative = discount, positive = surcharge** (`-15` percent = 15% off). The **Message** becomes
the label the customer sees ("Launch offer (15% off)").

### Try before you trust: the dry run

The Rules editor has a **Dry run** row that hits the *live* engine — the same `/evaluate` and
`/price` endpoints the order pipeline and billing use:

- Fill a sample (offering id, qty, subtotal, verified?) → **Test order** shows
  `✓ ALLOWED` or `✕ DENIED by "<rule>": <message>`.
- **Test price** shows `base 100.00 → Launch offer (15% off): -15.00 → total 85.00`.

Nothing is ordered or billed by a dry run.

### Enable / disable

Every rule row has a one-click **Enable/Disable**. A disabled rule is completely inert — this is
the no-redeploy kill switch. Two example rules ship disabled so you can study their shape.

---

## What the customer experiences

- **Order rule denies** → checkout fails with **your message**, verbatim
  (HTTP 422, code `POLICY_DENIED`). Nothing is charged or reserved.
- **Pricing rule matches** → the **cart previews it before checkout**
  ("Launch offer (15% off) −16.62 EUR/mo → Your price per month 94.15 EUR") and the **monthly
  bill carries it** as a labelled `priceAdjustment` line. Cart and bill use the same engine, so
  they never disagree.

---

## Evaluation semantics

- **Order rules**: enabled rules run in **priority order (lower first)**; the **first matching
  deny wins** and its message is returned.
- **Pricing rules**: **all** matching enabled rules apply, in priority order; percent adjustments
  compound on the **running** subtotal (two 15%-off rules → `100 → 85 → 72.25`), and the total
  never goes below zero.
- **Tenancy**: rules are tenant-owned (Postgres RLS). GenAlpha's rules can never touch Nova's
  orders, and vice versa.
- **Fail-safe by design**: a malformed condition is logged and ignored (never blocks commerce);
  if the policy service is unreachable, ordering and billing **fail open** — the base behaviour
  stands. A rules outage can never halt the shop.

---

## The context: what a condition can see

Advanced rules are JSON-logic over these fields.

**Order context** (built by product-ordering at order time):

| Field | Type | Meaning |
|---|---|---|
| `offeringIds` | string[] | Every offering id in the order (bundle children included) |
| `quantityByOffering` | map | Offering id → total quantity |
| `maxLineQuantity` | number | Largest single-offering quantity |
| `totalQuantity` | number | Sum of all quantities |
| `lineCount` | number | Number of distinct items |
| `verifiedIdentity` | boolean | Buyer completed BankID/Vipps step-up |
| `party` | string | The buyer's party id |
| `items` | list | `{offeringId, name, quantity}` per item |

**Pricing context** (cart preview sends `subtotal` + `offeringIds`; billing sends
`subtotal` + `offeringIds` + `party`):

| Field | Type | Meaning |
|---|---|---|
| `subtotal` | number | The recurring base being priced |
| `offeringIds` | string[] | Offerings in the cart / on the bill |
| `party` | string | Customer party id (billing only) |
| `verifiedIdentity` | boolean | When the caller supplies it (dry run does) |

**Supported JSON-logic operators**: `var`, `missing`, `==`, `===`, `!=`, `!==`, `>`, `>=`, `<`,
`<=`, `!`, `!!`, `and`, `or`, `if`/`?:`, `in`, `cat`, `+`, `-`, `*`, `/`, `%`, `min`, `max`,
`some`, `all`, `none`. Unknown operators evaluate to false (inert), and a missing `var` never
satisfies a comparison — a missing quantity is never "over the cap".

### Example conditions

```jsonc
// max 2 of any single item
{ ">": [ { "var": "maxLineQuantity" }, 2 ] }

// offering A and offering B can't be bought together
{ "and": [ { "in": ["offer-a", { "var": "offeringIds" }] },
           { "in": ["offer-b", { "var": "offeringIds" }] } ] }

// regulated plan requires verified identity
{ "and": [ { "in": ["regulated-plan", { "var": "offeringIds" }] },
           { "!": { "var": "verifiedIdentity" } } ] }

// pricing: 10% off carts over €100 (advanced pricing kind, adjustment -10 percent)
{ ">": [ { "var": "subtotal" }, 100 ] }
```

---

## The API (everything the console does, scriptable)

All calls go through the gateway with a bearer token. Roles: `policy:read` / `policy:write`
(back office), `policy:evaluate` (the order pipeline's machine scope; also granted to the demo
operator for dry runs). `POST /price` needs any authenticated caller.

```bash
TOKEN=... # e.g. password grant as demo (dev)

# create an order rule
curl -X POST http://localhost:8080/tmf-api/policyManagement/v4/policyRule \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
    "name": "Max 2 per order", "domain": "order", "effect": "deny",
    "priority": 10, "enabled": true,
    "condition": "{\">\":[{\"var\":\"maxLineQuantity\"},2]}",
    "message": "You can order at most 2 of a single item."
  }'

# create a pricing rule
curl -X POST http://localhost:8080/tmf-api/policyManagement/v4/policyRule \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
    "name": "Launch 15% off", "domain": "pricing", "effect": "adjust",
    "priority": 10, "enabled": true,
    "condition": "{\"==\":[1,1]}",
    "adjustmentType": "percent", "adjustmentValue": -15,
    "message": "Launch offer (15% off)"
  }'

# dry-run an order decision
curl -X POST http://localhost:8080/tmf-api/policyManagement/v4/evaluate \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"domain":"order","context":{"maxLineQuantity":3}}'
# → {"decision":"deny","ruleId":"…","ruleName":"Max 2 per order","message":"You can order…"}

# price a subtotal
curl -X POST http://localhost:8080/tmf-api/policyManagement/v4/price \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"context":{"subtotal":100}}'
# → {"basePrice":100,"adjustments":[{"label":"Launch offer (15% off)","amount":-15,…}],"total":85.00}

# disable / delete
curl -X PATCH  …/policyRule/{id} -d '{"enabled":false}' …
curl -X DELETE …/policyRule/{id} …
```

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Rule doesn't fire | It's disabled; or the condition references a field the context doesn't carry (check the tables above); or another deny with lower priority won first |
| Everything is allowed during an outage | By design — ordering/billing fail open when policy is unreachable |
| Pricing looks doubled | Two enabled pricing rules both match; they stack by design — check the Rules list |
| Advanced condition rejected on save | It must be valid JSON (the engine validates at create/patch) |
| Customer saw a generic message | Set the rule's **Message** — it's returned verbatim on deny |

**Proofs**: `ops/e2e/policy_test.js` (rule born → order refused → disabled → order passes, no
redeploy), `pricing_test.js` (100→85→76.50, cart preview in a real browser), and the Rules
dry-run section of `console_test.js` all run against the live stack.

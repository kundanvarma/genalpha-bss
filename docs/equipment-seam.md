# The equipment seam — the router at the customer's end

*Most "my internet is dead" calls are the box, not the network. The BSS now sees the box.*

## What it is

A per-deployment seam from the service orchestration component to the operator's
auto-configuration server (ACS: TR-069 or USP), the system that already talks to
every router and ONT the operator ships. Through it the BSS knows, per broadband
line:

- whether the box is **online, offline or restarting**, and when it was last seen;
- **uptime**, **firmware** (and whether an update is pending), **Wi-Fi clients**;
- and can send the one action care asks for most: a **restart**.

Real targets behind the seam: Axiros, Friendly Technologies, Calix, Nokia
Altiplano. In the fleet the stand-in is `mock-acs` (:8162), deterministic per
line so demos repeat, with `PUT /cpe/{serviceId}` to pin a state for tests.

## Where it shows

| Face | What |
|---|---|
| **Line check** (`POST /service/{id}/diagnose`) | after the network checks and before the data checks: `routerOffline` as the *cause* ("the network side is fine — check power and cable, or restart it from here"), `routerRebooting`, `routerUnreachable` (caution), `routerOnline` (info: uptime, clients, pending update) |
| **Customer's Home and Services** | the fibre card says "● Router online · 12 days up · 4 devices on Wi-Fi" or "○ Router offline · last seen 17:42" with **Restart router**; a `routerOffline` finding in the line check carries the restart right there |
| **Care desk** | the service row's facts carry the router state; **Restart router** is a direct action on a broadband row in the Services area (under More… on the Overview); the diagnosis leads with the likely cause |
| **Ontology** | capabilities `cpe.status` (GET, `service:read`) and `cpe.restart` (house verb); governed action `restartRouter` (owner or `service:write`, precondition: the line is active); event `CpeRestartedEvent`; the care-assist, hermes-worker and external-mcp agents may execute it, shop-home may check it |

## Doors

- `GET /tmf-api/serviceInventory/v4/service/{id}/cpe` — the box as the ACS sees it; 404 for a stranger, 503 when the ACS does not answer, 404 when the deployment has no seam.
- `POST /tmf-api/serviceInventory/v4/service/{id}/cpe/restart` — the owner on their own line (self-care list in the SOM's security config) or care; 409 on a paused line; 202 with "back in about a minute"; publishes `CpeRestartedEvent` on `bss.som.events`.

Configuration: `CPE_BASE_URL` on the service orchestration component (blank = no
seam: no router facts, no restart, the line check simply skips the box).

## Honest limits

One ACS per deployment today, not per tenant; the OCS seam shows how to make
it per tenant when a second operator needs a different vendor. Wi-Fi channel
changes, firmware pushes and speed tests are not exposed yet, only read state and
restart.

## Proof

`ops/e2e/cpe_test.js` (#129): the seam answers for Paula's broadband line; pinned
offline, the line check names the router as the cause and says the network is
fine; a restart with her own token is accepted, the box comes back with fresh
uptime, the check reads online; a stranger gets 404 on both doors; the governed
action checks for the owner and executes for the agent with a receipt; the care
desk shows the router offline on the row and restarts it; her Home's fibre card
shows it and restarts it.

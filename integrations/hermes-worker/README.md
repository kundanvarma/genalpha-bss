# The Hermes worker package — a digital workforce from day 1

The optional extension package that puts an autonomous AI worker on an
operator's back-office and care queues. **[Hermes Agent](https://hermes-agent.nousresearch.com/)**
(Nous Research, open source) is the reference runtime we document end to
end — but the BSS side is deliberately runtime-neutral: the job (a task
queue), the badge (a revocable staff identity) and the audit (the shift
ledger + the Workforce dashboard) speak MCP + OIDC, so any MCP-speaking
agent hires into the same three interfaces.

Everything the worker can do is proven by **suite #65**
(`ops/e2e/agentic_workforce_test.js`): the queue derives live from real
backlogs, completion is verified (a worker cannot mark done what is not
done), refunds/cease/erasure only ever become **approval requests** a human
executes with their own token, the tenant's AI kill-switch stops the
workforce, and revoking the badge is the firing.

## Day 1, in four steps

```bash
# 1. Install Hermes (their official installer; no official Docker image
#    as of July 2026 — when Nous ships one, this becomes a compose profile)
curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
hermes setup            # pick your model provider — the worker brings its own LLM

# 2. Hire: mint the badge on the tenant's own IdP (TMF672). The badge IS
#    the opt-in; the credentials land in .env.worker, shown once.
./hire-worker.sh hermes

# 3. Connect: merge config.snippet.yaml into ~/.hermes/config.yaml
#    (fill the env from .env.worker; the tool whitelist keeps the worker's
#    hands exactly as wide as its job)

# 4. Put the job cards + the clock in place:
#    copy skills/* into your Hermes skills directory, then schedule:
#      "Work the care queue"  → every 15 minutes  (skills/care-triage)
#      "Work the AR queue"    → daily             (skills/cash-matching)
```

From that point the worker claims tasks, resolves tickets through TMF621
like any CSR, matches unapplied payments cent-exact, **files approvals for
anything bigger**, and reports its shifts — all visible on the console's
**Workforce** tab (KPIs, the approvals queue, the shift ledger).

## What the operator keeps in hand

| Lever | Where |
|---|---|
| Hire / fire | console → Staff → grant/revoke `digital-worker` (or `hire-worker.sh`) |
| Stop everything now | console → AI governance kill-switch (stops copilots AND workers) |
| Big actions | Workforce tab → approvals: the human's click IS the write, under the human's token |
| The scoreboard | Workforce tab: completed, escalated, deflection, handle time, **reopen rate**, minutes saved (labeled estimate), self-reported cost (labeled) |

## Honest notes

- The worker brings its **own LLM** — its model costs do not pass through
  the BSS AI control plane; the dashboard shows them as the worker's
  self-reported word, labeled as such.
- `hire-worker.sh` grants exactly the `digital-worker` bundle: workforce
  queue + tickets + interactions + AR reads + the apply-payment act.
  No payment:write, no catalog:write, no admin — big actions go through
  approvals by construction.
- The SKILL.md job cards are the open, portable skills format — they work
  in any runtime that reads it, and they encode the floor rules ("do not
  argue with a 409; finish the work", "a near-match is a NO").

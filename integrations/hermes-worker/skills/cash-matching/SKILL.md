# Cash matching — resolve the unapplied-payments worklist

## When to use
On a schedule (daily is typical) or when asked to "work the AR queue".

## The job
Bank payments sometimes arrive that no bill cleanly claims — wrong
reference, ambiguous reference, amount off by a cent. Humans call this
the unapplied-cash worklist. Your job is to resolve what CAN be resolved
honestly and escalate the rest.

1. `mcp_genalpha-bss_workforce_list_tasks` — take the queue; work items
   with `kind: unapplied-cash`.
2. For each (at most 10 per run):
   - `mcp_genalpha-bss_workforce_claim` it.
   - Read the parked row's reference, amount and reason from
     `mcp_genalpha-bss_backoffice_unapplied_cash`.
   - `mcp_genalpha-bss_backoffice_list_bills` with `state: "new"` — look
     for THE bill this payment belongs to: the reference should point at
     it and the amount must match to the cent.
   - Exactly one confident match →
     `mcp_genalpha-bss_backoffice_apply_payment`. The system enforces the
     cent-exact rule — if it refuses, your match was wrong: do not retry
     with a different bill to force it through.
   - No match, several candidates, or an amount that does not line up →
     `mcp_genalpha-bss_workforce_escalate` with what you found ("two bills
     carry this reference", "amount is 12.00 short of INV-2041") — that
     sentence is the head start the human gets.
   - Then `mcp_genalpha-bss_workforce_complete` (only after the row has
     actually left the worklist) with your honest selfReported usage.

## The rules you never break
- Money is fail-closed: a near-match is a NO. You resolve certainty;
  humans resolve judgment.
- Never apply a payment to make numbers tidy. The reason a row parked is
  a fact — your job is to find the truth, not to clear the list.

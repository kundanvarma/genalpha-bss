# Licensing

genalpha-bss is source-available under the
[Business Source License 1.1](LICENSE) (BUSL-1.1), converting to
[Apache License 2.0](LICENSES/Apache-2.0.txt) **two years after each
release** — irrevocably, written into the license itself.

## The deal, in plain words

**Free, forever:**

- **Read everything.** Full source, no signup.
- **Non-production use for everyone** — development, testing, evaluation,
  education, research — at any company size.
- **Production use for small operators**: if your organization (including
  affiliates and any tenants you host on it) serves **fewer than 20,000
  active subscribers**, or has **less than €3M in annual service revenue**,
  you may run genalpha-bss in production for your own business, free.
  A fibre startup, an altnet, a young MVNO: run your whole operation on it.
- **Professional services**: consultants and integrators may freely help
  licensed users implement and operate it.

**Commercial license required:**

- **Production use above the threshold** (20,000+ active subscribers and
  €3M+ service revenue). In practice this is bundled into one
  per-subscriber price together with support and SLA — contact us.
- **Offering genalpha-bss itself as a product or service** — reselling it,
  hosting it for others as a BSS offering, or embedding it in a competing
  product — at any size. Being the vendor of genalpha-bss is the one
  right reserved to its maker.

**The timer:** every version becomes plain Apache-2.0 two years after it
ships. Whatever happens to this project or its author, the code's future
as open source is guaranteed. There is no lock-in without an expiry date.

## Scope map

| Path | License | Why |
|------|---------|-----|
| `/` (services, apps, infra, tooling) | BUSL-1.1 | the product |
| `ops/e2e/` | Apache-2.0 | the contract/verification suites stay maximally open so anyone — users, forks, auditors — can prove behaviour |
| Versions released before this notice | Apache-2.0 | earlier releases were published under Apache-2.0; that grant is irrevocable and those versions remain Apache-2.0 forever |

Future commercial add-on modules, if and when they ship, will carry their
own license terms in their own directories and will be clearly marked.

## FAQ

**Is this open source?**
Not by the OSI definition — the production threshold and the competing-use
restriction disqualify it. It is source-available with a guaranteed
conversion to Apache-2.0 on a two-year timer per release. We prefer
stating that plainly over stretching the words "open source".

**How is the subscriber count measured?**
Active billed end-customer accounts (one account may hold many services),
trailing 90-day average, excluding test/demo data, aggregated across your
affiliates and any tenants hosted on your deployment. Your own admin
console shows the number. Compliance is by annual self-attestation — the
license never phones home.

**We're under the threshold today and will grow past it. What happens?**
Congratulations — that's the idea. Crossing the line means it's time to
talk to us about a commercial license, which in practice means the support
relationship an operator of that size wants anyway. Nothing shuts off;
this is a license obligation, not a kill switch. Thresholds only ever
move in the generous direction for already-released versions.

**Can I fork it?**
Yes, for any use the license permits — and every fork inherits the
two-year Apache conversion. You may not use a fork to offer a competing
BSS product or service before its conversion date.

**Why not plain Apache?**
Because the license is the only thing standing between a small team and a
large vendor reselling its work the day it becomes worth reselling. This
model keeps every freedom that matters to operators — read it, run it,
grow on it — and reserves exactly one right for the people doing the
work. In two years, each version is Apache anyway.

**I want to do something the license doesn't allow.**
Talk to us — commercial licenses exist for exactly that conversation.

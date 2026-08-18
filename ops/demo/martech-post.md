# LinkedIn post — martech video (post Wednesday, upload mp4 natively)

Video: `ops/demo/recordings/martech.mp4`

---

**Operators pay millions to reconstruct, in a marketing cloud, the data their BSS already has.**

A second licence, a second integration programme, a second place your customer data can leak — all to copy out what's already on your own event bus.

Does your BSS have a martech stack with a built-in CDP? Mine does. 4½ minutes, sound on 🔊 — a live telco BSS presenting its own marketing desk.

What you're watching:

👤 I log in as a **marketer** — so the console shows exactly one desk. Role-based access isn't a slide in this system; it's the first thing you see.

🏗️ The architecture, in one animation: orders, bills, loyalty, usage — every domain event lands on the Kafka bus, and the **CDP traits fill themselves**. No nightly export. No reverse ETL. No copy of your customers in someone else's cloud. The BSS *is* the CDP.

✍️ A campaign and a journey built **by hand** — trigger, promo code, message, save. About thirty seconds each, because the audience data is already there.

🎯 **Audiences** from your own operational traits, resolved live. **Activation** pushes them to Meta/Google — every email SHA-256-hashed before it leaves, do-not-contact filtered, async. **Attribution** reads holdout-vs-treated lift — and a program with no holdout shows "— (no holdout)" instead of inventing a number. The system refuses to flatter you.

👂 Both ears on the market: **social listening** scores brand sentiment; an angry DM in **social care** becomes a trouble ticket automatically — the reply handle rides along so an agent answers on the same channel.

🤖 And then the same job, **by asking**: a real model proposes a winback campaign — segment, message, and a 10% holdout it added on its own. It can only *propose*. A human clicks Create. Every AI action lands in an audit ledger.

All of it speaks TM Forum Open APIs end to end — a native module of the BSS, or a standalone martech add-on bridged onto one you already run.

Part of **genalpha-bss**, the AI-native, vendor-neutral TM Forum ODA BSS I've been building. More soon.

#telecom #BSS #martech #CDP #TMForum #AI #CX

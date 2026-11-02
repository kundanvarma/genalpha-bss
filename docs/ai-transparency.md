# AI transparency — telling people when it is AI, and why

The EU AI Act's transparency duties for systems that talk to people applied from
**2 August 2026** and were not postponed by the 2026 omnibus (the high-risk rules
were). Norway brings the Act in through the EEA, with its own law expected in
2027, but an operator whose customers are in the EU is already bound. This arc
(#422) makes the BSS meet those duties by default, rather than leaving them to
each operator's configuration.

## Customers are told they are talking to an AI

**In the shop's chat**, before the customer types anything, a standing line under
the header says *"You are chatting with an AI assistant, not a person. Ask for a
human at any time."* — in the tenant's language (bokmål and nynorsk included).
Every reply the bot writes carries a small **AI assistant** label; a person who
takes the chat over from the care desk is labelled **Agent**. The header reads
*AI assistant* until a person joins.

**The session keeps the record.** When a chat opens, the service stamps the time
the customer was told (`aiDisclosedAt` on the chat session) and returns the
disclosure with the new session, so the channel never has to invent its own
wording and an auditor can see, per conversation, that it was said.

This is the only place in the BSS where an AI converses with a customer today:
the app has no chat, WhatsApp carries templates only, and the social listening
service classifies posts rather than answering them. Any new conversational
channel must carry the same disclosure — the care chat suite fails if the widget
answers without it.

**Proof:** `ops/e2e/care_chat_test.js` checks that a new session carries the
disclosure and its time, that the widget shows it before the first message, and
that every bot reply is labelled.

## Honest limits

- The disclosure text is fixed in English on the server and translated by each
  channel; a tenant cannot yet word it in its own voice.
- The record says *when* the customer was told, not that they read it.
- Spoken channels do not exist yet; a voice agent must say it out loud first.

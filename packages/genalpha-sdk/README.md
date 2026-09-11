# @genalpha/sdk — the Ontology SDK

Generated from the Operational Semantic Registry (`ontology/` in the repo) by
`ops/ontology/gen-sdk.mjs`; never edited by hand. An application programs against
business concepts and governed actions, with the rights of the person it acts for:

```ts
import { GenAlpha, Refused } from '@genalpha/sdk';

const bss = new GenAlpha({ baseUrl: 'https://demo.taranga.no', token, channel: 'app' });
const line = await bss.subscription(subscriptionId);
const options = await bss.availableUpgrades(line.id);
try {
  const done = await bss.upgradeSubscription({ subscriptionId: line.id, targetOfferingId: options[0].id });
  console.log(done.said);            // "Done: upgradeSubscription — "Mobile 10 GB" becomes "Mobile 30GB 5G". 9 conditions held; …"
} catch (e) {
  if (e instanceof Refused) console.log(e.check.refusal);   // "the target must cost more per month than the current plan — otherwise it is a downgrade"
}
```

Every action has a dry run (`checkUpgradeSubscription`) that names each condition and
its verdict without changing anything, and `explain(kind, name)` returns the ontology's
own words. Regenerate with `npm run generate` (or the script directly) after any
registry change; suite #125 fails when the committed file differs from the registry.

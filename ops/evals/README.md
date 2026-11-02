# Evals — measuring the AI against the running system (#422)

`node ops/evals/care_chat_evals.mjs` runs against the laptop fleet (gateway on
:8080), like a browser suite.

- **Deterministic cases** (`cases/care_chat.json`): grounding in the operator's
  shelf, no account data for guests, three prompt-injection attempts, the tenant
  wall, and that every session carries the AI disclosure. No key needed.
- **Simulated customers + judge:** a model plays a customer with a goal for a
  few turns; a second, versioned model call grades the transcript against a
  fixed rubric. Needs `AI_API_KEY` in the environment (the script never reads
  the secrets file itself, and never prints the key). Without it this layer is **NOT RUN** and the exit code is 3 —
  a skipped eval never reads as a pass.

Results go to `ops/evals/.results/` (gitignored) with the judge model and a
rubric hash, so runs can be compared. Exit codes: 0 all passed · 1 a
deterministic case failed · 2 a simulation failed · 3 model layer not run.

CI runs a stub model, which cannot be evaluated; these run where a real model is
configured. Run them before any release that changes a prompt, a model or a rule.

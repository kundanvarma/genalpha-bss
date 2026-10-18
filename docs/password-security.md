# Passwords — every tenant's sign-in follows NIST SP 800-63B

*Arc #320, built 2026-10-18 (tickets #321, #324, #322, #325, #326, #327). The two
hosted-box tickets, #323 and #328, are not built yet — see Honest limits.*

## The problem it solves

Until this arc any password was accepted on every tenant's sign-in: one character,
`password`, your own email address. Nothing slowed down someone guessing, and on the
hosted box Keycloak is reachable directly, with nothing in front of it. Anyone could
open an account without proving the address was theirs, and no tenant could send a
"verify your email" or "reset your password" mail, because no mail server existed.
The "temporary" passwords the BSS handed out — to a family member, a migrated
customer, a new colleague — were stored as permanent and were too short for the
standard.

## What it is now

**The rules, for every tenant.** A password must be at least **15 characters**, and up
to 128 are allowed, in any characters — spaces and Norwegian letters included. It may
not be your username or your email address, and it may not be one of the most common
passwords (the UK NCSC's list of the 100,000 most breached, plus the shapes people make
of our brand names, such as `tarangapassword`). There are **no** rules about digits or
symbols and **no** forced changes after a period of time: the standard forbids both,
because they push people towards weaker passwords. A short phrase of ordinary words is
the easiest strong password there is, and the sign-in page says so.

**In words a person understands.** The stock Keycloak pages say, in English or
Norwegian:

- *Use at least 15 characters — a short phrase of ordinary words works well, no symbols needed.*
- *That's one of the most commonly used passwords — choose a phrase that's yours alone.*
- *Your password can't be your username or email address — choose something only you know.*

**A handed-out password is temporary.** When the BSS creates a login for someone — a
parent adding a child, a migration importing customers, a company inviting a colleague,
onboarding minting an operator's first staff login — the person must choose their own
password at their first sign-in. A password someone else has seen is theirs only once
they have replaced it. Digital workers are the exception: they sign in by machine and
cannot answer a browser prompt, so the moment a login is given the digital-worker role,
the requirement is lifted. The BSS-generated passwords themselves are long enough and
easy to read aloud: `k7wq-3mzp-x9tr-c2hv`.

**Guessing is slowed down, never locked for good.** After 5 wrong tries in a row, sign-in
pauses for a minute — even the right password waits. Every further wrong try pauses it
again, a minute longer for every five, up to **15 minutes**. A correct sign-in, or twelve
hours without a failure, resets the count. The page shows the same words for a wrong
password and a paused account — *"Wrong email or password. After several tries in a row,
sign-in pauses for a few minutes and then opens again by itself."* — so a guesser cannot
learn which addresses have accounts. Nobody needs to unlock anyone.

**Account mail.** On the laptop, the mail a person needs — verify your email, reset your
password — is caught by a local mail server (Mailpit, `http://localhost:8025`) instead of
leaving the machine.

**Where the rules live.** One folder, `infra/keycloak/security/`, is the source of every
tenant's rules: the policy, the lockout settings, the common-password list and the words.
Every fleet start applies them to every tenant's realm, and onboarding applies them to a
new tenant the moment it is created. Existing passwords keep working — a rule is checked
when a password is set, never against one already in place.

**Where it differs between the laptop and a real deployment.** The product defaults are
the safe ones. The laptop's demo fleet opts out of two of them, in `docker-compose.yml`,
because its suites sign in by machine to the logins they create: handed-out passwords are
not temporary there, and email verification is off. The hosted box keeps temporary
passwords on.

## Proof

- `ops/e2e/password_policy_test.js`, on Keycloak's own pages: every realm reports the rules,
  read back live; a freshly onboarded tenant has them from its first minute; 14 characters,
  the email address and common passwords are refused in plain words; a Norwegian phrase
  makes an account and signs in; a handed-over password is refused by machine and replaced
  at the first browser sign-in; a digital worker's badge is exempt; five wrong guesses pause
  even the right password and the pause lifts by itself; the verification and reset mails
  arrive in Mailpit and their links work; existing logins sign in as before.
- `ops/arch/password_policy_check.py`, in the claims gate: the policy keeps the standard's
  rules (15+, 64+ allowed, no composition or expiry rule), the lockout is temporary and
  capped at 15 minutes, a paused account reads exactly like a failed sign-in, the words
  exist for every rule in every language, the list is built into the Keycloak image, and
  the rules reach every realm. Each check was seen to fail when broken on purpose.
- user-roles unit tests: the generated-password shape, temporary by default, the
  digital-worker exemption, the laptop opt-out, a cloned realm carrying the rules.

## Honest limits

- **The hosted box is not done.** Its published demo logins (`demo/demo` and the personas)
  are still guessable until #323 rotates them, and its account mail — from
  `notifications@taranga.no` through the operator's Microsoft 365 — waits for #328. Until
  then the box can enforce the rules but cannot send a verification or reset mail, and its
  email verification stays off.
- **Staff have a single factor.** A second factor for staff — one-time codes or passkeys —
  is the next arc. Customers have no passkeys yet either.
- **The list is of common passwords, not every breached one.** A password that leaked once
  but is not common is not caught; a full breach corpus or a live breach lookup was left out
  on purpose (size, and no outbound call).
- **Lockout is a denial-of-service trade-off.** Anyone who knows an email address can pause
  that account — for 15 minutes at most, never longer. Keycloak is the only throttle; it is
  not behind the gateway.
- **The laptop runs with two protections off** (temporary passwords, email verification),
  deliberately, for its machine-driven suites.
- **A tenant onboarded between fleet starts gets its mail settings at the next start**; its
  password rules and lockout it gets immediately.
- **Two languages.** The plain words exist in English and Norwegian; a tenant in another
  language sees Keycloak's own wording for these messages.

# The common-password list (#325)

`ncsc-100k.txt` is the UK National Cyber Security Centre's list of the 100,000
passwords most often seen in breaches, derived from Have I Been Pwned. Copied
verbatim (99,840 lines) from SecLists
(`Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt`, MIT), the NCSC's
own URL having moved. sha256 `c2e5696882c603b7…` (first 16 hex) on 2026-10-18.

Contains public sector information licensed under the Open Government Licence v3.0.

`brand-words.txt` is ours: the tenants' brand names in the shapes people turn them
into passwords (`tarangapassword`, `welcometogenalpha`, …), 15 characters or longer,
because anything shorter is already refused by length.

Honest limit: this is a list of COMMON passwords, not a full breach corpus. A
password that has leaked once but is not common is not caught.

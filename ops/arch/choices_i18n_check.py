#!/usr/bin/env python3
"""Every Benefit Choices string in the shop has a Norwegian entry (#387).

No Norwegian-language tenant offers Choices yet, so nobody would see an English
string slip through on a Norwegian page until a customer did. This reads the
t('…') keys the Choices screens use and fails naming any key the bokmål bundle
(no.js + no-account.js) lacks — nynorsk builds over bokmål, so bokmål is the
floor. Prints the missing keys, one per line; prints nothing when complete.
"""
import re
import sys

SCREENS = ["apps/storefront/src/pages/Choices.jsx", "apps/storefront/src/pages/home/ChoicesLine.jsx"]
BOKMAL = ["apps/storefront/src/i18n/no.js", "apps/storefront/src/i18n/no-account.js"]

CALL = re.compile(r"""\bt\(\s*(['"])((?:\\.|(?!\1).)*)\1\s*\)""")
ENTRY = re.compile(r"""^\s*(['"])((?:\\.|(?!\1).)*)\1\s*:""", re.M)


def unescape(s):
    return re.sub(r"\\(.)", r"\1", s)


used = set()
for path in SCREENS:
    used |= {unescape(m.group(2)) for m in CALL.finditer(open(path, encoding="utf-8").read())}
known = set()
for path in BOKMAL:
    known |= {unescape(m.group(2)) for m in ENTRY.finditer(open(path, encoding="utf-8").read())}

if not used:
    sys.exit("choices_i18n_check: found no t('…') calls — the screens moved; update SCREENS")
for key in sorted(used - known):
    print(key)

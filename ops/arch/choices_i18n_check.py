#!/usr/bin/env python3
"""Every customer string on the Choices, credit, chat and AI-marking surfaces
reads in both Norwegian standards (#387, #422).

No Norwegian-language tenant offers these yet, so nobody would see an English
string slip through on a Norwegian page until a customer did. This reads the
t('…') keys those screens use — literal keys; t(NAME) or said(NAME, …) where NAME
is a string constant in the same file; and every value of a table NAME = {…}
looked up as t(NAME[…]) — and fails naming:

  * a key the bokmål bundle (no.js + no-account.js) lacks: the page would read
    English in both standards;
  * "nn: <key>" for a key that has a bokmål entry but no entry of its own in
    nn.js + nn-account.js. nn.js spreads bokmål underneath, so such a key does not read English
    on a nynorsk page — it reads BOKMÅL, which is what the #422 review saw.
    Every key on these screens is therefore written out in nn.js, even where the
    nynorsk word is the same as the bokmål one, so that the choice is made by a
    person and not by the spread.

Prints the missing keys, one per line; prints nothing when complete.
"""
import re
import sys

SCREENS = ["apps/storefront/src/pages/Choices.jsx", "apps/storefront/src/pages/home/ChoicesLine.jsx",
           # credit explanations (#422): a customer's rights must not read in English on a Norwegian page
           "apps/storefront/src/pages/orders/CreditExplanation.jsx", "apps/storefront/src/pages/cart/CreditNotice.jsx",
           # the care chat (#422): the AI disclosure and everything around it
           "apps/storefront/src/ChatWidget.jsx"]
# single keys on screens that are otherwise out of scope: the AI-content mark on the shop's rail (#422)
KEYS = ["AI-written"]
BOKMAL = ["apps/storefront/src/i18n/no.js", "apps/storefront/src/i18n/no-account.js"]
NYNORSK = ["apps/storefront/src/i18n/nn.js", "apps/storefront/src/i18n/nn-account.js"]

CALL = re.compile(r"""\bt\(\s*(['"])((?:\\.|(?!\1).)*)\1\s*\)""")
CALL_CONST = re.compile(r"""\b(?:t|said)\(\s*([A-Z][A-Z0-9_]*)\s*[,)]""")
CALL_TABLE = re.compile(r"""\bt\(\s*([A-Z][A-Z0-9_]*)\[""")
CONST = re.compile(r"""\bconst\s+([A-Z][A-Z0-9_]*)\s*=\s*(['"])((?:\\.|(?!\2).)*)\2\s*;""")
TABLE = re.compile(r"""\bconst\s+([A-Z][A-Z0-9_]*)\s*=\s*\{(.*?)\n\};""", re.S)
VALUE = re.compile(r""":\s*(['"])((?:\\.|(?!\1).)*)\1""")
ENTRY = re.compile(r"""^\s*(['"])((?:\\.|(?!\1).)*)\1\s*:""", re.M)


def unescape(s):
    return re.sub(r"\\(.)", r"\1", s)


def entries(path):
    return {unescape(m.group(2)) for m in ENTRY.finditer(open(path, encoding="utf-8").read())}


used = set(KEYS)
for path in SCREENS:
    src = open(path, encoding="utf-8").read()
    used |= {unescape(m.group(2)) for m in CALL.finditer(src)}
    consts = {m.group(1): unescape(m.group(3)) for m in CONST.finditer(src)}
    for m in CALL_CONST.finditer(src):
        if m.group(1) not in consts:
            sys.exit(f"choices_i18n_check: {path} translates {m.group(1)}, which is not a string constant here")
        used.add(consts[m.group(1)])
    tables = {m.group(1): m.group(2) for m in TABLE.finditer(src)}
    for name in {m.group(1) for m in CALL_TABLE.finditer(src)}:
        if name not in tables:
            sys.exit(f"choices_i18n_check: {path} looks up t({name}[…]), which is not a table here")
        used |= {unescape(v.group(2)) for v in VALUE.finditer(tables[name])}
known = set()
for path in BOKMAL:
    known |= entries(path)
nynorsk = set()
for path in NYNORSK:
    nynorsk |= entries(path)

if len(used) <= len(KEYS):
    sys.exit("choices_i18n_check: found no t('…') calls — the screens moved; update SCREENS")
for key in sorted(used - known):
    print(key)
for key in sorted((used & known) - nynorsk):
    print("nn: " + key)

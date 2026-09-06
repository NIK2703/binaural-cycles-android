#!/usr/bin/env python3
"""Translation parity checker for Android string resources.

The default locale (res/values/) is English and is used as the baseline.
Every other locale lives in res/values-<loc>/ (values-ru, values-zh-rCN, ...).
Reports, for every module / locale that has both the baseline and the target:

  A. Missing keys, type mismatches (string vs string-array vs plurals),
     item-count mismatches, format-placeholder mismatches (%1$s / %d / ...),
     XML tag mismatches (<b>, <font>, ...) and source-language leftovers
     (Cyrillic when checking a non-Russian locale).
  B. Keys whose baseline and translated text is byte-identical
     (may be legit: "OK", "%d%%", "Hz").
  C. Full baseline/target side-by-side dump for manual quality review.

Exit code is 0 when section A is clean for every checked locale, 1 otherwise
(usable in CI / pre-commit).

Usage:
    python tools/translation_parity_check.py                     # all modules, all locales
    python tools/translation_parity_check.py --locale zh-rCN     # check one locale
    python tools/translation_parity_check.py --no-dump           # issues only
    python tools/translation_parity_check.py app/src/main        # single module
"""

from __future__ import annotations

import argparse
import io
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (module path) -- every module that ships localised resource folders.
MODULES = [
    "app/src/main",
    "app/src/debug",
    "data/preferences/src/main",
]

# Locales to validate against the English default. Discovered automatically too.
KNOWN_LOCALES = ["ru", "zh-rCN", "zh-rTW", "es"]

CYRILLIC = re.compile(r"[\u0400-\u04FF]")
FORMAT_TOKEN = re.compile(r"%(\d+\$)?[sdflt]")
XML_TAG = re.compile(r"</?([a-zA-Z_][\w-]*)[^>]*/?>")


def parse(path):
    """Parse a strings.xml into {name: (kind, payload)} preserving file order."""
    with io.open(path, encoding="utf-8") as f:
        raw = f.read()
    # Strip the XML declaration and <resources> wrapper so we can wrap the
    # children ourselves (comments are dropped by ElementTree, which is fine).
    raw = re.sub(r"^\s*<\?xml[^>]*\?>", "", raw)
    raw = re.sub(r"<resources[^>]*>", "", raw).replace("</resources>", "")
    root = ET.fromstring("<root>" + raw + "</root>")

    out, order = {}, []
    for child in root:
        name = child.get("name")
        if not name:
            continue
        if child.tag == "string":
            out[name] = ("str", _inner(child, "string"))
        elif child.tag in ("string-array", "array"):
            out[name] = (child.tag, [_inner(it, "item") for it in child])
        elif child.tag == "plurals":
            out[name] = ("plurals", {it.get("quantity"): _inner(it, "item") for it in child})
        else:
            continue
        order.append(name)
    return out, order


def _inner(elem, tag):
    """Return the element's inner text, keeping nested markup intact."""
    text = ET.tostring(elem, encoding="unicode")
    text = re.sub(r"^<%s[^>]*>" % tag, "", text, flags=re.S)
    return re.sub(r"</%s>\s*$" % tag, "", text, flags=re.S)


def flatten(value):
    """Normalise any resource kind into a list of comparable text items."""
    kind, payload = value
    if kind == "str":
        return [payload]
    if kind == "plurals":
        return ["[%s] %s" % (q, t) for q, t in payload.items()]
    return ["[%d] %s" % (i, t) for i, t in enumerate(payload)]


def check(base, tgt, order, loc):
    """Return a list of (severity, message) tuples for section A.

    `base` is the English default (res/values/); `tgt` is the locale under
    test. Cyrillic leftovers are only flagged for non-Russian locales, because
    Russian is *supposed* to contain Cyrillic.
    """
    skip_cyrillic = (loc == "ru")
    issues = []
    for name in order:
        if name not in tgt:
            issues.append(("MISSING", "%s  (base: %s)" % (name, " | ".join(flatten(base[name])))))
            continue
        r, t = base[name], tgt[name]
        if r[0] != t[0]:
            issues.append(("TYPE", "%s: base=%s %s=%s" % (name, r[0], loc, t[0])))
            continue
        rf, tf = flatten(r), flatten(t)
        if len(rf) != len(tf):
            issues.append(("COUNT", "%s: base=%d item(s), %s=%d item(s)"
                           % (name, len(rf), loc, len(tf))))
        for i in range(min(len(rf), len(tf))):
            a, b = rf[i], tf[i]
            if FORMAT_TOKEN.findall(a) != FORMAT_TOKEN.findall(b):
                issues.append(("PLACEHOLDER", "%s[%d]: base=%s %s=%s"
                               % (name, i, sorted(set(FORMAT_TOKEN.findall(a))), loc,
                                  sorted(set(FORMAT_TOKEN.findall(b))))))
            if set(XML_TAG.findall(a)) != set(XML_TAG.findall(b)):
                issues.append(("TAG", "%s[%d]: base=%s %s=%s"
                               % (name, i, sorted(set(XML_TAG.findall(a))), loc,
                                  sorted(set(XML_TAG.findall(b))))))
            if not skip_cyrillic and CYRILLIC.search(b):
                issues.append(("LEFTOVER-CYRILLIC", "%s[%d]: %s" % (name, i, b)))
    # keys present in the target but not in the baseline (stale / renamed)
    for name in tgt:
        if name not in base:
            issues.append(("EXTRA", "%s  (only in %s)" % (name, loc)))
    return issues


def discover_locales(mod):
    """List values-* qualifiers available for a module."""
    res = os.path.join(ROOT, mod, "res")
    if not os.path.isdir(res):
        return []
    found = []
    for entry in sorted(os.listdir(res)):
        if entry.startswith("values-") and os.path.isfile(
                os.path.join(res, entry, "strings.xml")):
            found.append(entry[len("values-"):])
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("module", nargs="?", help="single module path, e.g. app/src/main")
    ap.add_argument("--locale", default=None,
                    help="target locale qualifier, e.g. zh-rCN / es / ru "
                         "(default: all known/discovered locales)")
    ap.add_argument("--no-dump", action="store_true", help="skip the side-by-side dump")
    args = ap.parse_args()

    modules = [args.module] if args.module else MODULES
    total_issues = 0

    for mod in modules:
        base_path = os.path.join(ROOT, mod, "res", "values", "strings.xml")
        if not os.path.isfile(base_path):
            print("== %s: skipped (no default values/strings.xml)" % mod)
            continue
        base, order = parse(base_path)

        locales = [args.locale] if args.locale else discover_locales(mod)
        # keep KNOWN_LOCALES first / stable order
        locales = [l for l in KNOWN_LOCALES if l in locales] + \
                  [l for l in locales if l not in KNOWN_LOCALES]

        for loc in locales:
            tgt_path = os.path.join(ROOT, mod, "res", "values-%s" % loc, "strings.xml")
            if not os.path.isfile(tgt_path):
                print("== %s: skipped (no values-%s)" % (mod, loc))
                continue

            tgt, _ = parse(tgt_path)
            print("=" * 70)
            print("== %s   (base: %d keys, %s: %d keys)"
                  % (mod, len(base), loc, len(tgt)))
            print("=" * 70)

            print("\n--- A. structural / placeholder / tag / leftover issues ---")
            issues = check(base, tgt, order, loc)
            for sev, msg in issues:
                print("  [%s] %s" % (sev, msg))
            if not issues:
                print("  (none)")
            total_issues += len(issues)

            print("\n--- B. identical base == %s (verify these are intentional) ---" % loc)
            same = 0
            for name in order:
                if name not in tgt:
                    continue
                rf, tf = flatten(base[name]), flatten(tgt[name])
                for i in range(min(len(rf), len(tf))):
                    if rf[i].strip() and rf[i].strip() == tf[i].strip():
                        print("  %s[%d] => %r" % (name, i, rf[i]))
                        same += 1
            if not same:
                print("  (none)")

            if not args.no_dump:
                print("\n--- C. base/%s side-by-side ---" % loc)
                for name in order:
                    print("### %s  (%s)" % (name, base[name][0]))
                    rf = flatten(base[name])
                    tf = flatten(tgt[name]) if name in tgt else []
                    for i in range(max(len(rf), len(tf))):
                        print("  base: %s" % (rf[i] if i < len(rf) else "<<none>>"))
                        print("  %s: %s" % (loc, tf[i] if i < len(tf) else "<<MISSING>>"))
            print()

    if total_issues:
        print("FAILED: %d issue(s)" % total_issues)
        return 1
    print("OK: all modules in sync")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Dump the Python CAT's POS locator catalogue as JSON, for the CAT-Java parity test.

Read-only. Imports ``petrotest.pos.locators`` and ``petrotest.pos.locator_types`` from the
Python CAT's ``src`` directory and prints one JSON document to stdout. Nothing is driven.

    python -I dump_locators.py <path to C:\\gitrepos\\CAT\\src>

The shape mirrors ``LocatorsParityTest`` on the Java side key for key, so the two can be
compared as JSON trees. Every value that the Java catalogue must reproduce is here: each
locator's fields, its selector (plain, and rendered with sample indices and labels), its
prefix selector, its index recovered from a rendered ID, the content families, the build
variants resolved at a set of versions, and the registries in their order.
"""

import json
import sys

# Sample arguments the Java side reproduces exactly: positional index slots take 7, 8, ...
# and every named slot takes "Lbl".
SAMPLE_INDEX = 7
SAMPLE_LABEL = "Lbl"

# Versions every build variant is resolved at. None is the rig with no build block.
VERSIONS = [
    None,
    "057.00",
    "057.01",
    "058.00",
    "100.00",
    "056.99",
    "056.00",
    "012.34",
    "57",
    "057.1",
    "9.0",
    "10.0",
    " 057.00 ",
    "",
    "release-candidate",
    "57.x.3",
]

def sample_args(loc):
    """The positional and named arguments a sample rendering uses."""
    indices = [SAMPLE_INDEX + i for i in range(sum(1 for s in loc.placeholders if s == "n"))]
    named = {s: SAMPLE_LABEL for s in loc.placeholders if s != "n"}
    return indices, named


def attempt(fn):
    try:
        return fn()
    except Exception:  # the Java side returns null for the same cases
        return None


def rendered_selector(loc):
    if not loc.is_template:
        return attempt(lambda: loc.selector)
    indices, named = sample_args(loc)
    return attempt(lambda: loc.at(*indices, **named).selector)


def rendered_index(loc):
    if not loc.is_template:
        return None
    indices, named = sample_args(loc)
    rendered = attempt(lambda: loc.at(*indices, **named))
    if rendered is None:
        return None
    return attempt(lambda: loc.index_of(rendered.value))


def dump_locator(loc):
    return {
        "value": loc.value,
        "kind": loc.kind,
        "name": loc.name,
        "group": loc.group,
        "refs": loc.refs,
        "documented": loc.documented,
        "deprecated": loc.deprecated,
        "replacement": loc.replacement,
        "frame": loc.frame,
        "alternates": list(loc.alternates),
        "note": loc.note,
        "placeholders": list(loc.placeholders),
        "selector": attempt(lambda: loc.selector),
        "rendered_selector": rendered_selector(loc),
        "prefix_selector": attempt(lambda: loc.prefix_selector),
        "rendered_index": rendered_index(loc),
    }


def dump_family(fam):
    def of(label, suffix=""):
        built = fam.of(label, suffix=suffix)
        return {"value": built.value, "name": built.name, "group": built.group,
                "alternates": list(built.alternates)}

    return {
        "prefix": fam.prefix,
        "name": fam.name,
        "group": fam.group,
        "members": list(fam.members),
        "of": {
            "Veg Pizza|Label": of("Veg Pizza", "Label"),
            "Reese's puff|Label": of("Reese's puff", "Label"),
            "Pizza&Burger": of("Pizza&Burger"),
            "Pizza&amp;Burger": of("Pizza&amp;Burger"),
        },
    }


def dump_variant(variant):
    resolved = {}
    for version in VERSIONS:
        key = "<none>" if version is None else version
        try:
            resolved[key] = variant.resolve(version).value
        except Exception:
            resolved[key] = None
    return {
        "name": variant.name,
        "since": variant.since,
        "default": dump_locator(variant.default),
        "legacy": dump_locator(variant.legacy),
        "resolve": resolved,
    }


def main():
    sys.path.insert(0, sys.argv[1])
    from petrotest.pos import locators as L
    from petrotest.pos import locator_types as T

    public = sorted(
        n for n, v in vars(L).items()
        if n.isupper() and n != "Final" and not isinstance(v, type) and not n.startswith("_")
    )
    locator_names = {}
    family_names = {}
    variant_names = {}
    for name in public:
        obj = getattr(L, name)
        if isinstance(obj, T.Locator):
            locator_names[name] = dump_locator(obj)
        elif isinstance(obj, T.ContentFamily):
            family_names[name] = dump_family(obj)
        elif isinstance(obj, T.BuildVariant):
            variant_names[name] = dump_variant(obj)

    registries = {
        "element_ids": [loc.name for loc in L.ELEMENT_IDS],
        "xpaths": [loc.name for loc in L.XPATHS],
        "all": [loc.name for loc in L.ALL],
        "indexed_xpath_families": [loc.name for loc in L.INDEXED_XPATH_FAMILIES],
        "frames": [[k, list(v)] for k, v in L.FRAMES.items()],
        "deprecated_id_prefixes": list(L.DEPRECATED_ID_PREFIXES),
        "deprecated_id_templates": [[k, v] for k, v in L.DEPRECATED_ID_TEMPLATES.items()],
        "groups": {k: [loc.name for loc in v] for k, v in L.GROUPS.items()},
        "by_value": {k: loc.name for k, loc in L.BY_VALUE.items()},
        "by_name": {k: loc.name for k, loc in L.BY_NAME.items()},
        "catalogue": dict(L.CATALOGUE),
    }

    document = {
        "public_names": public,
        "locators": locator_names,
        "content_families": family_names,
        "build_variants": variant_names,
        "registries": registries,
        "counts": {
            "locators": len(locator_names),
            "content_families": len(family_names),
            "build_variants": len(variant_names),
            "element_ids": len(L.ELEMENT_IDS),
            "xpaths": len(L.XPATHS),
            "all": len(L.ALL),
        },
    }
    sys.stdout.write(json.dumps(document, ensure_ascii=True, sort_keys=True))


if __name__ == "__main__":
    main()

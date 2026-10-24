import json, sys
from petrotest.authoring.registry import GROUPS, STEPS
import petrotest.authoring.steps  # fills STEPS
out = {"groups": list(GROUPS), "steps": []}
pats_done = False
for d in STEPS:
    if d.level == "pats":
        if not pats_done:
            out["steps"].append({"patsActions": True})
            pats_done = True
        continue
    out["steps"].append({
        "text": d.text, "group": d.group, "kind": d.kind, "level": d.level,
        "example": d.example, "doc": d.doc, "fixtures": list(d.fixtures), "params": list(d.params),
    })
sys.stdout.buffer.write((json.dumps(out, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))

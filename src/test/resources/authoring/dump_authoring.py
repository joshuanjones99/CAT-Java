# The Python CAT's own catalogue renderings, recorder collapse and recorded feature, for the Java parity test
# (AuthoringParityTest). Run under the Python CAT's interpreter with its checkout as the working directory:
#     python dump_authoring.py <recording.ndjson>
import json, sys
from datetime import date
from petrotest.authoring import catalogue
from petrotest.authoring.recorded import recording_feature
from petrotest.recorder.catalogue import catalogue_map
from petrotest.recorder.format import read_recording
from petrotest.recorder.generate import collapse
from cat.authoring.draft import assemble, claude_argv

recording = read_recording(__import__("pathlib").Path(sys.argv[1]))
steps = collapse(recording)
drafted = {"title": "Card types: donate", "covers": "AC  1", "assumptions": ["one", "two  words"],
           "steps": ["the register is idle", 'When the customer inserts card "<card>"', ""],
           "examples": {"header": ["card"], "rows": [["AMEX"], ["VISA"]]}}
out = {
    "text": catalogue.render_text(),
    "markdown": catalogue.render_markdown(),
    "json": catalogue.render_json(),
    "entries": catalogue_map().entries,
    "codes": [s.code for s in steps],
    "observed": [s.observed for s in steps],
    "ok": [s.ok for s in steps],
    "feature": recording_feature(recording, title="Cash sale", feature="Authored cases", key="PETROCORE-3"),
    "assembled": assemble(drafted, feature="F", spec_key="PETROCORE-5", key="PETROCORE-3"),
    "argv": claude_argv(["claude"], model="m", budget_usd=2.5),
    "today": date.today().isoformat(),
}
sys.stdout.buffer.write((json.dumps(out, ensure_ascii=False) + "\n").encode("utf-8"))

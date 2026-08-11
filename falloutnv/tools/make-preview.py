"""
Bundles web/ into one standalone HTML file with a frozen snapshot baked in.

The result needs no server and no game: it inlines the real style.css and app.js, captures one
/state payload from mockserver.py (or generates one directly), and stubs fetch() so the page runs
against it. Useful for showing someone what the screen looks like without asking them to run
anything.

    py tools/mockserver.py          # in one terminal, optional
    py tools/make-preview.py        # writes preview.html

It is a preview, not a build artifact: polling returns the same frozen frame every time, so the
clock and the walking player do not move. Everything else -- tabs, selection, the map, the settings
panel -- behaves normally.
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, "..", "web")
OUT = os.path.join(HERE, "..", "preview.html")


def snapshot():
    """Prefer a live mockserver, fall back to importing it directly."""
    try:
        import urllib.request
        with urllib.request.urlopen("http://localhost:27304/state", timeout=2) as r:
            print("Captured a frame from the running mock server.")
            return json.load(r)
    except Exception:
        pass

    sys.path.insert(0, HERE)
    import mockserver
    print("Mock server not running; generating a frame directly.")
    return mockserver.snapshot()


def read(name):
    with open(os.path.join(WEB, name), encoding="utf-8") as fh:
        return fh.read()


def build():
    html = read("index.html")
    css = read("style.css")
    js = read("app.js")
    state = snapshot()

    # Drop the external references; everything is going inline.
    html = html.replace('<link rel="stylesheet" href="style.css">', "")
    html = html.replace('<script src="app.js"></script>', "")

    shim = """
// Preview shim: no server, so /state answers from a frozen snapshot and /action is a no-op.
// Settings still persist to localStorage, so the panel works as it really does.
const FROZEN = %s;
window.fetch = function (url, options) {
  const path = String(url);
  const body = (options && options.method === "POST") ? { ok: true } : FROZEN;
  return Promise.resolve({
    ok: true,
    status: 200,
    json: () => Promise.resolve(path.indexOf("action") >= 0 ? { ok: true } : body),
  });
};
""" % json.dumps(state)

    bundle = (
        "<!doctype html>\n<html><head><meta charset='utf-8'>\n"
        "<style>\n" + css + "\n</style>\n</head><body>\n"
        + html
        + "\n<script>\n" + shim + "\n" + js + "\n</script>\n</body></html>\n"
    )

    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write(bundle)

    print(f"Wrote {os.path.normpath(OUT)} ({len(bundle) // 1024} KB). Open it in any browser.")


if __name__ == "__main__":
    build()

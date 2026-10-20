import html
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1])
SRC = ROOT / "src/main/java"
OUT = ROOT / "docs/index.html"
TEMPLATE = Path(sys.argv[2]).read_text(encoding="utf-8")
FRAGS = Path(sys.argv[3])

KINDS = {
    "EventSourcedEntity": ("Event Sourced Entity", "#F5C518", 1),
    "KeyValueEntity": ("Key Value Entity", "#28C840", 2),
    "View": ("View", "#A855F7", 3),
    "Workflow": ("Workflow", "#7EC8E3", 4),
    "Agent": ("Agent", "#FF6B6B", 5),
    "Consumer": ("Consumer", "#8a8a8a", 6),
    "TimedAction": ("Timed Action", "#8a8a8a", 7),
}

files = {p: p.read_text(encoding="utf-8").replace("\r", "") for p in SRC.rglob("*.java")}


def esc(s):
    return html.escape(s, quote=False)


def javadoc_before(text, pos):
    """The Javadoc comment that ends just before pos (annotations may sit between), as plain text."""
    head = text[:pos]
    m = re.search(r"/\*\*((?:(?!\*/).)*?)\*/\s*(?:@[^\n]*\n\s*)*$", head, re.S)
    if not m:
        return ""
    body = re.sub(r"^\s*\*\s?", "", m.group(1), flags=re.M)
    first = re.split(r"\n\s*\n|<p>", body.strip())[0]
    first = re.sub(r"\{@(?:code|link|linkplain)\s+([^}]*)\}", lambda k: k.group(1).split("#")[-1].strip(), first)
    first = re.sub(r"<[^>]+>", "", first)
    first = re.sub(r"@param.*", "", first, flags=re.S)
    return re.sub(r"\s+", " ", first).strip()


def highlight(line):
    s = esc(line)
    if s.strip().startswith("//") or s.strip().startswith("*") or s.strip().startswith("/*"):
        return f'<span class="cm">{s}</span>'
    s = re.sub(r'(&quot;[^&]*?&quot;|"[^"]*")', r'<span class="st">\1</span>', s)
    s = re.sub(r"(@[A-Z]\w+(?:\.\w+)*)", r'<span class="an">\1</span>', s)
    s = re.sub(r"\b(public|class|extends|implements|record|interface|static|final|private|return|new|void)\b", r'<span class="kw">\1</span>', s)
    return s


def sentence(s, limit=120):
    s = s.strip()
    m = re.match(r"(.+?[.!?])(\s|$)", s)
    out = m.group(1) if m else s
    if len(out) > limit:
        out = out[: limit - 1].rsplit(" ", 1)[0] + "..."
    return out


def balanced(text, start):
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1 : i]
    return ""


def split_top(s):
    parts, depth, cur = [], 0, ""
    for ch in s:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    return parts


def record_fields(name, owner_text=None):
    qual, _, name = name.rpartition(".")
    ordered = [t for p, t in files.items() if qual and p.stem == qual] + ([owner_text] if owner_text else []) + list(files.values())
    for text in ordered:
        m = re.search(r"\brecord\s+" + re.escape(name) + r"\s*(?:<[^>]*>)?\s*\(", text)
        if m:
            inner = balanced(text, m.end() - 1)
            out = []
            for p in split_top(inner):
                p = re.sub(r"@\w+(\([^)]*\))?\s*", "", p).strip()
                if not p:
                    continue
                typ, _, fld = p.rpartition(" ")
                out.append((fld, re.sub(r"\s+", " ", typ)))
            return out
    return None


def event_names(owner_text, event_type):
    names = []
    texts = [owner_text] + [t for p, t in files.items() if p.name == event_type + ".java"]
    for text in texts:
        for m in re.finditer(r"\brecord\s+(\w+)\s*(?:<[^>]*>)?\s*\(", text):
            tail = text[m.end() - 1 :]
            after = balanced(text, m.end() - 1)
            rest = text[m.end() - 1 + len(after) + 2 : m.end() + len(after) + 120].split("{")[0]
            if re.search(r"implements\s+[\w.,\s]*\b" + re.escape(event_type) + r"\b", rest) and m.group(1) not in names:
                names.append(m.group(1))
        for m in re.finditer(r"\b(?:final\s+)?class\s+(\w+)\s+implements\s+" + re.escape(event_type) + r"\b", text):
            if m.group(1) not in names:
                names.append(m.group(1))
    return names


components = []
for path, text in files.items():
    m = re.search(r'@Component\(id\s*=\s*"([^"]+)"\)', text)
    if not m:
        continue
    cm = re.search(r"public\s+(?:abstract\s+)?class\s+(\w+)\s+extends\s+(\w+)\s*(<[^{]*?>)?\s*\{", text)
    if not cm or cm.group(2) not in KINDS:
        continue
    gen = cm.group(3) or ""
    gens = [g.strip() for g in split_top(gen[1:-1])] if gen else []
    components.append({
        "id": m.group(1), "name": cm.group(1), "kind": cm.group(2), "path": path, "text": text,
        "pos": text.rfind("public class", 0, cm.end()) if "public class" in text else cm.start(), "gens": gens,
        "cm": cm,
    })
components.sort(key=lambda c: (KINDS[c["kind"]][2], c["name"]))

endpoints = []
for path, text in files.items():
    m = re.search(r'@HttpEndpoint\("([^"]*)"\)', text)
    if not m:
        continue
    cm = re.search(r"public\s+class\s+(\w+)", text)
    routes = []
    for r in re.finditer(r'((?:/\*\*(?:(?!\*/).)*?\*/\s*)?)@(Get|Post|Put|Delete|Patch)\("([^"]*)"\)', text, re.S):
        doc = ""
        if r.group(1):
            doc = re.sub(r"\s+", " ", re.sub(r"^\s*\*\s?|/\*\*|\*/", "", r.group(1), flags=re.M)).strip()
            doc = re.sub(r"\{@(?:code|link)\s+([^}]*)\}", r"\1", doc)
            doc = sentence(re.sub(r"<[^>]+>", "", doc), 140)
        routes.append((r.group(2).upper(), m.group(1) + r.group(3), doc))
    endpoints.append({"name": cm.group(1), "prefix": m.group(1), "path": path, "text": text, "routes": routes,
                      "pos": cm.start()})
endpoints.sort(key=lambda e: e["name"])


def comp_excerpt(c):
    text = c["text"]
    lines = []
    cm = c["cm"]
    head_start = text.rfind("\n", 0, text.rfind("@Component", 0, cm.start()) if "@Component" in text[: cm.start()] else cm.start()) + 1
    for ln in text[head_start : cm.end()].split("\n"):
        ln = ln.rstrip()
        if ln.strip() and not ln.strip().startswith(("/**", "*", "*/")):
            lines.append(ln.rstrip(" {") + (" {" if ln.rstrip().endswith("{") else ""))
    body = text[cm.end():]
    sigs = []
    for m in re.finditer(r"^  (public\s+[^\n=;{]*\([^)]*\)[^\n{;]*)\{?", body, re.M):
        sig = m.group(1).strip()
        if sig.startswith("public " + c["name"] + "("):
            continue
        sigs.append("  " + sig + " { ... }")
    out = lines + ([""] if sigs else []) + sigs[:8]
    if len(sigs) > 8:
        out.append("  // " + str(len(sigs) - 8) + " more")
    out.append("}")
    return "\n".join(out)


def component_section(c):
    label, color, _ = KINDS[c["kind"]]
    doc = javadoc_before(c["text"], c["cm"].start())
    if not doc:
        doc = "No description in the source comment."
    return f"""<details class="sec">
  <summary><span class="chev">&#9654;</span><span class="sec-dot" style="background:{color}"></span><span class="sec-title">{c['name']}</span><span class="sec-type">{label}</span><span class="sec-id mono">{c['id']}</span><span class="sec-hint">{esc(sentence(doc, 90))}</span></summary>
  <div class="sec-body">
    <p class="comp-desc">{esc(doc)}</p>
    <div class="code"><div class="code-bar mono">{c['path'].name}</div>
    <div class="code-body mono">{chr(10).join(highlight(l) for l in comp_excerpt(c).split(chr(10)))}</div></div>
  </div>
</details>"""


def endpoint_section(e):
    doc = javadoc_before(e["text"], e["pos"]) or "HTTP routes."
    lines = [f"{m:<7}{p}" for m, p, _ in e["routes"]]
    notes = "".join(f"<li><span class=\"mono\">{m} {esc(p)}</span> {esc(d)}</li>" for m, p, d in e["routes"] if d)
    return f"""<details class="sec">
  <summary><span class="chev">&#9654;</span><span class="sec-dot" style="background:#fff"></span><span class="sec-title">{e['name']}</span><span class="sec-type">HTTP Endpoint</span><span class="sec-id mono">{esc(e['prefix'])}</span><span class="sec-hint">{len(e['routes'])} route{'s' if len(e['routes']) != 1 else ''}</span></summary>
  <div class="sec-body">
    <p class="comp-desc">{esc(doc)}</p>
    <div class="code"><div class="code-bar mono">{e['path'].name}</div>
    <div class="code-body mono">{chr(10).join(esc(l) for l in lines)}</div></div>
  </div>
</details>"""


def entity_box(c):
    st = c["gens"][0] if c["gens"] else "?"
    st_base = re.sub(r"<.*", "", st)
    fields = record_fields(st_base, c["text"]) or []
    rows = "".join(f'\n      <div class="ent-row"><span class="fname">{esc(f)}</span><span class="ftype">{esc(t)}</span></div>' for f, t in fields)
    if not fields:
        rows = f'\n      <div class="ent-row"><span class="fname">state</span><span class="ftype">{esc(st)}</span></div>'
    events = ""
    if c["kind"] == "EventSourcedEntity" and len(c["gens"]) > 1:
        names = event_names(c["text"], re.sub(r".*\.", "", c["gens"][1]))
        if names:
            events = '\n    <div class="ent-events"><div class="lbl">Events</div>' + "".join(
                f'\n      <span class="ent-ev">{n}</span>' for n in names) + "\n    </div>"
        c["events"] = names
    return f"""  <div class="ent-box">
    <div class="ent-title">{c['name']} <span style="font-weight:400;font-size:10px;color:#888">&middot; {esc(st_base)}</span></div>
    <div class="ent-rows">{rows}
    </div>{events}
  </div>"""


entities = [c for c in components if c["kind"] in ("EventSourcedEntity", "KeyValueEntity")]
boxes = "\n".join(entity_box(c) for c in entities)
n_events = sum(len(c.get("events", [])) for c in entities)
n_views = sum(1 for c in components if c["kind"] == "View")
n_domain = len([p for p in files if p.parent.name in ("domain", "governance")])

reference = []
reference += [endpoint_section(e) for e in endpoints]
reference += [component_section(c) for c in components]

frag = lambda n: (FRAGS / n).read_text(encoding="utf-8")

slug = "contextlayer"
values = {
    "TITLE": "Context Layer",
    "LEAD": "A governed context layer on the Akka SDK: it answers questions across source systems, says how current each answer is, and keeps people in charge of identity, access and actions.",
    "STAT_COMPONENTS": str(len(components) + len(endpoints)),
    "STAT_EVENTS": str(n_events),
    "STAT_ENDPOINTS": str(len(endpoints)),
    "STAT_VIEWS": str(n_views),
    "STAT_DOMAIN_OBJECTS": str(n_domain),
    "ENTITY_HINT": "State and events of the " + str(len(entities)) + " entities; the records they hold are listed by field",
    "INTERACTION_HINT": "A pushed change to a record, from the source system to the watchers; a webhook takes the same path through StreamEndpoint",
    "COMPONENT_GRAPH_HINT": "Overview of the core path: connectors, evidence, claims and the change feed. The evidence chain is drawn in detail below",
    "SLUG": slug,
    "INTERACTION_TITLE": "A pushed change reaches the store and the watchers",
    "INTERACTION_DESC": "A claims system publishes a record change on its stream; the consumer writes it to the entity store, records the refresh status, and publishes a notice to the change feed.",
    "COMPONENT_GRAPH_TITLE": "Core components and how they connect",
    "COMPONENT_GRAPH_DESC": "Three endpoints reach four entities and two views: connector packs feed their view, evidence feeds its view, and the stream endpoint reads the change feed.",
    "FOOTER_TARGET": "Akka SDK",
}

page = TEMPLATE.replace("</style>", ".cg .n-con rect.box{fill:#141414;stroke:#8a8a8a}.cg .n-con .nt{fill:#8a8a8a}\n</style>", 1)
for region in ("ENTITY_BOXES", "INTERACTION_SVG_BODY", "COMPONENT_GRAPH_BODY", "ADDITIONAL_DIAGRAMS", "COMPONENT_REFERENCE"):
    first = page.find("{{" + region + "}} —")
    live = page.rfind("{{" + region + "}}")
    start = page.rfind("<!--", 0, first)
    assert 0 < start < first < live, region
    page = page[:start] + page[live:]
for k, v in values.items():
    page = page.replace("{{" + k + "}}", esc(v) if k not in ("SLUG",) else v)
# Region placeholders appear once inside a comment and once live; replace only the live one.
def fill(region, body):
    global page
    marker = "{{" + region + "}}"
    idx = page.rfind(marker)
    page = page[:idx] + body + page[idx + len(marker):]

fill("ENTITY_BOXES", boxes)
fill("INTERACTION_SVG_BODY", frag("interaction.svg"))
fill("COMPONENT_GRAPH_BODY", frag("graph.svg"))
fill("ADDITIONAL_DIAGRAMS", frag("additional.html"))
fill("COMPONENT_REFERENCE", "\n".join(reference))

OUT.parent.mkdir(exist_ok=True)
OUT.write_text(page, encoding="utf-8", newline="\n")
print("components", len(components), "endpoints", len(endpoints), "events", n_events, "views", n_views, "domain", n_domain, "entities", len(entities))
for c in entities:
    print(c["name"], len(record_fields(re.sub(r"<.*", "", c["gens"][0]), c["text"]) or []), c.get("events"))

"""The attachment read path end to end, through the BFF, in the SPA's own request shapes.

Probe 35 established what ALM does. This asks the other half: does Alt-ALM's own endpoint turn that
into a response a browser SAVES rather than one it RENDERS - which is the entire security property
of the "everything downloads" decision (user, 2026-08-20).

Read-only. Nothing is created, nothing is deleted. The subject is the requirement probe 35 left
behind, which is kept rather than swept under the current protocol.

Needs the BFF running against the sandbox. From `bff/`:

    ./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.config.additional-location=file:../Secrets/local.properties

then, from the repo root:

    python scripts/probe/probe-attachment-serve.py

WARNING: stop it again before any `-Pcontract` run. Both share one API key, and
`authentication-point/logout` ends the AUTHENTICATION rather than one session (probe 13), so a test
class closing its pool invalidates the running app's sessions.
"""
import json
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
failures = []


def call(path, accept="application/json"):
    req = urllib.request.Request(BASE + path, headers={"Accept": accept})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def check(label, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{(' - ' + detail) if detail else ''}")
    if not ok:
        failures.append(label)


# ---- find a record that actually has an attachment ------------------------------------------
print("looking for a requirement with an attachment")
st, _, raw = call("/api/grid/requirements?pageSize=50&start=1")
rows = json.loads(raw).get("rows", []) if st == 200 else []
print(f"  {len(rows)} requirements in the sandbox")

subject = None
for row in rows:
    st, _, raw = call(f"/api/attachments/requirements/{row['id']}")
    if st != 200:
        continue
    items = json.loads(raw).get("items", [])
    if items:
        subject = (row["id"], items)
        break

if subject is None:
    print("\nNo attachment anywhere in the sandbox - probe 35's row may have been swept.")
    print("Nothing to verify; this is not a pass.")
    raise SystemExit(1)

rid, items = subject
print(f"  requirement {rid} has {len(items)} attachment(s)")
for a in items:
    # The NAME is not printed: it carries a probe timestamp, which is ours, but the habit of
    # printing attachment names from a live tenant is the one worth not forming.
    print(f"    id={a['id']} size={a['size']}")

att = items[0]["id"]

# ---- the list ---------------------------------------------------------------------------------
print("\n1. the list endpoint")
check("returns items with ids", bool(items[0]["id"]))
check("reports a name", bool(items[0]["name"]))

# ---- the download -----------------------------------------------------------------------------
print("\n2. the download route")
st, hdrs, body = call(f"/api/attachments/requirements/{rid}/{att}/file")
ctype = hdrs.get("Content-Type", "")
disp = hdrs.get("Content-Disposition", "")
sniff = hdrs.get("X-Content-Type-Options", "")
print(f"   HTTP {st}, {len(body)} bytes")
print(f"   Content-Type: {ctype!r}")
print(f"   Content-Disposition: {disp!r}")
print(f"   X-Content-Type-Options: {sniff!r}")

check("200", st == 200, f"HTTP {st}")
check("bytes came back", len(body) > 0, f"{len(body)} bytes")
# The three that ARE the security property.
check("served as octet-stream, not as ALM's own type", ctype.startswith("application/octet-stream"),
      repr(ctype))
check("disposition is attachment", disp.startswith("attachment"), repr(disp))
check("nosniff is set", sniff == "nosniff", repr(sniff))

# The download must land under the file's REAL name, extension included.
#
# ALM sends no Content-Disposition with the bytes at all - verified here, not assumed - so the
# name has to come from the attachment list instead. Without that lookup this said
# `attachment-8`, which on Windows is a file with no application associated with it. A regression
# would be invisible in a unit test, because the fallback name is a perfectly valid string.
expected_name = items[0]["name"]
check("named with the real filename, not a synthesised one",
      expected_name in disp, f"expected {expected_name!r} in the header")
check("the name kept its extension", "." in expected_name.rsplit("/", 1)[-1])

# ⚠️ The check that catches probe 35's actual trap. A member read with the wrong Accept returns
# ENTITY METADATA with HTTP 200 - a JSON document that is not the file. If our client ever loses
# its octet-stream Accept, this is what would come back, and only the bytes reveal it.
looks_like_json = body[:1] in (b"{", b"[")
check("the body is the FILE, not an entity envelope", not looks_like_json,
      "starts with a JSON brace" if looks_like_json else "")
if body[:8] == bytes.fromhex("89504e470d0a1a0a"):
    print("   (it is a PNG, by signature)")

# ---- the image route --------------------------------------------------------------------------
print("\n3. the image route")
st2, h2, body2 = call(f"/api/attachments/requirements/{rid}/{att}/image")
print(f"   HTTP {st2}, Content-Type={h2.get('Content-Type')!r}, {len(body2)} bytes")
if st2 == 200:
    check("inline only for a real image", h2.get("Content-Type", "").startswith("image/"),
          repr(h2.get("Content-Type")))
    check("disposition is inline", h2.get("Content-Disposition", "").startswith("inline"))
    check("nosniff is set here too", h2.get("X-Content-Type-Options") == "nosniff")
    check("same bytes as the download route", body2 == body)
elif st2 == 415:
    print("   refused as not-an-image, which is the correct answer for a non-raster attachment")
    check("a refusal carries no bytes", len(body2) == 0, f"{len(body2)} bytes")
else:
    check("image route answers 200 or 415, nothing else", False, f"HTTP {st2}")

# ---- a bad id ----------------------------------------------------------------------------------
print("\n4. an attachment id that does not exist")
st3, _, _ = call(f"/api/attachments/requirements/{rid}/99999999/file")
print(f"   HTTP {st3}")
check("refused rather than 200 with an error page", st3 != 200, f"HTTP {st3}")

print("\n" + ("ALL CHECKS PASSED" if not failures else "FAILURES: " + ", ".join(failures)))
raise SystemExit(1 if failures else 0)

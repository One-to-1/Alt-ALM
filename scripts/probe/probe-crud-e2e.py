"""End-to-end CRUD against the live sandbox, through the BFF, using the SPA's own request shapes.

What this covers that nothing else does: the component tests assert the SPA's requests against a
mocked fetch, and the BFF's contract tests assert its own service layer against ALM. Neither shows
that the shapes on the wire between them match. This sends exactly what `spa/src/api/client.ts`
sends and checks the BFF accepts it.

Safety, per the live-probe skill:
  - Writes go to the BFF's DEFAULT project only. No `project` parameter is ever sent, so no project
    name appears in this script, its output, or the process list. The default is the sandbox.
  - Every created record carries an ALTALM-E2E-<timestamp> name prefix.
  - Cleanup runs in a finally block, reverse creation order, followed by a prefix sweep — the sweep
    is not redundancy, it is the only cleanup that covers a 5xx that committed.
"""

import json
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
STAMP = time.strftime("%Y%m%d-%H%M%S")
PREFIX = f"ALTALM-E2E-{STAMP}"

created = []
failures = []


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"null")
        except Exception:
            return e.code, {"raw": raw[:200].decode("utf-8", "replace")}


def check(label, condition, detail=""):
    print(f"  {'PASS' if condition else 'FAIL'}  {label}{(' — ' + detail) if detail else ''}")
    if not condition:
        failures.append(label)


try:
    # ---- the parent. The root is a real row; -1 is a sentinel that 500s on a create. ----
    status, roots = call("GET", "/api/tree/roots")
    root = next((r for r in roots if r.get("collection") == "requirements"), None)
    parent_id = (root or {}).get("root", {}).get("id")
    print(f"tree roots: HTTP {status}, requirements root resolved: {parent_id is not None}")
    check("a requirements root exists to file under", parent_id is not None)

    # ---- CREATE, in the shape createRecord() sends ----
    print("\ncreate")
    status, body = call(
        "POST",
        "/api/records/requirements",
        # ⚠️ type-id is supplied because ALM genuinely requires it — and it answers with a clean
        # 400 `qccore.required-field-missing` naming the field by its DISPLAY LABEL, not a 500. The
        # BFF's single missing-required-field retry only fires on the 500 form (probe 9), so this
        # one reaches the user as a refusal, which is correct.
        {"fields": {"name": f"{PREFIX}-record", "parent-id": parent_id, "type-id": "3"}},
    )
    print(f"  HTTP {status}, outcome={body.get('outcome')}, retried={body.get('retried')}")
    new_id = body.get("id")
    if new_id:
        created.append(("requirements", new_id))
    check("create is accepted in the SPA's request shape", status in (200, 201), f"HTTP {status}")
    check("create committed and returned an id", body.get("outcome") == "COMMITTED" and new_id)

    if new_id:
        # ---- the comment field, discovered rather than assumed ----
        print("\ncomment field")
        status, field_body = call("GET", "/api/records/requirements/comment-field")
        field = field_body.get("field")
        print(f"  HTTP {status}, field={field}")
        check("a comment field is discovered for requirements", status == 200 and bool(field))

        # ---- COMMENT, twice: the second must NOT destroy the first ----
        print("\ncomments (the read-modify-write path)")
        status, c1 = call(
            "POST",
            f"/api/records/requirements/{new_id}/comments",
            {"comment": "first e2e comment", "author": "E2E", "expectedVersion": None},
        )
        print(f"  first:  HTTP {status}, outcome={c1.get('outcome')}")
        check("first comment committed", c1.get("outcome") == "COMMITTED", f"HTTP {status}")

        status, c2 = call(
            "POST",
            f"/api/records/requirements/{new_id}/comments",
            {"comment": "second e2e comment", "author": "E2E", "expectedVersion": None},
        )
        print(f"  second: HTTP {status}, outcome={c2.get('outcome')}")
        check("second comment committed", c2.get("outcome") == "COMMITTED", f"HTTP {status}")

        status, detail = call("GET", f"/api/detail/requirements/{new_id}")
        row = (detail.get("rows") or [{}])[0]
        stored = " ".join(row.get("values", {}).get(field, []) or [])
        both = "first e2e comment" in stored and "second e2e comment" in stored
        print(f"  stored comment field holds both: {both}")
        # ⚠️ THE assertion of this whole path. A plain memo PUT would have destroyed the first
        # comment and answered HTTP 200 (probe 30).
        check("the second comment did NOT destroy the first", both)

        # ---- UPDATE, with the ver-stamp the read returned ----
        print("\nupdate")
        version = (row.get("values", {}).get("ver-stamp") or [None])[0]
        status, u = call(
            "PUT",
            f"/api/records/requirements/{new_id}",
            {"fields": {"name": f"{PREFIX}-renamed"}, "expectedVersion": version},
        )
        print(f"  HTTP {status}, outcome={u.get('outcome')}, version sent={version is not None}")
        check("update is accepted in the SPA's request shape", u.get("outcome") == "COMMITTED",
              f"HTTP {status}")

        # ---- a STALE version must be refused, not silently applied ----
        status, conflict = call(
            "PUT",
            f"/api/records/requirements/{new_id}",
            {"fields": {"name": f"{PREFIX}-should-not-apply"}, "expectedVersion": version},
        )
        print(f"  stale version: HTTP {status}, outcome={conflict.get('outcome')}")
        # ALM itself accepts a stale ver-stamp (probe 31); this refusal is entirely the BFF's.
        check("a stale ver-stamp is refused with 409", status == 409, f"HTTP {status}")

finally:
    print("\ncleanup")
    for collection, rid in reversed(created):
        status, _ = call("DELETE", f"/api/records/{collection}/{rid}")
        print(f"  DELETE {collection}/{rid}: HTTP {status}")

    # The prefix sweep. Not redundancy: an UNKNOWN create returns no id, so id-tracked cleanup
    # cannot reach a row a 5xx committed.
    print("\norphan sweep")
    total_orphans = 0
    for collection in ("requirements", "tests", "test-folders", "defects"):
        status, grid = call(
            "GET",
            f"/api/grid/{collection}?pageSize=50&start=1&filter=name:ALTALM-*",
        )
        rows = grid.get("rows", []) if status == 200 else []
        total_orphans += len(rows)
        for r in rows:
            d, _ = call("DELETE", f"/api/records/{collection}/{r['id']}")
            print(f"  swept {collection}/{r['id']}: HTTP {d}")
        print(f"  {collection}: HTTP {status}, {len(rows)} matching ALTALM-*")

    print(f"\n{'ALL CHECKS PASSED' if not failures else 'FAILURES: ' + ', '.join(failures)}")
    print(f"orphans found by sweep: {total_orphans} (0 is the expected result)")

"""Multi-value writes end to end, in the SPA's own request shape.

Probe 33 established the grammar directly against ALM. This checks the other seam: that the SPA's
JSON array survives the BFF's normalisation, the validator's new multi-value rule, and
AlmEntityBody's serialisation, and arrives as two stored values.

Default project only (no `project` parameter anywhere). ALTALM-E2E-* names, cleanup in finally.
"""
import json
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
PREFIX = "ALTALM-E2E-" + time.strftime("%Y%m%d-%H%M%S")

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
            return e.code, {"raw": raw[:300].decode("utf-8", "replace")}


def check(label, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{(' - ' + detail) if detail else ''}")
    if not ok:
        failures.append(label)


def values_of(detail_body, field):
    row = (detail_body.get("rows") or [{}])[0]
    return row.get("values", {}).get(field, []) or []


try:
    # ---- targets: two releases under the release root ---------------------------------------
    st, roots = call("GET", "/api/tree/roots")
    # A release files under a release-FOLDER, so that is the root to ask for. `releases` is not
    # itself a tree and correctly does not appear in this list.
    rel_root = next((r for r in roots if r.get("collection") == "release-folders"), None)
    folder = (rel_root or {}).get("root", {}).get("id")
    print(f"release root: {folder!r}")

    rel_ids = []
    if folder:
        today = time.strftime("%Y-%m-%d")
        end = time.strftime("%Y-%m-%d", time.localtime(time.time() + 30 * 86400))
        for i in (1, 2):
            st, b = call("POST", "/api/records/releases", {"fields": {
                "name": f"{PREFIX}-REL{i}", "parent-id": folder,
                "start-date": today, "end-date": end}})
            if b.get("id"):
                rel_ids.append(b["id"])
                created.append(("releases", b["id"]))
        print(f"releases created: {len(rel_ids)}")
    check("two releases exist to point at", len(rel_ids) == 2)

    if len(rel_ids) == 2:
        # ---- subject requirement ------------------------------------------------------------
        st, grid = call("GET", "/api/grid/requirements?pageSize=1&start=1")
        parent = (grid.get("rows") or [{}])[0].get("id")
        st, b = call("POST", "/api/records/requirements", {"fields": {
            "name": PREFIX + "-MULTI", "parent-id": parent, "type-id": "3"}})
        req = b.get("id")
        if req:
            created.append(("requirements", req))
        check("subject requirement created", bool(req), f"HTTP {st}")

        if req:
            # ---- the array shape the SPA sends ---------------------------------------------
            print("\nmulti-value update, sent as a JSON array")
            st, u = call("PUT", f"/api/records/requirements/{req}",
                         {"fields": {"target-rel": rel_ids}, "expectedVersion": None})
            print(f"  HTTP {st}, outcome={u.get('outcome')}, detail={u.get('detail')}")
            check("the array is accepted", u.get("outcome") == "COMMITTED", f"HTTP {st}")

            st, detail = call("GET", f"/api/detail/requirements/{req}")
            stored = values_of(detail, "target-rel")
            print(f"  stored: {stored!r}")
            # The assertion the whole change rests on. One value stored would be the silent
            # truncation the field was left uneditable to avoid.
            check("BOTH values stored, in order", stored == rel_ids, f"got {stored!r}")

            # ---- the validator's new rule --------------------------------------------------
            print("\nthe multi-value rule, on a field that is not multi-value")
            st, bad = call("PUT", f"/api/records/requirements/{req}",
                           {"fields": {"name": ["one", "two"]}, "expectedVersion": None})
            print(f"  HTTP {st}, outcome={bad.get('outcome')}, problems={bad.get('problems')}")
            # 422: refused by the validator before anything left the BFF.
            check("a second value on a single-value field is refused", st == 422, f"HTTP {st}")

            # ---- clearing -------------------------------------------------------------------
            print("\nclearing a multi-value field")
            st, cl = call("PUT", f"/api/records/requirements/{req}",
                          {"fields": {"target-rel": []}, "expectedVersion": None})
            st2, detail2 = call("GET", f"/api/detail/requirements/{req}")
            after = [v for v in values_of(detail2, "target-rel") if v]
            print(f"  HTTP {st}, outcome={cl.get('outcome')}, stored after: {after!r}")
            check("an empty array clears the field", after == [], f"got {after!r}")

            # ---- a non-string element -------------------------------------------------------
            st, num = call("PUT", f"/api/records/requirements/{req}",
                           {"fields": {"target-rel": [1005]}, "expectedVersion": None})
            print(f"\n  a numeric element: HTTP {st}")
            check("a non-string element is refused, not coerced", st == 400, f"HTTP {st}")

finally:
    print("\ncleanup")
    for collection, rid in reversed(created):
        st, _ = call("DELETE", f"/api/records/{collection}/{rid}")
        print(f"  DELETE {collection}/{rid}: HTTP {st}")

    print("\norphan sweep")
    total = 0
    for collection in ("requirements", "releases", "release-cycles", "release-folders"):
        st, grid = call("GET", f"/api/grid/{collection}?pageSize=50&start=1&filter=name:ALTALM-*")
        rows = grid.get("rows", []) if st == 200 else []
        total += len(rows)
        for r in rows:
            d, _ = call("DELETE", f"/api/records/{collection}/{r['id']}")
            print(f"  swept {collection}/{r['id']}: HTTP {d}")
        print(f"  {collection}: {len(rows)} matching")

    print(f"\n{'ALL CHECKS PASSED' if not failures else 'FAILURES: ' + ', '.join(failures)}")
    print(f"orphans found: {total} (0 expected)")

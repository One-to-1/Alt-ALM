"""Snapshot the sandbox before and after a probe, and report exactly what the run changed.

⚠️ **This replaces delete-everything cleanup** (user, 2026-08-20). The old rule created records,
deleted them in a `finally`, and swept by name prefix. That gave predictable state and orphan
attribution — and it also threw away every reusable target, which cost real work: probe 33 had to
build two releases before it could test anything because the sandbox had none, and P1 validation
could not use the sandbox at all.

A before/after diff gives back both properties without the deletion:

  - **Attribution** is better, not merely equal. The sweep could only find rows whose *name* matched
    a prefix. A diff finds anything that appeared — including the row a 5xx create committed while
    returning no id, which is precisely the case id-tracked cleanup cannot reach.
  - **Predictable state** becomes the probe's job rather than the sandbox's: assert on the delta,
    not on absolute counts. `0 releases` was never a fact about ALM, only about a project nobody had
    used yet.

⚠️ The diff is the safety net now, so a probe MUST print it and a reader must actually look. An
unexpected entry in `modified` is the thing this exists to surface: it means the run touched a
record it did not mean to.

`ver-stamp` is the change detector — it increments on every write, memo writes included (probe 31).
It is useless as a concurrency token and reliable as a "did this row move" flag, which is all this
needs.
"""
import json
import urllib.parse

# Read cheaply: ids and names are enough to attribute a row, and ver-stamp to notice it moved.
_FIELDS = 'id,name,ver-stamp'

# 2000 is the server-stated cap; `page-size=max` also exists. A sandbox collection that genuinely
# exceeds this would make the snapshot partial, which is why `truncated` is reported rather than
# silently ignored — a partial snapshot would report every unseen row as "added" on the next run.
_PAGE = 2000


def _values(entity, name):
    for f in entity.get('Fields', []) or []:
        if f.get('Name') == name:
            return [v.get('value') for v in (f.get('values') or [])]
    return []


def _first(entity, name):
    vals = _values(entity, name)
    return vals[0] if vals else None


def snapshot(call, proj, collections):
    """{collection: {id: {'name': …, 'ver': …}}}, plus a 'truncated' marker per collection.

    `call` is the probe's own request helper, `(method, url) -> (status, text)`, so this inherits
    whatever session and masking the probe already set up rather than opening its own.
    """
    state = {}
    for collection in collections:
        status, text = call('GET', f'{proj}/{collection}?fields={_FIELDS}&page-size={_PAGE}')
        if status != 200:
            # Recorded rather than skipped: a collection that could not be read is not a collection
            # with nothing in it, and treating the two alike would report every row as new later.
            state[collection] = {'_unreadable': status}
            continue
        rows = {}
        for e in (json.loads(text).get('entities') or []):
            rid = _first(e, 'id')
            if rid:
                rows[rid] = {'name': _first(e, 'name'), 'ver': _first(e, 'ver-stamp')}
        if len(rows) >= _PAGE:
            rows['_truncated'] = True
        state[collection] = rows
    return state


def diff(before, after):
    """What changed, per collection: added, removed, modified. Empty collections are omitted."""
    report = {}
    for collection, now in after.items():
        was = before.get(collection, {})
        if '_unreadable' in was or '_unreadable' in now:
            report[collection] = {'unreadable': True}
            continue
        added = [i for i in now if i not in was]
        removed = [i for i in was if i not in now]
        # A row present in both whose ver-stamp moved. This is the entry that matters most: it is
        # how a probe discovers it edited something it did not mean to.
        modified = [i for i in now if i in was and now[i]['ver'] != was[i]['ver']]
        if added or removed or modified:
            report[collection] = {'added': added, 'removed': removed, 'modified': modified}
    return report


def print_diff(report, before, after, mask=lambda s: s):
    """Print the delta in a form a reader can actually check, with names for context."""
    if not report:
        print('   nothing changed')
        return
    for collection, d in sorted(report.items()):
        if d.get('unreadable'):
            print(f'   {collection}: UNREADABLE in one of the two snapshots — delta unknown')
            continue
        for rid in d['added']:
            print(mask(f"   + {collection}/{rid}  {after[collection][rid]['name']!r}"))
        for rid in d['removed']:
            print(mask(f"   - {collection}/{rid}  {before[collection][rid]['name']!r}"))
        for rid in d['modified']:
            print(mask(f"   ~ {collection}/{rid}  {after[collection][rid]['name']!r}  "
                       f"ver {before[collection][rid]['ver']} -> {after[collection][rid]['ver']}"))


def expect(report, expected):
    """Check the delta against what the probe intended, and name anything it did not.

    ⚠️ The point of the whole module. A probe that prints a diff nobody reads has replaced one
    silent failure with another; this turns "did I touch anything unexpected?" into an assertion.

    `expected` is {collection: {'added': n, 'modified': n, 'removed': n}} — counts, not ids, since
    ids are assigned by the server.
    """
    surprises = []
    for collection, d in report.items():
        if d.get('unreadable'):
            surprises.append(f'{collection}: unreadable, so this run cannot account for it')
            continue
        want = expected.get(collection, {})
        for kind in ('added', 'removed', 'modified'):
            got = len(d[kind])
            if got != want.get(kind, 0):
                surprises.append(
                    f'{collection}: {got} {kind}, expected {want.get(kind, 0)}')
    return surprises


def quote(query):
    """URL-encodes an ALM query. ⚠️ Quote multi-word values — a bare one silently matches everything
    (probe 26): `NOT` is a grammar keyword, so `{status[Not Completed]}` means "is not Completed"."""
    return urllib.parse.quote(query)


def snapshot_bff(call, collections, page=500):
    """The same snapshot, taken through the BFF's own grid endpoint.

    A separate function rather than a flag on {@link snapshot}, because the two speak different
    shapes and pretending otherwise is how a parser quietly reads one as the other. ALM returns
    `{entities: [{Fields: [...]}]}`; the BFF returns `{rows: [{id, values: {...}}]}`.

    ⚠️ **Detects added and removed, not modified.** `ver-stamp` is not guaranteed to be among the
    columns the grid returns, and inferring "unchanged" from a field that was never fetched would be
    a false all-clear — the worst kind. A probe that needs change detection takes the ALM-direct
    snapshot instead.

    `call` is the e2e script's helper, `(method, path) -> (status, parsed_json)`.
    """
    state = {}
    for collection in collections:
        status, body = call('GET', f'/api/grid/{collection}?pageSize={page}&start=1')
        if status != 200:
            state[collection] = {'_unreadable': status}
            continue
        rows = {}
        for r in body.get('rows', []) or []:
            rid = r.get('id')
            if rid:
                rows[rid] = {'name': (r.get('values', {}).get('name') or [None])[0], 'ver': None}
        state[collection] = rows
    return state


def diff_bff(before, after):
    """Added/removed only — see {@link snapshot_bff} for why `modified` is deliberately absent."""
    report = {}
    for collection, now in after.items():
        was = before.get(collection, {})
        if '_unreadable' in was or '_unreadable' in now:
            report[collection] = {'unreadable': True}
            continue
        added = [i for i in now if i not in was]
        removed = [i for i in was if i not in now]
        if added or removed:
            report[collection] = {'added': added, 'removed': removed, 'modified': []}
    return report

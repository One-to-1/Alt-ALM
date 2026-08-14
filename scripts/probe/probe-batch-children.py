"""Probe 20 - can one query resolve children for many parents at once?

Question: the tree already fetches child requirements with {parent-id[N]}. Does ALM's documented
`OR` operator (api-ref 4.3, tagged [docs-research], never probed) let one request cover many
parents? If so, probe 19's always-true `hasChildren` guess can become a fact.

READ-ONLY. Every call goes through the running BFF on :8080, so AlmAccessPolicy gates it -- this
script has no credentials of its own and cannot reach a project the BFF has not enrolled.

MASKING: prints ids and counts only. Project keys are hashed to PROJECT-xxxx before display and
requirement names are never requested. Ids are not sensitive (they are opaque integers), but names,
owners and field values are, so the queries ask for id and parent-id and nothing else.

    Prereq: the BFF running with Secrets/local.properties (see SESSION-STATE "Running it").
    Usage:  python scripts/probe/probe-batch-children.py
"""
import hashlib
import json
import urllib.error
import urllib.parse
import urllib.request

BASE = 'http://localhost:8080'


def get(path, **params):
    url = BASE + path + ('?' + urllib.parse.urlencode(params, doseq=True) if params else '')
    try:
        with urllib.request.urlopen(url) as r:
            return json.load(r), None
    except urllib.error.HTTPError as e:
        return None, f'HTTP {e.code} {e.read().decode("utf-8", "replace")[:160]}'
    except Exception as e:
        return None, type(e).__name__


def pseudo(key):
    """Never print a real domain/project. PROJECT-xxxx is stable within a run."""
    return 'PROJECT-' + hashlib.sha256(key.encode()).hexdigest()[:4]


def main():
    projects, err = get('/api/projects')
    if err:
        print(f'cannot reach the BFF: {err}')
        print('start it first -- see docs/research/SESSION-STATE.md "Running it"')
        return

    # Largest read-only project wins; the sandbox holds 1 requirement and proves nothing.
    target, biggest = None, -1
    for p in projects:
        if p['writable']:
            continue
        key = f'{p["domain"]}/{p["project"]}'
        g, _ = get('/api/grid/requirements', project=key, pageSize=2000, start=1, sort='id')
        if g and len(g['rows']) > biggest:
            target, biggest = key, len(g['rows'])
    if target is None:
        print('no read-only project with requirements is enrolled')
        return
    print(f'target {pseudo(target)}  requirements={biggest}')

    # Ground truth, built independently of the mechanism under test: read every requirement in one
    # page and group by the parent-id each row carries about itself.
    page, _ = get('/api/grid/requirements', project=target, pageSize=2000, start=1, sort='id')
    ids = [r['id'] for r in page['rows']]
    truth = {}
    for row in page['rows']:
        parent = str((row['values'].get('parent-id') or [''])[0])
        truth.setdefault(parent, []).append(row['id'])
    has_children = set(truth)
    print(f'ids={len(ids)}  parents that have children={len(has_children)}')

    # Baseline: the current one-request-per-node path, for a handful of real folders.
    roots, _ = get('/api/tree/roots', project=target)
    root = next(r['root'] for r in roots if r['collection'] == 'requirements')
    kids, _ = get('/api/tree/requirements/children', project=target, parentId=root['id'])
    sample = [n['id'] for n in kids['nodes']][:8]
    one_at_a_time = {}
    for cid in sample:
        c, e = get('/api/tree/requirements/children', project=target, parentId=cid)
        one_at_a_time[cid] = len(c['nodes']) if c else f'ERR {e}'
    print(f'root id={root["id"]}  direct children={len(sample)}')
    print('  one request per node :', one_at_a_time)
    print('  ground truth         :', {c: len(truth.get(c, [])) for c in sample})

    # The test. Walk the term count up; a silent truncation would show as a ground-truth mismatch
    # rather than an error, which is why every row is compared and not just counted.
    print('batched {parent-id[a OR b OR ...]}:')
    for n in (8, 16, 32, 63, 100, len(ids)):
        chunk = ids[:n]
        expr = ' OR '.join(chunk)
        g, e = get('/api/grid/requirements', project=target, pageSize=2000, start=1,
                   sort='id', filter=[f'parent-id:{expr}'])
        if e:
            print(f'  {n:5} terms (qlen {len(expr):5}) -> {e[:120]}')
            continue
        found = {str((r['values'].get('parent-id') or [''])[0]) for r in g['rows']}
        expected = {c for c in chunk if c in has_children}
        print(f'  {n:5} terms (qlen {len(expr):5}) -> {len(g["rows"]):4} rows, '
              f'{len(found):3} parents, matches ground truth: {found == expected}')

    # Does the same trick hold on the other tree collection?
    folders, _ = get('/api/grid/test-folders', project=target, pageSize=2000, start=1, sort='id')
    if folders and folders['rows']:
        fids = [r['id'] for r in folders['rows']][:20]
        g, e = get('/api/grid/test-folders', project=target, pageSize=2000, start=1,
                   sort='id', filter=[f'parent-id:{" OR ".join(fids)}'])
        print('test-folders batched  :', f'{len(g["rows"])} rows' if not e else e[:120])


if __name__ == '__main__':
    main()

"""Probe 24 - what does `GET {collection}/{id}/audits` actually return, and for which entities?

The api-reference says audits exist on 24 collections and that coverage is PARTIAL (probe 4 round 1:
a requirement with a create + 2 rich-text PUTs + a coverage link produced exactly 2 audit entries,
both `status` changes). That tells us audits are thin. It does NOT tell us their SHAPE - whether the
response is an ordinary `{entities:[...]}` envelope, what an entry's fields are called, or whether
the per-field before/after values are nested inside an entry or are separate rows.

The History tab cannot be built on a guess about that, and `audit`/`audit-property` are not in the
entity metadata we have parsed, so the field list has to come from the payload itself.

Hypothesis: `GET requirements/{id}/audits` returns the standard entity envelope, one entity per
CHANGE EVENT, each carrying a nested list of per-field before/after properties.
Refuting observation: a flat entity list with one row per FIELD change and no nesting, or a
non-standard envelope, or a 404 saying the sub-resource does not exist.

READ-ONLY. GET only.

Reports SHAPES ONLY - the envelope's keys, each entry's field NAMES, and for values only whether one
is present. Audit rows carry other teams' edits: who changed what, and from what to what. Field
names are schema (safe); the values and the usernames are their data and are never printed. Masks
host/domain/project/key/secret/user.

    python scripts/probe/probe-audits.py
"""
import collections
import http.cookiejar
import json
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
SECRETS = ROOT / 'Secrets' / 'ALM_API_credentials.json'
READ_PROJECTS = ROOT / 'Secrets' / 'alm-read-projects.json'

# Collections whose History tab P1 would offer. `runs` is in here because a run's history is the one
# case where ALM's own client leans on audits hardest.
PROBE_COLLECTIONS = ['requirements', 'tests', 'test-sets', 'defects', 'runs']

# Fields whose values are the OTHER TEAM'S data and must never be printed, only counted.
VALUE_FIELDS = {'old-value', 'new-value', 'user', 'username', 'parent-name', 'comment'}

MASK = []


def mask(s):
    out = str(s)
    for term in MASK:
        if term:
            out = re.sub(re.escape(term), 'REDACTED', out, flags=re.I)
    return out


def describe(node, path, out, depth=0):
    """Record the KEY STRUCTURE of a JSON node. Values are reported as types, never as content."""
    if depth > 6:
        return
    if isinstance(node, dict):
        for k, v in node.items():
            out[f'{path}.{k}' if path else k] = type(v).__name__
            describe(v, f'{path}.{k}' if path else k, out, depth + 1)
    elif isinstance(node, list) and node:
        describe(node[0], f'{path}[]', out, depth + 1)


def main():
    if not SECRETS.exists():
        print('no credentials; nothing to do')
        return 1
    c = json.loads(SECRETS.read_text(encoding='utf-8'))
    base = str(c['alm_adress']).strip().rstrip('/')
    if not base.endswith('/qcbin'):
        base += '/qcbin'
    for k in ('api_key', 'api_secret', 'domain', 'project'):
        if c.get(k):
            MASK.append(str(c[k]))
    MASK.append(urllib.parse.urlparse(base).netloc)

    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    body = json.dumps({'clientId': c['api_key'], 'secret': c['api_secret']}).encode()
    opener.open(urllib.request.Request(base + '/rest/oauth2/login', data=body,
                                       headers={'Content-Type': 'application/json'})).read()

    def get(url):
        r = urllib.request.Request(url, headers={'Accept': 'application/json'})
        with opener.open(r) as resp:
            return resp.status, resp.read().decode('utf-8', 'replace')

    try:
        _, who = get(base + '/v2/rest/is-authenticated')
        user = json.loads(who).get('AuthenticationInfo', {}).get('Username')
        if user:
            MASK.append(user)
    except Exception:
        pass

    if not READ_PROJECTS.exists():
        print('no read-project list; the sandbox has too little history to shape this')
        return 1
    data = json.loads(READ_PROJECTS.read_text(encoding='utf-8'))
    domain = data.get('domain') or c['domain']
    MASK.append(str(domain))
    target = None
    for row in data.get('projects', []):
        MASK.append(str(row.get('name')))
        if row.get('alias') == 'PROJECT-5':
            target = row
    if target is None:
        print('PROJECT-5 not in the read list')
        return 1

    proj = f"{base}/rest/domains/{domain}/projects/{target['name']}"
    print('reading PROJECT-5, GET only, reporting SHAPES only\n')

    for collection in PROBE_COLLECTIONS:
        # One record is enough to shape the payload; take the first the collection offers.
        try:
            status, txt = get(f'{proj}/{collection}?page-size=1&fields=id')
        except urllib.error.HTTPError as e:
            print(f'{collection:16} listing HTTP {e.code}')
            continue
        entities = json.loads(txt).get('entities', [])
        if not entities:
            print(f'{collection:16} no rows')
            continue
        rec_id = None
        for f in entities[0].get('Fields', []):
            if f.get('Name') == 'id':
                rec_id = (f.get('values') or [{}])[0].get('value')
        if not rec_id:
            print(f'{collection:16} first row has no id')
            continue

        url = f'{proj}/{collection}/{rec_id}/audits'
        try:
            status, txt = get(url)
        except urllib.error.HTTPError as e:
            print(f'{collection:16} audits HTTP {e.code}  {mask(e.read().decode("utf-8", "replace"))[:200]}')
            continue
        if status != 200:
            print(f'{collection:16} audits HTTP {status}')
            continue

        payload = json.loads(txt)
        shape = {}
        describe(payload, '', shape)
        top = [k for k in shape if '.' not in k and '[' not in k]
        print(f'{collection:16} HTTP 200   envelope keys: {top}')

        # `Audits` / `entities` / something else? Report whichever list the payload actually has.
        listing = None
        for key in ('entities', 'Audits', 'audits', 'Audit'):
            if isinstance(payload.get(key), list):
                listing = payload[key]
                print(f'{"":16} list is under "{key}", {len(listing)} entries')
                break
        if listing is None:
            print(f'{"":16} no list found; full key structure follows')
            for k, t in sorted(shape.items()):
                print(f'{"":18} {k}: {t}')
            continue
        if not listing:
            continue

        # Entry-level field names are schema. Values are their data: presence only.
        names = collections.Counter()
        nested = collections.Counter()
        for entry in listing:
            for f in entry.get('Fields', []) if isinstance(entry, dict) else []:
                names[f.get('Name')] += 1
            for k, v in (entry.items() if isinstance(entry, dict) else []):
                if k != 'Fields':
                    nested[f'{k}:{type(v).__name__}'] += 1
        print(f'{"":16} entry keys      : {dict(nested)}')
        print(f'{"":16} entry field names: {sorted(n for n in names if n)}')

        # If entries nest per-property rows, name those too.
        for entry in listing[:1]:
            for k, v in (entry.items() if isinstance(entry, dict) else []):
                if isinstance(v, list) and v and isinstance(v[0], dict):
                    inner = collections.Counter()
                    for row in v:
                        for f in row.get('Fields', []):
                            inner[f.get('Name')] += 1
                        for ik in row:
                            if ik != 'Fields':
                                inner[f'<{ik}>'] += 1
                    if inner:
                        print(f'{"":16} nested "{k}" field names: {sorted(n for n in inner if n)}')
        print()

    try:
        opener.open(base + '/authentication-point/logout').read()
    except Exception:
        pass
    return 0


if __name__ == '__main__':
    sys.exit(main())

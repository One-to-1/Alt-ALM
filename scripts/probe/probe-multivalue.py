"""Probe 33 - what wire shape does a MULTI-VALUE field write take?

The model has exactly two multi-value fields, both on requirements and both References:
`target-rel` (-> release) and `target-rcyc` (-> release-cycle). Alt-ALM currently refuses to edit
them, and the SPA says so rather than faking a control - a single-value dropdown would silently drop
the other values on save.

Building the real control needs one fact nobody has established: **how a multi-value write is
spelled**. The READ shape is known - `values` is an array and carries one object per value - but a
read shape is not a write shape, and CLAUDE.md's rule is explicit that an unverified claim gets
labelled rather than implemented. Three candidate spellings are plausible and only one costs nothing
to be wrong about:

  A. repeated entries   {"Name":"target-rel","values":[{"value":"1"},{"value":"2"}]}
  B. semicolon-joined   {"Name":"target-rel","values":[{"value":"1;2"}]}
  C. comma-joined       {"Name":"target-rel","values":[{"value":"1,2"}]}

Hypothesis: (A) is accepted and stores both values.
Refuting observation: the write is refused, OR it returns 200/201 and a read-back shows one value,
or a literal "1;2" stored as a string - the silent-corruption case, which is the one worth finding
before a UI is built on it.

⚠️ The read-back is the whole probe. A 200 proves nothing here: ALM answers 200 for writes that
stored something other than what was sent (probe 30 destroyed a comment for a 200), so every
candidate is judged on what comes back, never on the status.

Also settled here, because the control needs it:
  - is `target-rel` writable at all, per metadata AND in practice?
  - does a read return one entry per value, or one joined string?
  - can a multi-value field be CLEARED, and how?

WRITES. Sandbox only. ALTALM-PROBE-<timestamp> names, cleanup in `finally`, orphan sweep.
Printed output is ASCII-only - a UnicodeEncodeError on a cp1252 console aborts mid-run.

    python scripts/probe/probe-multivalue.py
"""
import datetime
import http.cookiejar
import json
import pathlib
import re
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
SECRETS = ROOT / 'Secrets' / 'ALM_API_credentials.json'

PREFIX = 'ALTALM-PROBE-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S')

MASK = []
created = []   # (collection, id), deleted in reverse


def mask(s):
    out = str(s)
    for t in MASK:
        if t:
            out = re.sub(re.escape(t), 'REDACTED', out, flags=re.I)
    return out


def field_values(entity, name):
    """EVERY value, not just the first - which is the entire point of this probe."""
    for f in entity.get('Fields', []) or []:
        if f.get('Name') == name:
            return [v.get('value') for v in (f.get('values') or [])]
    return []


def first_value(entity, name):
    vals = field_values(entity, name)
    return vals[0] if vals else None


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
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    op.open(urllib.request.Request(base + '/rest/oauth2/login',
                                   data=json.dumps({'clientId': c['api_key'],
                                                    'secret': c['api_secret']}).encode(),
                                   headers={'Content-Type': 'application/json'})).read()
    xsrf = next((k.value for k in jar if k.name == 'XSRF-TOKEN'), '')

    def call(method, url, body=None):
        headers = {'Accept': 'application/json'}
        if method != 'GET':
            headers['X-XSRF-TOKEN'] = xsrf
        if body is not None:
            headers['Content-Type'] = 'application/json'
        try:
            with op.open(urllib.request.Request(url, data=body, headers=headers,
                                                method=method)) as r:
                return r.status, r.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode('utf-8', 'replace')

    try:
        _, who = call('GET', base + '/v2/rest/is-authenticated')
        u = json.loads(who).get('AuthenticationInfo', {}).get('Username')
        if u:
            MASK.append(u)
    except Exception:
        pass

    proj = f"{base}/rest/domains/{c['domain']}/projects/{c['project']}"
    print(f'SANDBOX. prefix {PREFIX}\n')

    def post(collection, fields, entity_type):
        body = json.dumps({'Fields': [{'Name': n, 'values': [{'value': v}]}
                                      for n, v in fields],
                           'Type': entity_type}).encode()
        code, txt = call('POST', f'{proj}/{collection}', body)
        if code not in (200, 201):
            print(f'   create {collection} HTTP {code}: {mask(txt)[:220]}')
            return None
        rid = first_value(json.loads(txt), 'id')
        if rid:
            created.append((collection, rid))
        return rid

    try:
        # ---- 1. what does metadata claim? -------------------------------------------------
        print('-- 1. metadata on the two multi-value fields')
        code, txt = call('GET', f'{proj}/customization/entities/requirement/fields')
        multi = []
        if code == 200:
            for f in json.loads(txt).get('Fields', {}).get('Field', []):
                if f.get('name') in ('target-rel', 'target-rcyc'):
                    print(f"   {f['name']:12} type={f.get('type'):10} "
                          f"multiValue={f.get('supportsMultivalue')} "
                          f"editable={f.get('editable')} required={f.get('required')} "
                          f"physical={f.get('physicalName')}")
                    multi.append(f['name'])
        if not multi:
            print('   neither field is present in this project - nothing to probe')
            return 1

        # ---- 2. the targets. The sandbox has no releases, so make some. -------------------
        print('\n-- 2. building two releases to point at')
        # The verified root rule: {parent-id[-1]} first, fall back to {parent-id[0]}.
        root = None
        for q in ('{parent-id[-1]}', '{parent-id[0]}'):
            code, txt = call('GET', f'{proj}/release-folders?query={urllib.parse.quote(q)}'
                                    f'&fields=id,name&page-size=5')
            if code == 200:
                rows = json.loads(txt).get('entities') or []
                if rows:
                    root = first_value(rows[0], 'id')
                    print(f'   release-folder root via {q}: id={root} '
                          f'name={first_value(rows[0], "name")!r}')
                    break
        if root is None:
            print('   no release-folder root found; cannot build targets')
            return 1

        # A sub-folder is not required: the root IS a release-folder and releases file under it.
        # Tried first anyway because a probe-created folder makes cleanup tidier - but a failure
        # here is not fatal, and it is worth recording that it failed.
        folder = post('release-folders', [('name', PREFIX + '-RELFLD'), ('parent-id', root)],
                      'release-folder')
        if not folder:
            print('   sub-folder creation refused; filing the releases under the root instead')
            folder = root

        today = datetime.date.today()
        rel_ids = []
        for i in (1, 2):
            rid = post('releases', [
                ('name', f'{PREFIX}-REL{i}'),
                ('parent-id', folder),
                ('start-date', str(today)),
                ('end-date', str(today + datetime.timedelta(days=30))),
            ], 'release')
            if rid:
                rel_ids.append(rid)
        print(f'   releases created: {len(rel_ids)}')
        if len(rel_ids) < 2:
            print('   need two targets to tell "multi-value worked" from "one value stored"')
            return 1

        # ---- 3. the subject requirement ---------------------------------------------------
        code, txt = call('GET', f'{proj}/requirements?page-size=1&fields=id,type-id')
        first = (json.loads(txt).get('entities') or [{}])[0]
        req = post('requirements', [
            ('name', PREFIX + '-MULTI'),
            ('parent-id', first_value(first, 'id')),
            ('type-id', first_value(first, 'type-id') or '1'),
        ], 'requirement')
        if not req:
            return 1

        def read_back(label):
            st, t = call('GET', f'{proj}/requirements/{req}'
                                f'?fields=id,target-rel,target-rcyc')
            vals = field_values(json.loads(t), 'target-rel')
            print(f'   {label:38} -> HTTP {st}, target-rel={vals!r} ({len(vals)} value(s))')
            return vals

        # ---- 4. the baseline: does ONE value write at all? --------------------------------
        print('\n-- 3. baseline: a single value')
        st, t = call('PUT', f'{proj}/requirements/{req}', json.dumps({'Fields': [
            {'Name': 'target-rel', 'values': [{'value': rel_ids[0]}]}],
            'Type': 'requirement'}).encode())
        print(f'   PUT one value: HTTP {st}')
        if st not in (200, 201):
            print(f'   body: {mask(t)[:220]}')
        one = read_back('after a single-value PUT')
        if not one:
            print('   => target-rel does not accept a write at all. Everything below is moot.')

        # ---- 5. the candidates ------------------------------------------------------------
        print('\n-- 4. the three candidate spellings, each judged on the READ-BACK')
        results = {}

        def try_shape(label, values_array):
            st, t = call('PUT', f'{proj}/requirements/{req}', json.dumps({'Fields': [
                {'Name': 'target-rel', 'values': values_array}],
                'Type': 'requirement'}).encode())
            print(f'\n   {label}')
            print(f'     sent   : {json.dumps(values_array)}')
            print(f'     status : HTTP {st}')
            if st not in (200, 201):
                print(f'     body   : {mask(t)[:200]}')
            got = read_back('     read-back')
            # Both targets present, as separate values, is the only unambiguous success.
            ok = sorted(got) == sorted(rel_ids)
            corrupted = len(got) == 1 and got[0] not in rel_ids
            print(f'     verdict: {"BOTH STORED" if ok else ("CORRUPTED (joined string stored)" if corrupted else "not both")}')
            results[label] = (st, got, ok)
            # Reset between candidates so the next one cannot inherit this one's state.
            call('PUT', f'{proj}/requirements/{req}', json.dumps({'Fields': [
                {'Name': 'target-rel', 'values': [{'value': ''}]}],
                'Type': 'requirement'}).encode())
            return ok

        try_shape('A. repeated entries', [{'value': rel_ids[0]}, {'value': rel_ids[1]}])
        try_shape('B. semicolon-joined', [{'value': ';'.join(rel_ids)}])
        try_shape('C. comma-joined', [{'value': ','.join(rel_ids)}])

        # ---- 6. clearing ------------------------------------------------------------------
        print('\n-- 5. can a multi-value field be cleared?')
        winner = next((k for k, v in results.items() if v[2]), None)
        if winner:
            shape = ([{'value': rel_ids[0]}, {'value': rel_ids[1]}] if winner.startswith('A')
                     else [{'value': (';' if winner.startswith('B') else ',').join(rel_ids)}])
            call('PUT', f'{proj}/requirements/{req}', json.dumps({'Fields': [
                {'Name': 'target-rel', 'values': shape}], 'Type': 'requirement'}).encode())
            read_back('re-populated')
            st, _ = call('PUT', f'{proj}/requirements/{req}', json.dumps({'Fields': [
                {'Name': 'target-rel', 'values': [{'value': ''}]}],
                'Type': 'requirement'}).encode())
            after = read_back(f'after clearing with empty string (HTTP {st})')
            print(f'   => clearing {"WORKS" if not [a for a in after if a] else "does NOT work"}')

        # ---- verdict ----------------------------------------------------------------------
        print('\n== VERDICT ==')
        for label, (st, got, ok) in results.items():
            print(f'   {label:24} HTTP {st}  stored={got!r}  {"ACCEPTED" if ok else "no"}')
        if winner:
            print(f'\n   Multi-value writes are spelled: {winner}')
        else:
            print('\n   No candidate stored both values. A multi-value control cannot be built on')
            print('   REST alone until one does - record this as the blocker, do not guess a shape.')

    finally:
        print('\n-- cleanup')
        for collection, rid in reversed(created):
            st, _ = call('DELETE', f'{proj}/{collection}/{rid}')
            print(f'   DELETE {collection}/{rid}: HTTP {st}')

        print('\n-- orphan sweep')
        total = 0
        for collection in ('requirements', 'releases', 'release-cycles', 'release-folders'):
            q = urllib.parse.quote('{name[ALTALM-PROBE*]}')
            st, t = call('GET', f'{proj}/{collection}?query={q}&fields=id,name&page-size=50')
            rows = (json.loads(t).get('entities') or []) if st == 200 else []
            total += len(rows)
            for r in rows:
                d, _ = call('DELETE', f'{proj}/{collection}/{first_value(r, "id")}')
                print(f'   swept {collection}/{first_value(r, "id")}: HTTP {d}')
            print(f'   {collection}: {len(rows)} matching')
        print(f'   orphans found: {total} (0 is the expected result)')

        # Logout is two calls and both need XSRF; the status varies and the outcome does not.
        call('DELETE', base + '/rest/site-session')
        call('POST', base + '/authentication-point/logout')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

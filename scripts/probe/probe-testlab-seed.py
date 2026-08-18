"""Probe 28 - can the sandbox hold a Test Lab chain, and what does each link actually need?

The borrowed projects are gone (user, 2026-08-18), so the sandbox is the only reachable project and
it holds 0 tests and 0 test sets. Test Lab's drill-down - test set -> instances -> runs - is the next
P1 feature and there is currently nothing to drill into, which would mean building a navigation
feature that cannot be looked at.

So: seed the chain. Five entities, each one a link in the DAG the generator spec describes:

    test-folder -> test -> (test-set-folder -> test-set) -> test-instance

Hypothesis: every step is an ordinary POST, and the instance binds to its set and its test through
two id fields on the instance itself.
Refuting observation: any step that 4xx/5xxs after a field-order retry, which would mean Test Lab
data cannot be produced over REST and the drill-down has to be verified some other way.

What this probe is really measuring is the FIELD each link needs, because that is what the
drill-down's scoping filter has to agree with. The names are read back off the created records
rather than assumed - `cycle-id` is the folklore answer and folklore is not evidence.

WRITES - sandbox only (designated by the user 2026-08-12), everything prefixed ALTALM-PROBE-, and
cleanup runs in reverse creation order in a finally block.

    python scripts/probe/probe-testlab-seed.py           # seed, report, delete
    python scripts/probe/probe-testlab-seed.py --keep    # seed and LEAVE IT (then --sweep)
    python scripts/probe/probe-testlab-seed.py --sweep   # delete every ALTALM-PROBE-* record
"""
import datetime
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
PREFIX = 'ALTALM-PROBE-'

# Reverse creation order matters: a test-set holding instances and a folder holding tests both
# refuse to vanish cleanly if their children are still there.
#
# test-instances is NOT in this list, and that is the point. A test instance has no `name` field at
# all - its identity comes from the test it points at - so `?query={name[ALTALM-PROBE*]}` against
# test-instances returns HTTP 404, not an empty list. The documented name-prefix sweep therefore
# cannot see an orphaned instance, and would report "no orphans" while leaving one behind. They are
# swept through their parent test set instead, before the set itself goes.
SWEEP_ORDER = ['test-sets', 'test-set-folders', 'tests', 'test-folders']

MASK = []


def mask(s):
    out = str(s)
    for term in MASK:
        if term:
            out = re.sub(re.escape(term), 'REDACTED', out, flags=re.I)
    return out


def main():
    argv = sys.argv[1:]
    keep = '--keep' in argv

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
    xsrf = next((ck.value for ck in jar if ck.name == 'XSRF-TOKEN'), None)
    if not xsrf:
        print('no XSRF token; every write would 401')
        return 1

    def call(method, url, payload=None):
        headers = {'Accept': 'application/json'}
        data = None
        if payload is not None:
            headers['Content-Type'] = 'application/json'
            data = payload.encode('utf-8')
        if method != 'GET':
            headers['X-XSRF-TOKEN'] = xsrf
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with opener.open(req) as resp:
                return resp.status, resp.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode('utf-8', 'replace')

    try:
        _, who = call('GET', base + '/v2/rest/is-authenticated')
        user = json.loads(who).get('AuthenticationInfo', {}).get('Username')
        if user:
            MASK.append(user)
    except Exception:
        pass

    proj = f"{base}/rest/domains/{c['domain']}/projects/{c['project']}"
    print(f'writing to the SANDBOX only ({mask(c["project"])})\n')

    def field(entity, name):
        for f in entity.get('Fields', []):
            if f.get('Name') == name:
                vals = f.get('values') or []
                return vals[0].get('value') if vals else None
        return None

    def sweep():
        total = 0

        # Instances first, reached through their sets: they have no name to match on, and deleting
        # the set out from under them is what creates the orphan this exists to prevent.
        status, txt = call(
            'GET', f'{proj}/test-sets?query={{name[{PREFIX}*]}}&fields=id,name&page-size=100')
        if status == 200:
            for row in json.loads(txt).get('entities', []):
                set_id = field(row, 'id')
                st, body = call('GET', f'{proj}/test-instances?query={{cycle-id[{set_id}]}}'
                                       '&fields=id&page-size=100')
                if st != 200:
                    print(f'sweep: instances of set {set_id} -> HTTP {st}')
                    continue
                for inst in json.loads(body).get('entities', []):
                    iid = field(inst, 'id')
                    code, _ = call('DELETE', f'{proj}/test-instances/{iid}')
                    print(f'sweep: DELETE test-instances/{iid} -> HTTP {code}')
                    total += 1

        for collection in SWEEP_ORDER:
            status, txt = call(
                'GET', f'{proj}/{collection}?query={{name[{PREFIX}*]}}&fields=id,name&page-size=100')
            if status != 200:
                print(f'sweep: {collection} listing HTTP {status}')
                continue
            for row in json.loads(txt).get('entities', []):
                rid = field(row, 'id')
                code, _ = call('DELETE', f'{proj}/{collection}/{rid}')
                print(f'sweep: DELETE {collection}/{rid} -> HTTP {code}')
                total += 1
        if total == 0:
            print('sweep: no orphans')

    if '--sweep' in argv:
        sweep()
        call('POST', base + '/authentication-point/logout')
        return 0

    def root_of(collection):
        """Probe 15's rule: {parent-id[-1]} first, {parent-id[0]} as the fallback."""
        for sentinel in ('-1', '0'):
            status, txt = call(
                'GET', f'{proj}/{collection}?query={{parent-id[{sentinel}]}}&fields=id,name')
            if status == 200:
                rows = json.loads(txt).get('entities', [])
                if rows:
                    return field(rows[0], 'id'), field(rows[0], 'name')
        return None, None

    def create(collection, fields, label):
        entity = {'Fields': [{'Name': k, 'values': [{'value': str(v)}]} for k, v in fields.items()],
                  'Type': collection.rstrip('s') if not collection.endswith('ies') else collection}
        status, txt = call('POST', f'{proj}/{collection}', json.dumps(entity))
        if status not in (200, 201):
            snippet = mask(re.sub(r'\s+', ' ', txt))[:300]
            print(f'  {label:16} POST -> HTTP {status}\n      {snippet}')
            return None
        rid = field(json.loads(txt), 'id')
        created.append((collection, rid))
        print(f'  {label:16} -> {collection}/{rid}')
        return rid

    created = []
    try:
        stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')

        for collection in ('test-folders', 'test-set-folders'):
            rid, name = root_of(collection)
            print(f'{collection:18} root id={rid} name={mask(name)!r}')
        print()

        test_root, _ = root_of('test-folders')
        set_root, _ = root_of('test-set-folders')

        folder_id = create('test-folders', {'name': f'{PREFIX}{stamp}-folder',
                                            'parent-id': test_root}, 'test folder')
        test_id = create('tests', {'name': f'{PREFIX}{stamp}-test', 'parent-id': folder_id,
                                   'subtype-id': 'MANUAL', 'owner': user or ''}, 'test')
        set_folder_id = create('test-set-folders', {'name': f'{PREFIX}{stamp}-setfolder',
                                                   'parent-id': set_root}, 'test-set folder')
        set_id = create('test-sets', {'name': f'{PREFIX}{stamp}-set', 'parent-id': set_folder_id,
                                      'subtype-id': 'hp.qc.test-set.default'}, 'test set')

        instance_id = None
        if test_id and set_id:
            instance_id = create('test-instances',
                                 {'test-id': test_id, 'cycle-id': set_id, 'subtype-id': 'hp.qc.test-instance.MANUAL'},
                                 'test instance')

        if instance_id:
            _, txt = call('GET', f'{proj}/test-instances/{instance_id}')
            inst = json.loads(txt)
            print('\n--- which fields on the INSTANCE point back at its set and its test ---')
            for f in inst.get('Fields', []):
                vals = f.get('values') or []
                v = vals[0].get('value') if vals else None
                if v in (str(set_id), str(test_id)) and v:
                    points_at = 'the test set' if v == str(set_id) else 'the test'
                    print(f'  {f.get("Name"):22} = {v:6}  -> {points_at}')

        if keep:
            print(f'\n--keep: {len(created)} records LEFT IN THE SANDBOX. Run --sweep when done.')
            created.clear()
    finally:
        for collection, rid in reversed(created):
            code, _ = call('DELETE', f'{proj}/{collection}/{rid}')
            print(f'cleanup: DELETE {collection}/{rid} -> HTTP {code}')
        if not keep:
            sweep()
        call('POST', base + '/authentication-point/logout')
    return 0


if __name__ == '__main__':
    sys.exit(main())

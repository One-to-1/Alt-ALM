"""Probe 31 - is `ver-stamp` an optimistic-concurrency token, or just a counter?

Probe 30 established that a memo PUT REPLACES the field, so Alt-ALM's comment path has to be
read-modify-write. That inherits the lost-update problem immediately: two people open the same
record, both append a comment, the second write is built on a value read before the first landed,
and the first person's comment vanishes. HTTP 200, no warning, exactly like the destructive case
read-modify-write was introduced to fix - just harder to notice.

The standard fix is optimistic concurrency: send the version you read, and let the server refuse the
write if it has moved on. ALM exposes `ver-stamp` and it was observed incrementing on the row probe
29 created. Whether it can be USED that way is a different question, and it decides the shape of the
comment path - so it is settled before that path is built, not after.

Hypothesis: including a stale `ver-stamp` in a PUT body causes ALM to reject the write.
Refuting observation: the PUT succeeds and the stale value is ignored or silently overwritten, which
would mean ALM offers no concurrency control on this route and Alt-ALM must either accept
last-writer-wins or build detection itself.

Four questions, in order:
  1. Does `ver-stamp` increment on every write, or only on some?
  2. Is it writable at all, per metadata and in practice?
  3. Does sending a STALE one get rejected?
  4. Does sending a CURRENT one work, so a rejection in (3) would mean concurrency rather than
     "ver-stamp is simply not accepted in a body"?

Question 4 is the one that makes the others mean anything: without it, a rejection in (3) is
indistinguishable from ALM refusing the field outright.

WRITES. Sandbox only. ALTALM-PROBE-<timestamp> names, cleanup in `finally`, orphan sweep.
Printed output is ASCII-only - a UnicodeEncodeError on a cp1252 console aborts mid-run and jumps to
`finally`, which has already cost two probes.

    python scripts/probe/probe-verstamp.py
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

PREFIX = 'ALTALM-PROBE-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S')

MASK = []
created = []


def mask(s):
    out = str(s)
    for t in MASK:
        if t:
            out = re.sub(re.escape(t), 'REDACTED', out, flags=re.I)
    return out


def field_value(entity, name):
    for f in entity.get('Fields', []) or []:
        if f.get('Name') == name:
            vals = f.get('values') or []
            return vals[0].get('value') if vals else None
    return None


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

    try:
        # ---- 2a. what does metadata claim about ver-stamp? --------------------------------
        print('-- 1. metadata on ver-stamp')
        code, txt = call('GET', f'{proj}/customization/entities/requirement/fields')
        if code == 200:
            for f in json.loads(txt).get('Fields', {}).get('Field', []):
                if f.get('name') in ('ver-stamp', 'last-modified'):
                    print(f"   {f['name']:16} type={f.get('type'):9} editable={f.get('editable')} "
                          f"required={f.get('required')} active={f.get('active')} "
                          f"physical={f.get('physicalName')}")

        # ---- create the subject -----------------------------------------------------------
        code, txt = call('GET', f'{proj}/requirements?page-size=1&fields=id,type-id')
        first = (json.loads(txt).get('entities') or [{}])[0]
        parent = field_value(first, 'id')
        type_id = field_value(first, 'type-id') or '1'

        body = json.dumps({'Fields': [
            {'Name': 'name', 'values': [{'value': PREFIX + '-VER'}]},
            {'Name': 'parent-id', 'values': [{'value': parent}]},
            {'Name': 'type-id', 'values': [{'value': type_id}]},
        ], 'Type': 'requirement'}).encode()
        code, txt = call('POST', f'{proj}/requirements', body)
        if code not in (200, 201):
            print(f'  create HTTP {code}: {mask(txt)[:200]}')
            return 1
        rid = field_value(json.loads(txt), 'id')
        created.append(rid)

        def read_stamp(label):
            st, t = call('GET', f'{proj}/requirements/{rid}?fields=id,ver-stamp,last-modified')
            j = json.loads(t)
            v = field_value(j, 'ver-stamp')
            m = field_value(j, 'last-modified')
            print(f'   {label:34} ver-stamp={v!r} last-modified={m!r}')
            return v

        print('\n-- 2. does ver-stamp move on every write?')
        v0 = read_stamp('after create')
        call('PUT', f'{proj}/requirements/{rid}', json.dumps({'Fields': [
            {'Name': 'description', 'values': [{'value': 'edit 1'}]}], 'Type': 'requirement'}).encode())
        v1 = read_stamp('after a plain-field PUT')
        call('PUT', f'{proj}/requirements/{rid}', json.dumps({'Fields': [
            {'Name': 'comments', 'values': [{'value': 'memo edit'}]}], 'Type': 'requirement'}).encode())
        v2 = read_stamp('after a MEMO PUT')

        if v0 == v1 == v2:
            print('   => ver-stamp does NOT move on write. It cannot detect anything.')
        elif v1 != v0 and v2 != v1:
            print('   => ver-stamp increments on every write, including memo writes.')
        else:
            print('   => ver-stamp moves on SOME writes only - which is worse than never,')
            print('      because a token that misses the memo case cannot guard the comment path.')

        # ---- 3/4. sending it back ---------------------------------------------------------
        print('\n-- 3. sending a CURRENT ver-stamp (does ALM accept the field at all?)')
        current = read_stamp('current')
        st, t = call('PUT', f'{proj}/requirements/{rid}', json.dumps({'Fields': [
            {'Name': 'ver-stamp', 'values': [{'value': str(current)}]},
            {'Name': 'description', 'values': [{'value': 'edit with current stamp'}]},
        ], 'Type': 'requirement'}).encode())
        print(f'   PUT with current ver-stamp -> HTTP {st}  {mask(t)[:160]}')
        accepted_current = st in (200, 201)

        print('\n-- 4. sending a STALE ver-stamp (the actual question)')
        stale = '1'
        after = read_stamp('before the stale write')
        st2, t2 = call('PUT', f'{proj}/requirements/{rid}', json.dumps({'Fields': [
            {'Name': 'ver-stamp', 'values': [{'value': stale}]},
            {'Name': 'description', 'values': [{'value': 'edit with STALE stamp'}]},
        ], 'Type': 'requirement'}).encode())
        print(f'   PUT with stale ver-stamp={stale} -> HTTP {st2}  {mask(t2)[:160]}')

        st3, t3 = call('GET', f'{proj}/requirements/{rid}?fields=id,description,ver-stamp')
        landed = field_value(json.loads(t3), 'description') or ''
        print(f'   description now: {mask(landed)[:120]!r}')

        print('\n== VERDICT ==')
        if not accepted_current:
            print('   ALM does not accept ver-stamp in a write body at all, so a rejection of the')
            print('   stale one would say nothing about concurrency. No optimistic locking here.')
        elif st2 >= 400 and 'STALE' not in landed:
            print('   STALE REJECTED and current accepted: ver-stamp IS an optimistic-concurrency')
            print('   token. The comment path should send the ver-stamp it read.')
        else:
            print('   Stale accepted and the write landed: ver-stamp is a COUNTER, not a token.')
            print('   ALM offers no optimistic locking on this route, so Alt-ALM must either')
            print('   accept last-writer-wins or detect the conflict itself by re-reading and')
            print('   comparing before the PUT - which narrows the race but cannot close it.')

    finally:
        print('\n-- cleanup')
        for rid2 in reversed(created):
            st, _ = call('DELETE', f'{proj}/requirements/{rid2}')
            print(f'  DELETE requirements/{rid2} HTTP {st}')
        q = urllib.parse.quote('{name[ALTALM-PROBE*]}', safe='{}[]*')
        st, txt = call('GET', f'{proj}/requirements?query={q}&fields=id,name&page-size=50')
        orphans = 0
        if st == 200:
            for e in json.loads(txt).get('entities', []):
                oid = field_value(e, 'id')
                if oid:
                    call('DELETE', f'{proj}/requirements/{oid}')
                    orphans += 1
                    print(f'  swept requirements/{oid}')
        else:
            print(f'  !! sweep HTTP {st} - NOT a clean sweep, check by hand')
        print(f'  orphans swept: {orphans}' + ('' if orphans else '  (clean)'))
        try:
            call('DELETE', base + '/rest/site-session')
            call('POST', base + '/authentication-point/logout', b'')
        except Exception:
            pass
    return 0


if __name__ == '__main__':
    sys.exit(main())

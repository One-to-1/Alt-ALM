"""Probe 29 pass 3 - is `EntityStatus` a BULK-operation channel rather than a read channel?

Pass 1 and 2 (`probe-entity-status.py`) threw ~25 deliberately broken READS at the sandbox - fields
that do not exist, ids that do not exist, virtual and inactive fields, per-subtype fields, forbidden
collections, degenerate paging - and every single one came back either as a clean 200 whose rows all
said `EntityStatus:"Success"`, or as a REQUEST-level failure (400/403/404) with no `entities`
envelope at all. Not one row ever carried a different status, and not one row ever omitted the key.

That leaves one hypothesis standing, and it is the one the field's shape suggests: a PER-ROW status
channel exists for the operation that has per-row outcomes - a BULK write, where ALM must report
"row 1 committed, row 2 was rejected" inside a single 200.

Hypothesis: `POST {collection}` with a multi-entity body returns 200 and a collection envelope whose
rows carry INDEPENDENT `EntityStatus` values, so one bad entity alongside one good entity produces a
mixed envelope.
Refuting observation: the bulk request is rejected whole (4xx/5xx), or it commits nothing, or it
answers with every row `"Success"` regardless - any of which would mean the per-row channel is
unreachable from OUR client entirely, and both parser decisions guard a state we cannot produce.

WRITES. Sandbox only - the project in `Secrets/ALM_API_credentials.json`, designated a disposable
sandbox by the user on 2026-08-12. Every created row is named `ALTALM-PROBE-<timestamp>`, tracked,
deleted in reverse order in a `finally`, and then swept by name prefix. A 5xx is treated as
"unknown outcome, verify by query", never as "failed" (alm-api 1.2).

    python scripts/probe/probe-entity-status-bulk.py
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
FIXTURES = ROOT / 'tests' / 'fixtures' / 'entities'

PREFIX = 'ALTALM-PROBE-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
SWEEP = ['requirements']

MASK = []
created = []


def mask(s):
    out = str(s)
    for t in MASK:
        if t:
            out = re.sub(re.escape(t), 'REDACTED', out, flags=re.I)
    return out


def entity(entity_type, fields):
    """One entity body in ALM's wire shape.

    `fields` is passed as a list of pairs, never a dict built at the call site by keyword, because
    entity-write field order is load-bearing (alm-api 1.1 - wrong order yields opaque NPE-style
    500s) and only an explicitly ordered sequence makes that visible at the call site.
    """
    return {
        'Fields': [{'Name': n, 'values': [{'value': str(v)}]} for n, v in fields],
        'Type': entity_type,
    }


def xml_entities(entities):
    """ALM's documented bulk body: `<Entities>` wrapping one `<Entity>` per row.

    Built by hand rather than with a serialiser so field ORDER is exactly the order the caller
    passed - the same load-bearing constraint as the JSON path (alm-api 1.1).
    """
    def esc(v):
        return (str(v).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
                .replace('"', '&quot;'))

    parts = ['<Entities>']
    for e in entities:
        parts.append(f'<Entity Type="{esc(e["Type"])}"><Fields>')
        for f in e['Fields']:
            value = (f['values'] or [{}])[0].get('value', '')
            parts.append(f'<Field Name="{esc(f["Name"])}"><Value>{esc(value)}</Value></Field>')
        parts.append('</Fields></Entity>')
    parts.append('</Entities>')
    return ''.join(parts)


def xml_single(e):
    """One `<Entity>` with no collection wrapper - the shape probe 8 wrote runs with."""
    return xml_entities([e]).replace('<Entities>', '').replace('</Entities>', '')


def track_xml(body):
    """Pull ids out of an XML response so cleanup can reach anything that committed.

    Deliberately a regex over `<Field Name="id"><Value>N</Value>` rather than a parse: this runs in
    the path that has to work even when the response is a shape we did not expect, and that is
    exactly when a strict parser would throw and strand a row in the sandbox.
    """
    import re as _re
    for m in _re.finditer(r'Name="id"[^>]*>\s*<Value>(\d+)</Value>', body):
        rid = m.group(1)
        if not any(r == rid for _, r in created):
            created.append(('requirements', rid))


def report(label, code, body):
    """Print the per-row statuses - the whole object of the probe."""
    try:
        p = json.loads(body)
    except Exception:
        print(f'  {label:34} HTTP {code}  non-JSON: {mask(body)[:120]}')
        return None
    if not isinstance(p, dict) or 'entities' not in p:
        title = p.get('Title', '') if isinstance(p, dict) else ''
        print(f'  {label:34} HTTP {code}  no envelope  {mask(title)[:100]}')
        return p

    rows = p.get('entities') or []
    print(f'  {label:34} HTTP {code}  {len(rows)} rows')
    for i, e in enumerate(rows):
        present = 'EntityStatus' in e
        status = e.get('EntityStatus')
        err = mask(e.get('ErrorMessage') or '')[:150]
        ident = ''
        for f in e.get('Fields', []) or []:
            if f.get('Name') == 'id':
                ident = (f.get('values') or [{}])[0].get('value') or ''
        flag = '   ' if (present and status == 'Success') else '!! '
        print(f'    {flag}row {i}: present={present} status={status!r} id={ident!r} err={err!r}')
        if ident and status == 'Success':
            created.append(('requirements', ident))
    return p


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
    xsrf = ''
    for cookie in jar:
        if cookie.name == 'XSRF-TOKEN':
            xsrf = cookie.value

    def call(method, url, body=None, content_type='application/json',
             accept='application/json'):
        headers = {'Accept': accept}
        if method != 'GET':
            headers['X-XSRF-TOKEN'] = xsrf
        if body is not None:
            headers['Content-Type'] = content_type
        req = urllib.request.Request(url, data=body, headers=headers, method=method)
        try:
            with op.open(req) as resp:
                return resp.status, resp.read().decode('utf-8', 'replace')
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
    print(f'SANDBOX. Bulk writes, prefix {PREFIX}\n')

    try:
        # A real parent id. `-1` is the ROOT SENTINEL, not a row: POSTing against it returns
        # 500 "Entity with key '-1' does not exist in table 'REQ'" (probe 27).
        code, txt = call('GET', f'{proj}/requirements?page-size=1&fields=id,type-id')
        parent, type_id = None, '1'
        for f in (json.loads(txt).get('entities') or [{}])[0].get('Fields', []):
            if f.get('Name') == 'id':
                parent = (f.get('values') or [{}])[0].get('value')
            if f.get('Name') == 'type-id':
                type_id = (f.get('values') or [{}])[0].get('value') or '1'
        if not parent:
            print('the sandbox has no requirement to parent under; nothing to do')
            return 1
        print(f'  parent requirement resolved, type-id={type_id}\n')

        good_a = entity('requirement', [('name', PREFIX + '-A'), ('parent-id', parent),
                                        ('type-id', type_id)])
        good_b = entity('requirement', [('name', PREFIX + '-B'), ('parent-id', parent),
                                        ('type-id', type_id)])
        # The deliberate failure: a parent that does not exist. Chosen over a bad field name because
        # a bad NAME is a schema error ALM rejects before it ever looks at rows (pass 1 proved that),
        # whereas a bad PARENT is a per-ROW referential failure - exactly the thing a per-row status
        # channel would exist to report.
        bad = entity('requirement', [('name', PREFIX + '-BAD'), ('parent-id', '999999'),
                                     ('type-id', type_id)])

        print('-- bulk POST as JSON (does the collection URL accept a multi-entity JSON body?)')
        body = json.dumps({'entities': [good_a, good_b]}).encode()
        code, txt = call('POST', f'{proj}/requirements', body)
        report('json {entities:[...]}', code, txt)

        # ALM's documented bulk form is XML, not JSON: `<Entities>` wrapping `<Entity>` elements.
        # The JSON attempt above is kept because its failure is itself the finding - the server
        # parses a JSON body as ONE entity and NPEs on the missing top-level `Fields`, which is the
        # same opaque shape-error class as the field-order trap (alm-api 1.1).
        print('\n-- SANITY: single-entity XML POST (a shape already proved to work)')
        single = entity('requirement', [('name', PREFIX + '-XML1'), ('parent-id', parent),
                                        ('type-id', type_id)])
        code, txt = call('POST', f'{proj}/requirements', xml_single(single).encode('utf-8'),
                         content_type='application/xml', accept='application/xml')
        print(f'  {"single <Entity> xml":34} HTTP {code}  {mask(txt)[:160]}')
        track_xml(txt)
        if code in (200, 201):
            FIXTURES.mkdir(parents=True, exist_ok=True)
            (FIXTURES / 'entity-write-single.xml').write_text(mask(txt), encoding='utf-8')
            print('    captured the single-entity write envelope -> entity-write-single.xml')

        print('\n-- bulk POST as XML (the documented multi-entity form)')
        code, txt = call('POST', f'{proj}/requirements',
                         xml_entities([good_a, good_b]).encode('utf-8'),
                         content_type='application/xml', accept='application/xml')
        report('xml <Entities> 2 valid', code, txt)
        track_xml(txt)

        print('\n-- bulk POST as XML, one valid + one referentially broken')
        code, txt = call('POST', f'{proj}/requirements',
                         xml_entities([
                             entity('requirement', [('name', PREFIX + '-D'),
                                                    ('parent-id', parent),
                                                    ('type-id', type_id)]),
                             bad,
                         ]).encode('utf-8'),
                         content_type='application/xml', accept='application/xml')
        xml_mixed = report('xml 1 valid + 1 broken', code, txt)
        track_xml(txt)
        if xml_mixed is None or not isinstance(xml_mixed, dict):
            # An XML response body will not parse as JSON; show it raw so the per-entity elements
            # are visible either way. This is the payload the whole probe exists to look at.
            print('    raw response:')
            print('    ' + mask(txt)[:1200].replace(chr(10), chr(10) + '    '))

        print('\n-- bulk POST, one valid + one referentially broken')
        body = json.dumps({'entities': [
            entity('requirement', [('name', PREFIX + '-C'), ('parent-id', parent),
                                   ('type-id', type_id)]),
            bad,
        ]}).encode()
        code, txt = call('POST', f'{proj}/requirements', body)
        mixed = report('1 valid + 1 broken', code, txt)
        if mixed and any(e.get('EntityStatus') != 'Success'
                         for e in (mixed.get('entities') or [])):
            FIXTURES.mkdir(parents=True, exist_ok=True)
            out = FIXTURES / 'entity-page-entity-status-error-LIVE.json'
            out.write_text(mask(txt), encoding='utf-8')
            print(f'    captured a REAL non-Success envelope -> {out.name}')

        # The last place a non-"Success" status could live: a SINGLE write that fails. If ALM
        # answers that with an <Entity EntityStatus="Failed"> rather than a QCRestException, the
        # per-row channel is reachable after all - just on one row at a time.
        print('\n-- single write that FAILS (entity envelope, or exception envelope?)')
        broken = entity('requirement', [('name', PREFIX + '-BADPARENT'),
                                        ('parent-id', '999999'), ('type-id', type_id)])
        code, txt = call('POST', f'{proj}/requirements',
                         xml_single(broken).encode('utf-8'),
                         content_type='application/xml', accept='application/xml')
        print(f'  {"xml, bad parent":34} HTTP {code}  {mask(txt)[:220]}')
        track_xml(txt)

        code, txt = call('POST', f'{proj}/requirements',
                         json.dumps(broken).encode('utf-8'))
        print(f'  {"json, bad parent":34} HTTP {code}  {mask(txt)[:220]}')

        # A required field left out - a different failure class (validation, not referential).
        code, txt = call('POST', f'{proj}/requirements',
                         xml_single(entity('requirement', [('parent-id', parent)])).encode('utf-8'),
                         content_type='application/xml', accept='application/xml')
        print(f'  {"xml, no name/type":34} HTTP {code}  {mask(txt)[:220]}')
        track_xml(txt)

        print('\n-- bulk PUT (same question on the update path)')
        if created:
            targets = [entity('requirement', [('id', rid), ('name', PREFIX + '-renamed')])
                       for _, rid in created[:1]]
            targets.append(entity('requirement', [('id', '999999'), ('name', PREFIX + '-nope')]))
            body = json.dumps({'entities': targets}).encode()
            code, txt = call('PUT', f'{proj}/requirements', body)
            report('1 real + 1 missing id', code, txt)
        else:
            print('  nothing was created, so there is nothing to bulk-update')

        # Whatever the bulk calls reported, ask the collection what actually committed. A 5xx is
        # "unknown outcome", and so is a 200 whose per-row story we have never seen before.
        print('\n-- what actually committed')
        q = urllib.parse.quote(f'{{name[{PREFIX}*]}}', safe='{}[]*')
        code, txt = call('GET', f'{proj}/requirements?query={q}&fields=id,name&page-size=50')
        rows = json.loads(txt).get('entities', []) if code == 200 else []
        print(f'  {len(rows)} row(s) carry the probe prefix')
        for e in rows:
            rid = name = ''
            for f in e.get('Fields', []):
                if f.get('Name') == 'id':
                    rid = (f.get('values') or [{}])[0].get('value')
                if f.get('Name') == 'name':
                    name = (f.get('values') or [{}])[0].get('value')
            print(f'    id={rid} name={mask(name)}')
            if rid and not any(r == rid for _, r in created):
                created.append(('requirements', rid))

    finally:
        print('\n-- cleanup, reverse creation order')
        for col, rid in reversed(created):
            code, _ = call('DELETE', f'{proj}/{col}/{rid}')
            print(f'  DELETE {col}/{rid} HTTP {code}')

        print('-- orphan sweep')
        orphans = 0
        for col in SWEEP:
            q = urllib.parse.quote('{name[ALTALM-PROBE*]}', safe='{}[]*')
            code, txt = call('GET', f'{proj}/{col}?query={q}&fields=id,name&page-size=50')
            if code != 200:
                print(f'  {col}: sweep HTTP {code} - NOT a clean sweep, check by hand')
                continue
            for e in json.loads(txt).get('entities', []):
                for f in e.get('Fields', []):
                    if f.get('Name') == 'id':
                        oid = (f.get('values') or [{}])[0].get('value')
                        dcode, _ = call('DELETE', f'{proj}/{col}/{oid}')
                        print(f'  swept {col}/{oid} HTTP {dcode}')
                        orphans += 1
        print(f'  orphans swept: {orphans}' + ('  <-- expected 0' if orphans else '  (clean)'))

        try:
            call('DELETE', base + '/rest/site-session')
            call('POST', base + '/authentication-point/logout', b'')
        except Exception:
            pass
    return 0


if __name__ == '__main__':
    sys.exit(main())

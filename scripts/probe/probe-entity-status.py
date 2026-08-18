"""Probe 29 - can a row in ALM's collection envelope ever say anything but EntityStatus:"Success"?

Every envelope this project has ever captured - all 15 entities, every probe - sends
`EntityStatus:"Success"` explicitly on every row. Two decisions in `bff/.../alm/read/` rest entirely
on that absence, and they fail in OPPOSITE directions:

  * `AlmEntityParser` reads a MISSING `EntityStatus` as `"Success"`. If ALM omits the key on a failed
    row, we render a broken record as a healthy one with blank fields.
  * `AlmEntityPage.AlmEntity.isError()` is `!"Success".equals(...)`, so ANY other string is an error.
    If ALM ever says `"OK"` or `""` or a localised token, every row turns into an alert.

The detail pane now renders `row.error` as a visible banner, so a code path that has never seen its
real input has a UI attached to it. `tests/fixtures/entities/entity-page-entity-status-error.json` is
HAND-INVENTED and its two tests pin our guess rather than ALM's behaviour.

Hypothesis: `EntityStatus` is a per-row error channel, and a read that is partially satisfiable -
a field that does not exist, an id that does not exist, a field valid only on a sibling subtype -
produces a row carrying a non-"Success" status and a populated `ErrorMessage`.

Refuting observation: ALM answers every such read with a REQUEST-level failure (4xx/5xx) or with a
silently narrowed result set, and no row in any response ever carries a status other than "Success".
That is a real answer too: it would mean the per-row channel is vestigial on reads, and `isError()`
guards a state the read path cannot reach.

READ-ONLY. GET only. Against the SANDBOX only (the borrowed projects are no longer reachable -
user, 2026-08-18), so no other team's data is touched at all.

Reports statuses, envelope keys, and HTTP codes. Field VALUES are never printed except for
`EntityStatus`/`ErrorMessage` themselves, which are the object of study; an ErrorMessage is truncated
and masked like everything else.

    python scripts/probe/probe-entity-status.py
"""
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

MASK = []


def mask(s):
    out = str(s)
    for term in MASK:
        if term:
            out = re.sub(re.escape(term), 'REDACTED', out, flags=re.I)
    return out


def statuses(payload):
    """Every row's (present?, status, errorMessage) - the whole point of the probe.

    `present` is tracked separately from the value because the two decisions under test are about
    ABSENCE and about UNEXPECTED VALUE respectively, and a summary that collapsed them would answer
    neither.
    """
    rows = []
    for e in payload.get('entities', []) or []:
        rows.append((
            'EntityStatus' in e,
            e.get('EntityStatus'),
            (e.get('ErrorMessage') or '')[:160],
        ))
    return rows


def summarise(label, code, body):
    """One line per experiment, plus a loud line for anything that is not the known shape."""
    try:
        payload = json.loads(body)
    except Exception:
        print(f'  {label:44} HTTP {code}  non-JSON body: {mask(body)[:120]}')
        return None

    if not isinstance(payload, dict) or 'entities' not in payload:
        keys = list(payload.keys()) if isinstance(payload, dict) else type(payload).__name__
        print(f'  {label:44} HTTP {code}  no entities envelope, keys={keys}')
        return payload

    rows = statuses(payload)
    total = payload.get('TotalResults')
    if not rows:
        print(f'  {label:44} HTTP {code}  0 rows, TotalResults={total}')
        return payload

    absent = sum(1 for present, _, _ in rows if not present)
    distinct = sorted({s for _, s, _ in rows if s is not None})
    print(f'  {label:44} HTTP {code}  {len(rows)} rows, TotalResults={total}, '
          f'EntityStatus={distinct}, absent={absent}')

    for present, status, err in rows:
        if not present:
            print(f'  {"":44} !! a row has NO EntityStatus key at all')
            break
    for present, status, err in rows:
        if present and status != 'Success':
            print(f'  {"":44} !! EntityStatus={status!r} ErrorMessage={mask(err)!r}')
            break
    return payload


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
        req = urllib.request.Request(url, headers={'Accept': 'application/json'})
        try:
            with opener.open(req) as resp:
                return resp.status, resp.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            # A 4xx/5xx body is the interesting half of several of these experiments, so it is read
            # rather than allowed to propagate: "ALM refuses the whole request" IS the finding when
            # the alternative was a per-row error.
            return e.code, e.read().decode('utf-8', 'replace')

    try:
        _, who = get(base + '/v2/rest/is-authenticated')
        user = json.loads(who).get('AuthenticationInfo', {}).get('Username')
        if user:
            MASK.append(user)
    except Exception:
        pass

    proj = f"{base}/rest/domains/{c['domain']}/projects/{c['project']}"
    print('SANDBOX, GET only. Looking for any row whose EntityStatus is not "Success".\n')

    # Baseline first: without it, "every row said Success" is not evidence of anything, because a
    # collection that answered 0 rows would print the same absence.
    print('-- baseline (the shape every fixture already has)')
    code, txt = get(f'{proj}/requirements?page-size=5&fields=id,name')
    summarise('requirements, ordinary read', code, txt)

    print('\n-- experiment 1: a field that does not exist')
    for label, q in [
        ('fields=id,no-such-field', 'page-size=5&fields=id,no-such-field'),
        ('fields=no-such-field alone', 'page-size=5&fields=no-such-field'),
        # `steps` belongs to a design-step, not a requirement: a real ALM field name, wrong entity.
        ('fields=id,step-order (other entity)', 'page-size=5&fields=id,step-order'),
        ('fields= empty', 'page-size=5&fields='),
    ]:
        code, txt = get(f'{proj}/requirements?{q}')
        summarise(label, code, txt)

    print('\n-- experiment 2: ids that do not exist')
    for label, q in [
        ('query={id[999999]}', 'query={id[999999]}&fields=id,name'),
        ('query={id[1 OR 999999]}', 'query={id[1 Or 999999]}&fields=id,name'),
        ('query={id[">999999"]}', 'query={id[>999999]}&fields=id,name'),
    ]:
        code, txt = get(f'{proj}/requirements?{urllib.parse.quote(q, safe="=&{}[]<>*")}')
        summarise(label, code, txt)

    # A single-entity GET is a different endpoint with a different envelope; worth one line, because
    # if the per-row channel exists anywhere it might exist there.
    code, txt = get(f'{proj}/requirements/999999?fields=id,name')
    print(f'  {"GET requirements/999999":44} HTTP {code}  {mask(txt)[:150]}')

    print('\n-- experiment 3: a field valid only on a sibling subtype')
    # Requirements are polymorphic (type-id). A field defined on one subtype and not another is the
    # textbook case for a PER-ROW rather than per-request error, so it is the likeliest producer.
    code, txt = get(f'{proj}/customization/entities/requirement/fields')
    subtype_fields = []
    if code == 200:
        try:
            for f in json.loads(txt).get('fields', []):
                if f.get('SubType') or f.get('subtype'):
                    subtype_fields.append(f.get('Name') or f.get('name'))
        except Exception:
            pass
    print(f'  {"subtype-scoped fields discovered":44} {len(subtype_fields)}')
    for name in subtype_fields[:4]:
        code, txt = get(f'{proj}/requirements?page-size=5&fields=id,{name}')
        summarise(f'fields=id,{name}', code, txt)

    print('\n-- experiment 4: degenerate paging (probe 15 found page-size=0 lies)')
    for label, q in [
        ('page-size=0', 'page-size=0&fields=id,name'),
        ('start-index past the end', 'start-index=99999&page-size=5&fields=id,name'),
    ]:
        code, txt = get(f'{proj}/requirements?{q}')
        summarise(label, code, txt)

    print('\n-- experiment 5: other collections, ordinary reads')
    for col in ('tests', 'test-sets', 'defects', 'releases', 'test-instances'):
        code, txt = get(f'{proj}/{col}?page-size=3&fields=id')
        summarise(col, code, txt)

    print('\nIf every line above says EntityStatus=[\'Success\'] with absent=0, the per-row channel')
    print('is unreachable from the read path and BOTH decisions guard a state ALM does not produce.')
    print('That is the finding to write down - not "we could not make it fail".')

    try:
        opener.open(urllib.request.Request(base + '/authentication-point/logout', data=b''))
    except Exception:
        pass
    return 0


if __name__ == '__main__':
    sys.exit(main())

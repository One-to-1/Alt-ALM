"""Probe 22 - capture `customization/entities/{e}/relations/` as offline fixtures.

Probe 21.6 established that this endpoint enumerates the stock client's related-entity tabs, each
relation carrying a human-readable `Label` and a target entity. It read them live and wrote down
counts; it did not save the payload, so `AlmRelationParser` has nothing to be built against.

This probe fixes that. It captures the relations document for requirement / test / defect from the
SANDBOX ONLY and writes masked fixtures under tests/fixtures/.

Why the sandbox only: a relations document is SCHEMA, not data - no record names, no owners, no
field values. But it is still per-project customization, and the borrowed-project rule is "counts
and shapes only, never their content". A schema captured from someone else's project is their
content. Ours is not, so ours is what gets committed.

READ-ONLY. GET only. Masks host/domain/project/key/secret/user in every byte written.

Hypothesis: the payload is a JSON object wrapping a list of relations, each with at least a name,
a `Label`, a target entity, and a `Features` array.
Refuting observation: no `Label` on some relation, or a shape that varies per entity.

    python scripts/probe/probe-relations.py
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
FIXTURES = ROOT / 'tests' / 'fixtures'

ENTITIES = ['requirement', 'test', 'defect']

MASK = []


def mask(s):
    out = str(s)
    for term in MASK:
        if term:
            out = re.sub(re.escape(term), 'REDACTED', out, flags=re.I)
    return out


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
    req = urllib.request.Request(base + '/rest/oauth2/login', data=body,
                                 headers={'Content-Type': 'application/json'})
    opener.open(req).read()

    def get(url):
        r = urllib.request.Request(url, headers={'Accept': 'application/json'})
        with opener.open(r) as resp:
            return resp.status, resp.read().decode('utf-8', 'replace')

    # Resolve our own identity into the mask before anything can echo it.
    try:
        _, who = get(base + '/v2/rest/is-authenticated')
        user = json.loads(who).get('AuthenticationInfo', {}).get('Username')
        if user:
            MASK.append(user)
    except Exception:
        pass

    proj = f"{base}/rest/domains/{c['domain']}/projects/{c['project']}"
    print('capturing relations from the SANDBOX only, GET only\n')

    for entity in ENTITIES:
        url = f'{proj}/customization/entities/{entity}/relations/'
        try:
            status, txt = get(url)
        except urllib.error.HTTPError as e:
            print(f'  {entity:12} HTTP {e.code}')
            continue
        if status != 200:
            print(f'  {entity:12} HTTP {status}')
            continue

        masked = mask(txt)
        # Verify the mask actually fired before writing anything to a tracked directory.
        for term in MASK:
            if term and re.search(re.escape(term), masked, flags=re.I):
                print(f'  {entity:12} ABORT - an unmasked term survived; nothing written')
                return 2

        out = FIXTURES / f'customization-relations-{entity}.json'
        parsed = json.loads(masked)
        out.write_text(json.dumps(parsed, indent=2) + '\n', encoding='utf-8')

        rels = parsed
        while isinstance(rels, dict):
            rels = next(iter(rels.values()))
        labels = [r.get('Label') or r.get('label') for r in rels]
        missing = sum(1 for lb in labels if not lb)
        print(f'  {entity:12} HTTP 200  {len(rels):3} relations, '
              f'{missing} without a Label  -> {out.relative_to(ROOT)}')
        if entity == 'requirement':
            print(f'    top-level keys: {list(parsed.keys()) if isinstance(parsed, dict) else "list"}')
            print(f'    first relation: {json.dumps(rels[0], indent=2)[:600]}')

    try:
        opener.open(base + '/authentication-point/logout').read()
    except Exception:
        pass
    return 0


if __name__ == '__main__':
    sys.exit(main())

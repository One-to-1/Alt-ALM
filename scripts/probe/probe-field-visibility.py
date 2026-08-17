"""Probe 21 - what does the API say about which fields and tabs ALM renders?

Hypothesis (from the captured sandbox fixture, offline):
  * the Details form  = fields where active AND visibleInWebUI
  * the Risk Analysis tab = fields where active AND NOT visibleInWebUI (all rbt-*)
  * the memo TABS     = Memo fields where active AND visibleInWebUI
  * `visible` is useless - true for all 74 requirement fields

Refuting observation: any project where those sets are implausible for a form (e.g. 60 fields),
or where a Memo field ALM does not tab is active+visibleInWebUI.

Also asks whether the related-entity tabs (Attachments, History, Linked Defects, Requirement
Traceability, Test Coverage, Business Models Linkage) are enumerable from an entity instance's
`resource-list`.

READ-ONLY. GET only, every project. Masks host/domain/project/key/secret/user in all output, and
prints field NAMES and flags only - never a field VALUE from a borrowed project.

    python scripts/probe/probe-field-visibility.py
"""
import json
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import http.cookiejar

ROOT = pathlib.Path(__file__).resolve().parents[2]
SECRETS = ROOT / 'Secrets' / 'ALM_API_credentials.json'
READ_PROJECTS = ROOT / 'Secrets' / 'alm-read-projects.json'

MASK = []


def mask(s):
    out = str(s)
    for term in MASK:
        if term:
            out = re.sub(re.escape(term), 'REDACTED', out, flags=re.I)
    return out


def pseudo(domain, project):
    import hashlib
    return 'PROJECT-' + hashlib.sha256(f'{domain}/{project}'.encode()).hexdigest()[:4]


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

    def get(url, accept='application/json'):
        r = urllib.request.Request(url, headers={'Accept': accept})
        with opener.open(r) as resp:
            return resp.status, resp.read().decode('utf-8', 'replace')

    # Whose identity are we? Add it to the mask before anything can echo it.
    try:
        _, who = get(base + '/v2/rest/is-authenticated')
        user = json.loads(who).get('AuthenticationInfo', {}).get('Username')
        if user:
            MASK.append(user)
    except Exception:
        pass

    # Secrets/alm-read-projects.json is {note, domain, sandbox, projects:[{name, alias, access,
    # totalRows}]} — one shared domain, projects carry `name`. Aliases are the pseudonyms probe 16
    # assigned; prefer them over our own hash so the two documents agree.
    targets = [(c['domain'], c['project'], None)]
    if READ_PROJECTS.exists():
        data = json.loads(READ_PROJECTS.read_text(encoding='utf-8'))
        shared_domain = data.get('domain') or c['domain']
        MASK.append(str(shared_domain))
        for row in data.get('projects', []):
            name = row.get('name')
            if not name or name == c['project']:
                continue
            MASK.append(str(name))
            targets.append((shared_domain, name, row.get('alias')))

    print(f'probing {len(targets)} projects, requirement fields, GET only\n')
    print(f'{"project":16} {"total":>6} {"visible":>8} {"web":>5} {"active":>7} '
          f'{"act+web":>8} {"act-not-web":>12} {"memo(act+web)":>14}')

    per_project = {}
    for domain, project, alias in targets:
        proj = f'{base}/rest/domains/{domain}/projects/{project}'
        try:
            status, txt = get(proj + '/customization/entities/requirement/fields')
        except urllib.error.HTTPError as e:
            print(f'{alias or pseudo(domain, project):16} HTTP {e.code}')
            continue
        if status != 200:
            print(f'{alias or pseudo(domain, project):16} HTTP {status}')
            continue

        raw = json.loads(txt)
        fields = raw
        while isinstance(fields, dict):
            fields = next(iter(fields.values()))

        total = len(fields)
        vis = sum(1 for f in fields if f.get('visible'))
        web = sum(1 for f in fields if f.get('visibleInWebUI'))
        act = sum(1 for f in fields if f.get('active'))
        aw = [f for f in fields if f.get('active') and f.get('visibleInWebUI')]
        anw = [f for f in fields if f.get('active') and not f.get('visibleInWebUI')]
        memo_aw = [f for f in aw if f.get('type') == 'Memo']

        print(f'{alias or pseudo(domain, project):16} {total:6} {vis:8} {web:5} {act:7} '
              f'{len(aw):8} {len(anw):12} {len(memo_aw):14}')
        per_project[alias or pseudo(domain, project)] = {
            'form': [f['name'] for f in aw if f.get('type') != 'Memo'],
            'memo_tabs': [(f['name'], f['label']) for f in memo_aw],
            'not_web_prefixes': sorted({f['name'].split('-')[0] for f in anw}),
        }

    print('\n-- memo fields that are active AND visibleInWebUI (the tab candidates) --')
    for name, info in per_project.items():
        labels = ', '.join(lbl for _, lbl in info['memo_tabs'])
        print(f'  {name}: {labels or "(none)"}')

    print('\n-- name prefixes of active-but-NOT-visibleInWebUI fields --')
    for name, info in per_project.items():
        print(f'  {name}: {info["not_web_prefixes"]}')

    # Are the related-entity tabs enumerable from an instance's resource-list?
    print('\n-- per-instance resource-list (related-entity tab candidates) --')
    domain, project, alias = targets[-1]
    proj = f'{base}/rest/domains/{domain}/projects/{project}'
    try:
        _, txt = get(proj + '/requirements?page-size=1&fields=id')
        entities = json.loads(txt).get('entities', [])
        if entities:
            rid = [f for f in entities[0]['Fields'] if f['Name'] == 'id'][0]['values'][0]['value']
            status, rl = get(f'{proj}/requirements/{rid}')
            data = json.loads(rl)
            rels = data.get('resource-list') or data.get('ResourceList') or {}
            print(f'  requirement instance resource-list keys: {list(data.keys())}')
            print(f'  {mask(json.dumps(rels))[:1200]}')
        else:
            print('  no requirements to inspect')
    except Exception as e:
        print(f'  resource-list read failed: {mask(e)}')

    try:
        urllib.request.Request(base + '/authentication-point/logout')
        opener.open(base + '/authentication-point/logout').read()
    except Exception:
        pass
    return 0


if __name__ == '__main__':
    sys.exit(main())

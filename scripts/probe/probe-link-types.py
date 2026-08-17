"""Probe 23 - is a link's id column enough to filter it, or is the TYPE column load-bearing?

Probe 22 showed each relation's StorageDescriptor names the column to filter on:
  ReferenceStorage   -> query TargetEntity where {ReferenceIdColumn}[parentId]
  AssociationStorage -> query AssociationEntity where {AssociationSourceIdColumn}[parentId]

But several carry a second column - `ReferenceTypeColumn` / `AssociationSourceTypeColumn`
(`second-endpoint-type`, `parent-type`) - and `defect-link` is polymorphic: a requirement, a run, a
test and a test-set all link to defects through the same table. If those endpoint ids share one
number space per entity, then filtering `defect-links` by `second-endpoint-id[605]` alone would
also match a TEST numbered 605 and show its defects on a requirement's tab.

Hypothesis: the type column is REQUIRED to disambiguate, and holds a short ALM type code.
Refuting observation: the type column is absent/null on real rows, or holds one constant value.

READ-ONLY. GET only. Reads a borrowed project because the sandbox has no links to look at; reports
SHAPES ONLY - column names, distinct type codes, counts. No record names, no field values, no ids
beyond a count. Masks host/domain/project/key/secret/user.

    python scripts/probe/probe-link-types.py
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

# The link tables probe 22 found relations pointing at, plus their endpoint columns.
LINK_TABLES = [
    ('defect-links', ['first-endpoint-id', 'first-endpoint-type',
                      'second-endpoint-id', 'second-endpoint-type']),
    ('req-traces', ['from-req-id', 'to-req-id']),
    ('requirement-coverages', ['requirement-id', 'test-id']),
]

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

    # The read target is a borrowed project: GET only, shapes only.
    if not READ_PROJECTS.exists():
        print('no read-project list; cannot reach a project with links')
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
    print('reading PROJECT-5, GET only, reporting shapes only\n')

    for collection, columns in LINK_TABLES:
        url = f'{proj}/{collection}?page-size=200&fields=' + ','.join(columns)
        try:
            status, txt = get(url)
        except urllib.error.HTTPError as e:
            print(f'  {collection:24} HTTP {e.code}')
            continue
        if status != 200:
            print(f'  {collection:24} HTTP {status}')
            continue

        entities = json.loads(txt).get('entities', [])
        print(f'  {collection:24} {len(entities):4} rows')
        if not entities:
            continue

        seen = collections.defaultdict(collections.Counter)
        for ent in entities:
            for f in ent.get('Fields', []):
                vals = [v.get('value') for v in (f.get('values') or [])]
                name = f.get('Name')
                if name and name.endswith('-type'):
                    # A type CODE is schema, not their data — safe and necessary to report.
                    for v in vals:
                        seen[name][v] += 1
                elif name:
                    # Ids are their data: count presence only, never the value.
                    seen[name]['<id present>' if vals and vals[0] else '<null>'] += 1
        for col, counter in seen.items():
            print(f'      {col:24} {dict(counter)}')

    try:
        opener.open(base + '/authentication-point/logout').read()
    except Exception:
        pass
    return 0


if __name__ == '__main__':
    sys.exit(main())

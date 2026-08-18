"""Probe 30 - P2's phase-start deferred probe: how does a comment field actually behave?

The plan defers this to the start of P2 (`implementation-plan.md`, "Deferred probe executed at phase
start"): decide whether Alt-ALM's comment writes should append with a banner/timestamp convention
matching the stock client, BEFORE any comment-write UX is built.

There are really four questions, and only the last is a matter of taste:

  1. Which field is "comments", per entity, and what TYPE is it? (`dev-comments` on a defect is the
     canonical one; a requirement's is `comments`. Both are believed Memo.)
  2. Does the SERVER do anything on write - prepend a banner, stamp a user, append rather than
     replace? REST writes bypass workflow scripts (`CLIENT_TYPES_BYPASS_REST_WF`), so the expectation
     is a plain overwrite, but "expected" is not "verified" and this is the exact class of thing
     CLAUDE.md forbids inventing.
  3. Is it therefore DESTRUCTIVE? If PUT replaces the whole memo, a naive "add a comment" UI that
     sends only the new text silently deletes every previous comment. That is the finding that
     actually matters, and it is a data-loss bug, not a formatting preference.
  4. Only then: what banner convention should WE emit?

Hypothesis: the field is an ordinary Memo, a PUT replaces it wholesale, the server adds nothing, and
the stock client's banner is purely a client-side convention Alt-ALM must reproduce itself.
Refuting observation: the server appends rather than replaces, or returns a value containing a
banner, stamp, or separator we did not send.

⚠️ **What this probe CANNOT answer, and must not pretend to.** The stock client's exact banner
format can only be read off a record a HUMAN wrote a comment on through the stock UI. The sandbox has
none, and the borrowed projects that did are no longer reachable (user, 2026-08-18). So the format
below is reconstructed from what we can observe plus what the field permits - it is labelled
UNVERIFIED and stays that way until someone opens ALM's own client and adds a comment.

WRITES. Sandbox only. `ALTALM-PROBE-<timestamp>` names, cleanup in `finally`, orphan sweep.

    python scripts/probe/probe-comments.py
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

# Where a "comments" field is expected to live, per entity. Discovered against metadata rather than
# assumed - a field name that is right for a defect is not automatically right for a requirement.
CANDIDATES = ['comments', 'dev-comments', 'user-comments', 'remarks']

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

    def call(method, url, body=None, content_type='application/json'):
        headers = {'Accept': 'application/json'}
        if method != 'GET':
            headers['X-XSRF-TOKEN'] = xsrf
        if body is not None:
            headers['Content-Type'] = content_type
        try:
            with op.open(urllib.request.Request(url, data=body, headers=headers,
                                                method=method)) as r:
                return r.status, r.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode('utf-8', 'replace')

    try:
        _, who = call('GET', base + '/v2/rest/is-authenticated')
        user = json.loads(who).get('AuthenticationInfo', {}).get('Username')
        if user:
            MASK.append(user)
    except Exception:
        user = None

    proj = f"{base}/rest/domains/{c['domain']}/projects/{c['project']}"
    print(f'SANDBOX. prefix {PREFIX}\n')

    try:
        # ---- 1. which field, and what type -------------------------------------------------
        print('-- 1. the comment field, per entity, from METADATA (not assumed)')
        found = {}
        for ent in ('requirement', 'defect', 'test', 'run'):
            code, txt = call('GET', f'{proj}/customization/entities/{ent}/fields')
            if code != 200:
                print(f'  {ent:12} metadata HTTP {code}')
                continue
            fields = json.loads(txt).get('Fields', {}).get('Field', [])
            hits = [f for f in fields if f.get('name') in CANDIDATES]
            for f in hits:
                print(f'  {ent:12} {f["name"]:16} type={f.get("type"):10} '
                      f'required={f.get("required")} editable={f.get("editable")} '
                      f'active={f.get("active")} physical={f.get("physicalName")}')
            if hits:
                found[ent] = hits[0]['name']
            else:
                print(f'  {ent:12} none of {CANDIDATES}')

        if 'requirement' not in found:
            print('\nno comment field on requirement; nothing further to test')
            return 1
        comment_field = found['requirement']

        # ---- 2/3. does a PUT replace or append? -------------------------------------------
        print(f'\n-- 2. write behaviour of requirement.{comment_field}')
        code, txt = call('GET', f'{proj}/requirements?page-size=1&fields=id,type-id')
        first = (json.loads(txt).get('entities') or [{}])[0]
        parent = field_value(first, 'id')
        type_id = field_value(first, 'type-id') or '1'

        body = json.dumps({'Fields': [
            {'Name': 'name', 'values': [{'value': PREFIX + '-COMMENT'}]},
            {'Name': 'parent-id', 'values': [{'value': parent}]},
            {'Name': 'type-id', 'values': [{'value': type_id}]},
        ], 'Type': 'requirement'}).encode()
        code, txt = call('POST', f'{proj}/requirements', body)
        if code not in (200, 201):
            print(f'  create HTTP {code}: {mask(txt)[:200]}')
            return 1
        rid = field_value(json.loads(txt), 'id')
        created.append(('requirements', rid))
        print(f'  created requirement (id withheld from output? no - it is ours): {rid}')

        def put_comment(text, label):
            b = json.dumps({'Fields': [
                {'Name': comment_field, 'values': [{'value': text}]},
            ], 'Type': 'requirement'}).encode()
            st, resp = call('PUT', f'{proj}/requirements/{rid}', b)
            got = field_value(json.loads(resp), comment_field) if st in (200, 201) else None
            print(f'  {label:28} PUT HTTP {st}')
            return st, got

        _, first_value = put_comment('FIRST comment from the probe.', 'write #1')
        print(f'    stored: {mask(str(first_value))[:220]!r}')

        _, second_value = put_comment('SECOND comment from the probe.', 'write #2')
        print(f'    stored: {mask(str(second_value))[:220]!r}')

        # The question the whole probe exists for.
        if second_value and 'FIRST' in second_value:
            print('  => APPENDS: the server preserved the earlier comment')
        else:
            print('  => !! REPLACES: write #2 destroyed write #1.')
            print('     An "add a comment" UI that sends only the new text DELETES the history.')
            print('     Alt-ALM must read-modify-write: fetch current, prepend/append, PUT the whole.')

        # ---- did the server decorate anything? --------------------------------------------
        print('\n-- 3. did the server add a banner, stamp, or user of its own?')
        sent = 'SECOND comment from the probe.'
        stored = second_value or ''
        added = stored.replace(sent, '') if sent in stored else stored
        print(f'    sent   : {sent!r}')
        print(f'    stored : {mask(stored)[:300]!r}')
        print(f'    delta  : {mask(added)[:300]!r}')
        if user and user in stored:
            print('    => the server stamped the USERNAME into the value')
        if re.search(r'\d{1,4}[/-]\d{1,2}[/-]\d{1,4}', stored):
            print('    => the value contains something date-shaped')
        if added.strip() in ('', '<html><body></body></html>'):
            print('    => no banner, no stamp: the server stores what it is given (modulo the memo')
            print('       HTML wrapper from probe 27). The banner is OURS to write.')

        # ---- 4. read-modify-write, the shape P2 will actually ship -------------------------
        print('\n-- 4. read-modify-write with a stock-shaped banner (UNVERIFIED format)')
        stamp = datetime.datetime.now().strftime('%d/%m/%Y')
        banner = (f'<b>________________________________________</b><br>'
                  f'<b>ALTALM-PROBE-USER &lt;probe&gt;, {stamp}:</b><br>')
        code, txt = call('GET', f'{proj}/requirements/{rid}?fields=' + comment_field)
        existing = field_value(json.loads(txt), comment_field) or ''
        merged = existing + banner + 'THIRD comment, appended client-side.'
        st, got = put_comment(merged, 'write #3 (merged)')
        kept = sum(1 for m in ('FIRST', 'SECOND', 'THIRD') if got and m in got)
        print(f'    comments still present after the merge: {kept}/3')
        print(f'    stored : {mask(str(got))[:400]!r}')

    finally:
        print('\n-- cleanup')
        for col, rid2 in reversed(created):
            st, _ = call('DELETE', f'{proj}/{col}/{rid2}')
            print(f'  DELETE {col}/{rid2} HTTP {st}')
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

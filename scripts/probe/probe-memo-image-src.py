"""Probe 37 - what does ALM actually STORE as a memo image's `src`?

Alt-ALM's memo-image path rests on one assumption: that the `<img src>` ALM keeps in a memo ends in
`/attachments/<filename>`, so the filename can be read off the URL and matched against the record's
attachment list. Nothing verified that. `attachmentNameOf` in `spa/src/detail/richText.ts` parses
exactly that shape, and if ALM rewrites the URL to something else the feature does not break loudly
- every image silently stays a placeholder, which looks identical to "this record has no images".

Questions:

  1. Does an absolute REST URL as `<img src>` survive a memo write at all, unchanged?
  2. If it is rewritten, into what?
  3. Does the last path segment match the name the attachments list reports?
  4. What happens to a RELATIVE src - api-ref says it is silently stripped. Confirm, because a
     stripped src is a memo whose image can never be resolved by anyone.

Refuting observation for (1)/(3): any stored src whose last path segment is not the attachment's
name as the list reports it.

WRITES. Sandbox only. Reuses the requirement probe 35 left behind rather than creating one - it
already has an image attachment, and an attachment is a sub-resource of the record it hangs off, so
the memo has to live on the same record. Records are KEPT (user, 2026-08-20); the before/after diff
attributes the run. Output is ASCII-only.

    python scripts/probe/probe-memo-image-src.py
"""
import datetime
import http.cookiejar
import json
import pathlib
import re
import urllib.error
import urllib.parse
import urllib.request

import probe_state

ROOT = pathlib.Path(__file__).resolve().parents[2]
SECRETS = ROOT / 'Secrets' / 'ALM_API_credentials.json'
PREFIX = 'ALTALM-PROBE-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S')

WATCHED = ('requirements',)
MASK = []


def mask(s):
    out = str(s)
    for t in MASK:
        if t:
            out = re.sub(re.escape(t), 'REDACTED', out, flags=re.I)
    return out


def first_value(entity, name):
    for f in entity.get('Fields', []) or []:
        if f.get('Name') == name:
            vals = f.get('values') or []
            return vals[0].get('value') if vals else None
    return None


def read_memo(call, proj, rid):
    """One record's description, read through the COLLECTION form.

    WARNING: this is the whole reason probe 37 was nearly written down as a false negative. The obvious
    read, `GET requirements/{id}`, returns a bare Entity object - NOT an {entities:[...]} envelope.
    A parser written for the envelope finds no `entities` key, falls back to an empty dict, and
    reports every field as absent. That made a perfectly successful memo write look like a memo
    that came back EMPTY, which reads as "ALM stripped everything" rather than as "the probe read
    the wrong shape". HTTP 200 throughout.

    Six variants all reported stripped before the read was fixed; all six survive with their `src`
    intact afterwards. Same failure mode as the standing lesson in SESSION-STATE.md: not too few
    attempts, an unexamined assumption about the shape of the question.
    """
    st, txt = call('GET', f'{proj}/requirements?query='
                   + urllib.parse.quote('{id[' + str(rid) + ']}')
                   + '&fields=id,description&page-size=1')
    if st != 200:
        return ''
    entities = json.loads(txt).get('entities') or []
    return (first_value(entities[0], 'description') or '') if entities else ''


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

    def call(method, url, body=None, ctype='application/json'):
        h = {'Accept': 'application/json'}
        if method != 'GET':
            h['X-XSRF-TOKEN'] = xsrf
        if body is not None:
            h['Content-Type'] = ctype
        try:
            with op.open(urllib.request.Request(url, data=body, headers=h, method=method)) as r:
                return r.status, r.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode('utf-8', 'replace')

    proj = f"{base}/rest/domains/{c['domain']}/projects/{c['project']}"
    print(f'SANDBOX. prefix {PREFIX}')
    before = probe_state.snapshot(call, proj, WATCHED)
    print('   before: ' + ', '.join(f'{k}={len(before[k])}' for k in WATCHED) + '\n')

    try:
        # ---- find the record probe 35 left, with its image attachment ---------------------
        st, txt = call('GET', f'{proj}/requirements?fields=id,name&page-size=200')
        subject = None
        for e in (json.loads(txt).get('entities') or []):
            rid = first_value(e, 'id')
            st2, atx = call('GET', f'{proj}/requirements/{rid}/attachments?fields=id,name')
            if st2 != 200:
                continue
            atts = json.loads(atx).get('entities') or []
            named = [(first_value(a, 'id'), first_value(a, 'name')) for a in atts]
            named = [(i, n) for i, n in named if n and n.lower().endswith('.png')]
            if named:
                subject = (rid, named[0])
                break

        if subject is None:
            print('No requirement with a .png attachment in the sandbox - run probe 35 first.')
            return 1

        rid, (att_id, att_name) = subject
        print(f'-- subject: requirement {rid}, attachment {att_id}')
        print(f'   attachment name as the LIST reports it: {att_name!r}')

        # ---- Q1: an absolute REST url as an <img src> --------------------------------------
        # The exact form api-ref documents. Percent-encoded, because the name may contain
        # characters a URL cannot carry raw.
        absolute = (f'{proj}/requirements/{rid}/attachments/'
                    + urllib.parse.quote(att_name))
        memo = (f'<html><body><p>{PREFIX}</p>'
                f'<img src="{absolute}" alt="probe37"/></body></html>')
        body = json.dumps({'Fields': [
            {'Name': 'description', 'values': [{'value': memo}]},
        ], 'Type': 'requirement'}).encode()

        st, txt = call('PUT', f'{proj}/requirements/{rid}', body)
        print(f'\n-- 1. memo write with an absolute REST src: HTTP {st}')
        if st not in (200, 201):
            print(f'   {mask(txt)[:300]}')
            return 1

        stored = read_memo(call, proj, rid)
        srcs = re.findall(r'<img[^>]*\ssrc="([^"]*)"', stored, re.I)
        print(f'   stored <img> count: {len(srcs)}')
        for src in srcs:
            print(f'   stored src: {mask(src)}')

        # ---- Q3: does the last segment match the list's name? ------------------------------
        print('\n-- 2. does the src end in the attachment name?')
        if not srcs:
            print('   *** NO SRC SURVIVED. The memo-image path cannot work as designed.')
        else:
            src = srcs[0]
            path = src.split('?')[0].split('#')[0]
            marker = path.lower().rfind('/attachments/')
            tail = path[marker + len('/attachments/'):] if marker >= 0 else ''
            decoded = urllib.parse.unquote(tail) if tail else ''
            print(f'   last segment after /attachments/: {tail!r}')
            print(f'   percent-decoded:                  {decoded!r}')
            print(f'   matches the list name: {decoded == att_name}')
            if decoded != att_name:
                print('   *** The SPA parser keys on this. A mismatch means placeholders forever.')

        # ---- Q4: a relative src -------------------------------------------------------------
        rel_memo = (f'<html><body><p>{PREFIX} relative</p>'
                    f'<img src="{urllib.parse.quote(att_name)}" alt="rel"/></body></html>')
        body = json.dumps({'Fields': [
            {'Name': 'description', 'values': [{'value': rel_memo}]},
        ], 'Type': 'requirement'}).encode()
        st, _ = call('PUT', f'{proj}/requirements/{rid}', body)
        stored_rel = read_memo(call, proj, rid)
        rel_srcs = re.findall(r'<img[^>]*\ssrc="([^"]*)"', stored_rel, re.I)
        print(f'\n-- 3. a RELATIVE src: write HTTP {st}, stored srcs: {len(rel_srcs)}')
        print(f'   img element survived at all: {"<img" in stored_rel.lower()}')
        for src in rel_srcs:
            print(f'   stored src: {mask(src)}')
        if '<img' in stored_rel.lower() and not rel_srcs:
            print('   -> element kept, src stripped: confirms api-ref. Unresolvable by anyone.')

        # ---- restore the absolute form, so the record is left useful ------------------------
        # WARNING: NOT cleanup-by-deletion (retired 2026-08-20). This leaves the record in the state
        # that makes it a reusable fixture for the SPA's memo-image path.
        body = json.dumps({'Fields': [
            {'Name': 'description', 'values': [{'value': memo}]},
        ], 'Type': 'requirement'}).encode()
        call('PUT', f'{proj}/requirements/{rid}', body)
        print('\n-- restored the absolute-src memo, so this record stays a usable fixture')

    finally:
        print('\n-- what this run changed')
        after = probe_state.snapshot(call, proj, WATCHED)
        report = probe_state.diff(before, after)
        probe_state.print_diff(report, before, after, mask)
        # One record edited three times; no rows added or removed.
        for line in probe_state.expect(report, {'requirements': {'modified': 1}}):
            print(f'   *** UNEXPECTED: {line}')

        call('DELETE', base + '/rest/site-session')
        call('POST', base + '/authentication-point/logout')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

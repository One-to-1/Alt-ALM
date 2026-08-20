"""Probe 35 - can Alt-ALM READ an attachment back, and in what shape?

The write side is settled: multipart with the `file` part last, or octet-stream + `Slug`, and
`ref-subtype=1` for rich content (probes 5-6, api-ref 6.6). The READ side is not, and it is the half
the UI actually needs - every image in a memo currently renders as a placeholder saying "Alt-ALM
cannot fetch attachments", which is true only because nobody has established how.

Questions, in the order that makes them mean something:

  1. What does a GET on the attachments COLLECTION return - an entity envelope like every other
     collection, or something else? Which fields identify a file (name? id? size? mime type?).
  2. What does a GET on a MEMBER return - the bytes, or an entity describing them?
  3. Does `?by-id=true` work, and does it matter? A filename in a URL is a different risk from a
     numeric id: names can collide, contain slashes, and need escaping.
  4. Does the response carry a usable `Content-Type`, or must Alt-ALM infer one from the name?
  5. Do the bytes round-trip EXACTLY? A memo is re-serialised on the way in (probe 27); an
     attachment must not be, and "probably binary-safe" is not good enough to build an <img> on.

Refuting observation for (5): any difference between the bytes sent and the bytes read back.

⚠️ Judged on the BYTES and the HEADERS, never on the status. A 200 that returns an HTML error page
is a shape this API produces when the Accept header is wrong (api-ref) - it would look like success
to anything that only checked the code.

WRITES. Sandbox only. ALTALM-PROBE-<timestamp> names. Records are KEPT (user, 2026-08-20); the
before/after diff attributes the run. Output is ASCII-only.

    python scripts/probe/probe-attachment-read.py
"""
import datetime
import hashlib
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
created = []

# A tiny but REAL png - 1x1, valid header and IEND. A text file would not exercise the question,
# because the risk being tested is that something re-encodes or line-ending-mangles binary content.
PNG = bytes.fromhex(
    '89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4'
    '890000000a49444154789c6360000002000100ffff03000006000557bfabd400'
    '00000049454e44ae426082')


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

    def call(method, url, body=None, ctype=None, accept='application/json'):
        """Returns (status, headers, raw bytes). Bytes, not text - this probe is about binary."""
        h = {'Accept': accept}
        if method != 'GET':
            h['X-XSRF-TOKEN'] = xsrf
        if ctype:
            h['Content-Type'] = ctype
        try:
            with op.open(urllib.request.Request(url, data=body, headers=h, method=method)) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    # probe_state wants (method, url) -> (status, text).
    def call_text(method, url, body=None):
        st, _, raw = call(method, url, body)
        return st, raw.decode('utf-8', 'replace')

    proj = f"{base}/rest/domains/{c['domain']}/projects/{c['project']}"
    print(f'SANDBOX. prefix {PREFIX}')
    before = probe_state.snapshot(call_text, proj, WATCHED)
    print('   before: ' + ', '.join(f'{k}={len(before[k])}' for k in WATCHED) + '\n')

    try:
        # ---- a record to hang the attachment on ------------------------------------------
        st, txt = call_text('GET', f'{proj}/requirements?page-size=1&fields=id,type-id')
        first = (json.loads(txt).get('entities') or [{}])[0]
        body = json.dumps({'Fields': [
            {'Name': 'name', 'values': [{'value': PREFIX + '-ATT'}]},
            {'Name': 'parent-id', 'values': [{'value': first_value(first, 'id')}]},
            {'Name': 'type-id', 'values': [{'value': first_value(first, 'type-id') or '1'}]},
        ], 'Type': 'requirement'}).encode()
        st, _, raw = call('POST', f'{proj}/requirements', body, 'application/json')
        if st not in (200, 201):
            print(f'   create HTTP {st}: {mask(raw.decode("utf-8", "replace"))[:200]}')
            return 1
        rid = first_value(json.loads(raw), 'id')
        created.append(('requirements', rid))
        print(f'-- subject requirement: {rid}')

        # ---- upload, on the verified recipe ----------------------------------------------
        # ⚠️ Hand-built: PS7's -Form produced a body this server rejects, and the `file` part MUST
        # be last (probe round 3). Same constraint applies to any client stack, so it is built
        # explicitly here rather than trusted to a library.
        fname = PREFIX + '-img.png'
        boundary = '----AltAlmProbe' + datetime.datetime.now().strftime('%H%M%S%f')
        crlf = '\r\n'
        parts = []
        for key, val in (('filename', fname), ('description', 'probe 35'), ('ref-subtype', '1')):
            parts.append(f'--{boundary}{crlf}'
                         f'Content-Disposition: form-data; name="{key}"{crlf}{crlf}{val}{crlf}')
        head = ''.join(parts).encode('utf-8')
        filehead = (f'--{boundary}{crlf}'
                    f'Content-Disposition: form-data; name="file"; filename="{fname}"{crlf}'
                    f'Content-Type: image/png{crlf}{crlf}').encode('utf-8')
        tail = f'{crlf}--{boundary}--{crlf}'.encode('utf-8')
        st, _, raw = call('POST', f'{proj}/requirements/{rid}/attachments',
                          head + filehead + PNG + tail,
                          f'multipart/form-data; boundary={boundary}')
        print(f'-- upload: HTTP {st}')
        if st not in (200, 201):
            print(f'   {mask(raw.decode("utf-8", "replace"))[:300]}')
            return 1

        # ---- Q1: what does the COLLECTION return? ----------------------------------------
        print('\n-- 1. GET the attachments collection')
        st, hdrs, raw = call('GET', f'{proj}/requirements/{rid}/attachments')
        print(f'   HTTP {st}, Content-Type={hdrs.get("Content-Type")!r}')
        att_id = None
        if st == 200:
            j = json.loads(raw)
            ents = j.get('entities') or []
            print(f'   envelope keys: {sorted(j.keys())}, entities={len(ents)}')
            if ents:
                names = [f.get('Name') for f in ents[0].get('Fields', [])]
                print(f'   fields on an attachment: {sorted(n for n in names if n)}')
                att_id = first_value(ents[0], 'id')
                for k in ('id', 'name', 'file-size', 'ref-subtype', 'description', 'last-modified'):
                    print(f'      {k:14} = {first_value(ents[0], k)!r}')

        # ---- Q2/Q4/Q5: the member, by NAME -----------------------------------------------
        print('\n-- 2. GET the member by NAME (Accept: */*)')
        st, hdrs, raw = call('GET',
                             f'{proj}/requirements/{rid}/attachments/{urllib.parse.quote(fname)}',
                             accept='*/*')
        print(f'   HTTP {st}, Content-Type={hdrs.get("Content-Type")!r}, '
              f'Content-Length={hdrs.get("Content-Length")!r}')
        print(f'   Content-Disposition={hdrs.get("Content-Disposition")!r}')
        print(f'   bytes returned: {len(raw)} (sent {len(PNG)})')
        same = raw == PNG
        print(f'   byte-identical: {same}')
        if not same and len(raw) < 400:
            print(f'   body starts: {mask(raw[:200].decode("utf-8", "replace"))!r}')
        print(f'   sha256 sent={hashlib.sha256(PNG).hexdigest()[:16]} '
              f'got={hashlib.sha256(raw).hexdigest()[:16]}')

        # ---- Q3: by-id -------------------------------------------------------------------
        print('\n-- 3. GET the member by ID (?by-id=true)')
        if att_id:
            st2, h2, raw2 = call('GET',
                                 f'{proj}/requirements/{rid}/attachments/{att_id}?by-id=true',
                                 accept='*/*')
            print(f'   HTTP {st2}, Content-Type={h2.get("Content-Type")!r}, bytes={len(raw2)}')
            print(f'   matches the by-name bytes: {raw2 == raw}')
        else:
            print('   no attachment id was returned by the collection read - cannot test')

        # ---- what a JSON Accept does, since the app sends one by default -----------------
        print('\n-- 4. the same member with Accept: application/json')
        st3, h3, raw3 = call('GET',
                             f'{proj}/requirements/{rid}/attachments/{urllib.parse.quote(fname)}')
        print(f'   HTTP {st3}, Content-Type={h3.get("Content-Type")!r}, bytes={len(raw3)}')
        print(f'   still the file: {raw3 == PNG}')
        if raw3 != PNG and len(raw3) < 400:
            print(f'   body starts: {mask(raw3[:200].decode("utf-8", "replace"))!r}')

        print('\n== VERDICT ==')
        print(f'   collection read : {"entity envelope" if att_id else "see above"}')
        print(f'   member by name  : {"BYTES, byte-identical" if same else "NOT the raw bytes"}')
        print(f'   content-type    : {hdrs.get("Content-Type")!r}')

    finally:
        print('\n-- what this run changed')
        after = probe_state.snapshot(call_text, proj, WATCHED)
        report = probe_state.diff(before, after)
        probe_state.print_diff(report, before, after, mask)
        # One requirement added; its PARENT is modified because creating a child moves it (probe 34).
        for line in probe_state.expect(report, {'requirements': {'added': 1, 'modified': 1}}):
            print(f'   *** UNEXPECTED: {line}')
        print(f'   kept for reuse: {[f"{a}/{b}" for a, b in created]}')

        call('DELETE', base + '/rest/site-session')
        call('POST', base + '/authentication-point/logout')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

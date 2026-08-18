"""Probe 27 - what does ALM actually STORE when a memo field is handed hostile HTML?

The client-side sanitiser (spa/src/detail/richText.ts) is tested against payloads we wrote
ourselves. That proves the sanitiser does what we asked; it does not prove what it will be asked.
Between the attacker and our renderer sits ALM, which re-formats every memo it stores, and nobody
has looked at what comes out the other side.

Two things this settles, and they pull in opposite directions:

  1. If the payloads do not come back, that is NOT the same as "ALM refuses to store them". ALM
     sanitises on OUTPUT, per field, against a whitelist file the deployment owns - so a quiet
     round trip means the setting is on here, not that the data is clean. (Established after the
     first run of this probe read its own result as write-time stripping. It is not.)
  2. If ALM stores them verbatim, then a stored-XSS payload can sit in a real requirement's
     Description right now, and the sanitiser is the only thing between it and every Alt-ALM user.
     That is the answer that makes the sanitiser load-bearing rather than precautionary.

It also produces the one thing the sandbox does not have: a record with genuinely formatted rich
text, so the new renderer can be looked at instead of assumed.

Hypothesis: ALM stores the formatting largely intact (it round-trips its own editor's output) and
does NOT sanitise, because its own client renders in an environment that trusts the server.
Refuting observation: the fetched memo comes back without the script element and without the
onerror attribute.

WRITES - and only to the sandbox designated by the user on 2026-08-12, the project named in
Secrets/ALM_API_credentials.json. Every record is prefixed ALTALM-PROBE- and deleted in a finally
block. `--keep` suspends the delete so a screenshot can be taken; `--sweep` deletes everything
matching the prefix and is safe to run at any time.

    python scripts/probe/probe-richtext.py            # create, report, delete
    python scripts/probe/probe-richtext.py --keep     # create, report, LEAVE IT (then --sweep)
    python scripts/probe/probe-richtext.py --sweep    # delete every ALTALM-PROBE-* requirement
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

MASK = []


def mask(s):
    out = str(s)
    for term in MASK:
        if term:
            out = re.sub(re.escape(term), 'REDACTED', out, flags=re.I)
    return out


# A 1x1 transparent GIF. Inline images are the one kind Alt-ALM can render, so the memo needs one
# to prove the distinction between "image we show" and "image we refuse to fetch".
DOT = ('data:image/gif;base64,'
       'R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7')

# Formatting first, payloads second, so a partial store is still legible in the diff.
MEMO = f"""<html><body>
<h2 style="color: #1a5fb4">Acceptance criteria</h2>
<p>The service <b>must</b> reject a malformed batch and <i>must not</i> partially apply it.
Escalate to <a href="https://example.invalid/runbook">the runbook</a> if it does.</p>
<ul><li>Reject the whole batch.</li><li>Log the rejected row count.</li>
<li style="color: #b3261e">Never partially apply.</li></ul>
<table border="1" cellpadding="4">
<tr><th>Case</th><th>Expected</th></tr>
<tr><td>Empty batch</td><td>HTTP 400</td></tr>
<tr><td>One bad row</td><td>HTTP 422, nothing written</td></tr>
</table>
<p><font color="#8a5300" size="2">Raised at the 2026-08 review.</font></p>
<p>Inline image: <img src="{DOT}" alt="dot"> and a stored one:
<img src="https://example.invalid/qcbin/attachment.png" alt="sequence diagram"></p>
<script>window.__altalm_probe = 'script element executed'</script>
<img src="x" onerror="window.__altalm_probe = 'onerror fired'">
<p><a href="javascript:window.__altalm_probe='href ran'">looks like a link</a></p>
<p style="background: url(https://example.invalid/beacon.png)">styled with a remote fetch</p>
</body></html>"""

# What must not survive into a rendered page. Searched for in what ALM GIVES BACK.
PAYLOADS = {
    'script element': '<script',
    'onerror attribute': 'onerror',
    'javascript: href': 'javascript:',
    'remote url() in style': 'url(https://example.invalid/beacon.png)',
    'remote img src': 'https://example.invalid/qcbin/attachment.png',
}

FORMATTING = {
    'heading': '<h2',
    'bold': '<b>',
    'list': '<li',
    'table': '<table',
    'font tag': '<font',
    'inline style': 'style=',
    'link': 'example.invalid/runbook',
    'inline data: image': 'data:image/gif',
}


def main():
    argv = sys.argv[1:]
    keep = '--keep' in argv
    sweep_only = '--sweep' in argv

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

    # ⚠️ The sandbox and only the sandbox. This script writes, so the target is the project in the
    # credentials file - the one the user designated disposable - and there is no flag to point it
    # anywhere else. The borrowed projects are GET-only by grant.
    proj = f"{base}/rest/domains/{c['domain']}/projects/{c['project']}"
    print(f'writing to the SANDBOX only ({mask(c["project"])})\n')

    def field(entity, name):
        for f in entity.get('Fields', []):
            if f.get('Name') == name:
                return (f.get('values') or [{}])[0].get('value')
        return None

    def sweep():
        """Delete every requirement carrying the probe prefix. Idempotent; safe to run alone."""
        status, txt = call(
            'GET', f'{proj}/requirements?query={{name[{PREFIX}*]}}&fields=id,name&page-size=100')
        if status != 200:
            print(f'sweep: listing HTTP {status}')
            return
        rows = json.loads(txt).get('entities', [])
        if not rows:
            print('sweep: no orphans')
            return
        for row in rows:
            rid = field(row, 'id')
            code, _ = call('DELETE', f'{proj}/requirements/{rid}')
            print(f'sweep: DELETE requirements/{rid} -> HTTP {code}')

    # What does a memo field accept BESIDES a full HTML document? The question behind it is whether
    # ALM has a markup DIALECT (markdown, wiki) that it interprets, or whether HTML is simply the
    # storage format - in which case everything else is inert text that renders as itself.
    FORMAT_CASES = [
        ('plain text, no markup at all',
         'The batch importer must reject malformed rows.'),
        ('markdown',
         '# Heading\n\n**bold** and *italic*\n\n- first item\n- second item'),
        ('wiki markup',
         "== Heading ==\n'''bold''' and ''italic''\n* first item\n* second item"),
        ('bare HTML fragment, no html/body wrapper',
         '<p>A <b>fragment</b> with no document around it.</p>'),
        ('text that merely looks like markup',
         'if (a < b && c > d) then "escape me"'),
    ]

    if '--formats' in argv:
        made = []
        try:
            status, txt = call('GET', f'{proj}/requirements?page-size=1&fields=id,type-id')
            rows = json.loads(txt).get('entities', [])
            if not rows:
                print('no requirement to parent under')
                return 1
            parent_id = field(rows[0], 'id')
            type_id = field(rows[0], 'type-id')
            stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')

            for i, (label, sent) in enumerate(FORMAT_CASES):
                entity = {'Fields': [
                    {'Name': 'name', 'values': [{'value': f'{PREFIX}{stamp}-fmt{i}'}]},
                    {'Name': 'parent-id', 'values': [{'value': str(parent_id)}]},
                    {'Name': 'type-id', 'values': [{'value': str(type_id)}]},
                    {'Name': 'description', 'values': [{'value': sent}]},
                ], 'Type': 'requirement'}
                status, txt = call('POST', f'{proj}/requirements', json.dumps(entity))
                print(f'--- {label}')
                if status not in (200, 201):
                    print(f'    POST -> HTTP {status}\n')
                    continue
                rid = field(json.loads(txt), 'id')
                made.append(rid)
                _, back = call('GET', f'{proj}/requirements/{rid}?fields=id,description')
                stored = field(json.loads(back), 'description') or ''
                print(f'    sent   : {sent[:110]!r}')
                print(f'    stored : {stored[:240]!r}')
                print(f'    wrapped: {"yes" if "<body" in stored.lower() else "no"}')
                print()
        finally:
            for rid in made:
                call('DELETE', f'{proj}/requirements/{rid}')
            sweep()
            call('POST', base + '/authentication-point/logout')
        return 0

    if sweep_only:
        sweep()
        return 0

    created = []
    try:
        # Copy parent-id and type-id off an existing record rather than guessing: roots and subtype
        # ids are per-project customization (ADR 0005) and the sandbox's are not ours to assume.
        status, txt = call('GET', f'{proj}/requirements?page-size=1&fields=id,parent-id,type-id')
        rows = json.loads(txt).get('entities', []) if status == 200 else []
        if not rows:
            print('the sandbox has no requirement to copy a parent from; cannot place a new one')
            return 1
        # Parent under the existing record's OWN id, not its parent-id. The sandbox's one
        # requirement reports parent-id=-1, which is the tree root's sentinel and is not itself a
        # row: POSTing against it returns 500 "Entity with key '-1' does not exist in table 'REQ'".
        parent_id = field(rows[0], 'id')
        type_id = field(rows[0], 'type-id')
        print(f'placing under parent-id={parent_id}, type-id={type_id}')

        stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
        name = f'{PREFIX}{stamp}-richtext'

        # Field order is load-bearing on ALM writes (probe 3): name, then relational ids, then the
        # subtype, then the payload. A dict literal preserves insertion order in Python 3.7+, and
        # json.dumps writes it out in that order.
        entity = {
            'Fields': [
                {'Name': 'name', 'values': [{'value': name}]},
                {'Name': 'parent-id', 'values': [{'value': str(parent_id)}]},
                {'Name': 'type-id', 'values': [{'value': str(type_id)}]},
                {'Name': 'description', 'values': [{'value': MEMO}]},
            ],
            'Type': 'requirement',
        }
        status, txt = call('POST', f'{proj}/requirements', json.dumps(entity))
        print(f'POST requirements -> HTTP {status}')
        if status not in (200, 201):
            # A 5xx may still have committed the row (alm-api §1.2); the sweep in `finally` is what
            # makes that survivable rather than a mystery orphan.
            print('  body: ' + mask(re.sub(r'\s+', ' ', txt))[:400])
            return 1

        new_id = field(json.loads(txt), 'id')
        created.append(new_id)
        print(f'created requirement {new_id} named {name}\n')

        status, txt = call('GET', f'{proj}/requirements/{new_id}?fields=id,name,description')
        stored = field(json.loads(txt), 'description') or ''

        print('--- what ALM STORED, by feature ---')
        for label, needle in FORMATTING.items():
            print(f'  formatting  {label:22} {"kept" if needle in stored else "DROPPED"}')
        print()
        executable = 0
        beacons = 0
        for label, needle in PAYLOADS.items():
            present = needle.lower() in stored.lower()
            if present:
                if label == 'remote img src':
                    beacons += 1
                else:
                    executable += 1
            print(f'  payload     {label:22} {"RETURNED VERBATIM" if present else "not returned"}')

        print()
        print(f'sent {len(MEMO)} chars, stored {len(stored)} chars')
        print()
        if executable:
            print(f'FINDING: ALM stored {executable} executable payload(s) unchanged. A memo '
                  'field can carry live markup, so the client-side sanitiser is the only thing '
                  'preventing stored XSS.')
        else:
            print('FINDING: no executable payload came back. Note what this does NOT show: '
                  'the probe cannot see the database, and ALM applies OUTPUT sanitisation - '
                  'per-field project configuration (Do nothing / Text encoding / HTML '
                  'sanitization) over a deployment-owned sanitizer-whitelist.xml. The raw '
                  'value is still stored. A project set to Do nothing returns it live, which '
                  'is why the client-side sanitiser is load-bearing rather than '
                  'older clients.')
        if beacons:
            print(f'{beacons} remote image src survived. Nothing executes, but rendering it would '
                  'fetch from a host the memo author chose - which is exactly what the renderer '
                  'refuses to do.')

        if keep:
            print(f'\n--keep: requirement {new_id} LEFT IN THE SANDBOX. '
                  'Run with --sweep when finished.')
            created.clear()
    finally:
        for rid in created:
            code, _ = call('DELETE', f'{proj}/requirements/{rid}')
            print(f'cleanup: DELETE requirements/{rid} -> HTTP {code}')
        if not keep:
            sweep()
        call('POST', base + '/authentication-point/logout')
    return 0


if __name__ == '__main__':
    sys.exit(main())

---
name: alm-live-probe
description: Safe, read-only-by-default probing of a live ALM/QC instance — safety rules, masking discipline, the working PowerShell probe-script skeleton, and hard-won gotchas. Load before touching a live ALM instance.
---

Load `alm-api` first for API behaviour. This skill covers *how to safely talk to the live sandbox*.

## 1. Safety rules (non-negotiable)

- **Read-only by default.** Writes are permitted ONLY against a project the user has explicitly
  designated a sandbox — currently confirmed for the project referenced in
  `Secrets/ALM_API_credentials.json` (confirmed 2026-08-12). Never assume any other project/instance is
  writable.
- ⚠️ **The tenant's OTHER projects are readable, and that is a narrow grant** (user, 2026-08-14).
  Eight are reachable with the same key; `PROJECT-5` is the populated one used for P1 validation.
  Rules, all three of which apply every time:
  1. **GET only.** Never issue a non-GET against any project but the sandbox — not even a "harmless"
     one. Probe scripts that touch foreign projects should have no write helper defined at all.
  2. **Nothing from them enters the repo.** Counts and structural shapes only — never a node name,
     requirement text, owner, or any other field value, in fixtures, docs, logs or commits.
     Pseudonymize the projects themselves (`PROJECT-5`); the real names live only in git-ignored
     `Secrets/alm-read-projects.json`. Their project names are *themselves* other teams' data.
  3. **Seeding the sandbox from their data is allowed** (user-authorized) — but a seeded record is
     sandbox state, not a committed artifact. The repo outlives the sandbox; that asymmetry is the
     whole reason for rule 2. CLAUDE.md hard constraint: the record generator refuses any target not on an explicit
  allowlist; the same discipline applies to ad-hoc probe scripts.
- **Never print, log, or commit the contents of `Secrets/`.** Load credentials at runtime; reference the
  file by path in docs/code, never its values.
- **Every created record carries an `ALTALM-PROBE-<timestamp>` name prefix**, e.g.
  `'ALTALM-PROBE-' + (Get-Date -Format 'yyyyMMdd-HHmmss')` — so any row can be attributed to a run.
- ⚠️ **Records are KEPT, not deleted** (user, 2026-08-20). **This reverses the previous rule**, which
  deleted everything in a `finally` and then swept by prefix. That rule threw away every reusable
  target and cost real work: probe 33 had to build two releases before it could test anything,
  because the sandbox had none, and P1 validation could not use the sandbox at all.
- **Snapshot before, snapshot after, report the delta.** `scripts/probe/probe_state.py`:
  `snapshot(call, proj, collections)` → `diff(before, after)` → `print_diff(...)` →
  `expect(report, {...})`. Take the "before" right after login, the "after" in the `finally`.
  Talking to the BFF instead of ALM? Use `snapshot_bff` / `diff_bff` — different response shape, and
  they detect added/removed only, because `ver-stamp` is not guaranteed to be among the grid's
  columns and inferring "unchanged" from a field never fetched is a false all-clear.
- **The diff is strictly better at the job the sweep existed for.** A sweep could only find rows
  whose *name* matched a prefix. A 5xx that silently commits returns no id **and need not carry your
  prefix at all** — the diff sees it regardless. It also sees rows you never meant to touch: probe 34
  was found this way on its first run, when the root requirement's `ver-stamp` moved because creating
  a child moves its parent.
- **`expect()` is the assertion, and it is not optional.** A printed diff nobody reads has replaced
  one silent failure with another. Declare what the run intends to change; anything else is reported
  as a surprise. **Verify the run reported no surprises before declaring the probe done.**
- **Assert on the delta, never on absolute counts.** "0 releases" was never a fact about ALM, only
  about a project nobody had used yet — and it stops being true the first time a probe keeps its
  records.
- ⚠️ **`test-instances` is deliberately NOT in that list, and a name sweep cannot find one** (probe
  28). A test instance has no `name` field at all — its identity comes from the test it points at —
  so `?query={name[ALTALM-PROBE*]}` against `test-instances` returns **HTTP 404, not an empty list**.
  A sweep that includes it prints one 404 line and then reports "no orphans" while the instance is
  still there. Sweep instances **through their parent test set** (`{cycle-id[<set-id>]}`) *before*
  deleting the set, or deleting the set orphans them. `scripts/probe/probe-testlab-seed.py` does this.
  ⚠️ Assume the same trap for any entity whose name is derived rather than stored — check that a
  collection actually *has* the field you are sweeping on before trusting the sweep's silence.

⚠️ **Stop any locally-running BFF before probing or running contract tests** (found 2026-08-20).
Sharing the API key with a live app makes **writes fail with 5xx/`UNKNOWN`** reproducibly, and they
pass immediately once it is stopped. Probable cause: `authentication-point/logout` ends the
*authentication*, not one session, so one process closing its pool breaks the other's. Kill the
**port holder** on 8080, not the Maven parent.

⚠️ **Track-by-id cleanup cannot cover a 5xx write.** An `UNKNOWN` create returns **no id**, so there
is nothing to record and nothing to delete — the row leaks. The name-prefix sweep is the only
cleanup that catches it, which is why §1 requires it rather than treating it as belt-and-braces.

## 2. Masking discipline

Every probe script builds a `maskTerms` list at startup — host, domain, project, API key, API secret,
and (once discovered) the resolved username — and a `Mask()` helper that regex-replaces each term with
`REDACTED`:

```powershell
$maskHost = ([Uri]$base).Host
$script:maskTerms = [System.Collections.Generic.List[string]]::new()
foreach ($m in @($maskHost, $c.api_key, $c.api_secret, $c.domain, $c.project)) {
    if ($m) { $script:maskTerms.Add([string]$m) }
}
function Mask([string]$s) {
    if (-not $s) { return $s }
    foreach ($m in $script:maskTerms) { $s = $s -replace [regex]::Escape($m), 'REDACTED' }
    return $s
}
```

Add the resolved username to `maskTerms` too, as soon as it's known (it's PII, and appears in
`detected-by`/`owner` fields on created records):

```powershell
$me = (($r.Content | ConvertFrom-Json).AuthenticationInfo.Username)
if ($me) { $script:maskTerms.Add([string]$me) }
```

- **Mask ALL output** — every `Write-Host` status line, every saved fixture, error bodies included.
- Before committing any new fixture, **verify programmatically that no raw secret string appears in it**
  (e.g. grep the saved file for the unmasked host/key/secret strings) — don't rely on Mask() having been
  called correctly everywhere by eye.
- Entity/user *data* (real project user lists, etc.) is not captured into fixtures at all — count only.

## 3. Reusable script skeleton

Credentials load at runtime from `Secrets/ALM_API_credentials.json`, keys: `alm_adress`, `api_key`,
`api_secret`, `domain`, `project`.

```powershell
$c = Get-Content $secretsPath -Raw | ConvertFrom-Json
$base = ([string]$c.alm_adress).Trim().TrimEnd('/')
if ($base -notmatch '/qcbin$') { $base = "$base/qcbin" }

$iwr = @{ TimeoutSec = 60; SkipHttpErrorCheck = $true; MaximumRedirection = 0; AllowInsecureRedirect = $true }

# sign in
$jsonBody = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
$r = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post -ContentType 'application/json' -Body $jsonBody -SessionVariable session
$null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post -WebSession $session
$xsrf = ($session.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'XSRF-TOKEN').Value
$proj = "$base/rest/domains/$($c.domain)/projects/$($c.project)"
```

**`Invoke-Alm`** — centralizes the XSRF header on every non-GET, masked status logging:

```powershell
function Invoke-Alm {
    param([string]$Method, [string]$Rel, [string]$BodyJson, [switch]$NoXsrf, [string]$Accept = 'application/json')
    $h = @{ Accept = $Accept }
    if (-not $NoXsrf -and $Method -ne 'GET') { $h['X-XSRF-TOKEN'] = $xsrf }
    $args = @{ Uri = "$proj/$Rel"; Method = $Method; Headers = $h; WebSession = $session }
    if ($BodyJson) { $args.ContentType = 'application/json'; $args.Body = $BodyJson }
    $r = Invoke-WebRequest @iwr @args
    Write-Host (Mask ('{0,-6} /{1,-58} HTTP {2}' -f $Method, $Rel, $r.StatusCode))
    return $r
}
```

**`Build-Entity`** — **must** use `[ordered]@{}`, never a plain Hashtable, because plain-Hashtable key
enumeration order is randomized per process (string-hash-seed randomization) and the server's write
behaviour is order-sensitive (`alm-api` §1.1 — wrong order → opaque NPE 500s that differ run to run):

```powershell
function Build-Entity([string]$Type, $Fields) {
    $fa = foreach ($k in $Fields.Keys) { [ordered]@{ Name = $k; values = @(@{ value = [string]$Fields[$k] }) } }
    return ([ordered]@{ Fields = @($fa); Type = $Type } | ConvertTo-Json -Depth 6 -Compress)
}
```
Call sites pass `$Fields` as `[ordered]@{ name = …; 'parent-id' = …; 'type-id' = … }` in the fixed
convention: name → relational ids → type/subtype fields last.

**`Get-FieldValue`** — pull a field's value out of a parsed entity response:
```powershell
function Get-FieldValue($EntityJson, [string]$Name) {
    $f = ($EntityJson.Fields | Where-Object Name -eq $Name)
    if ($f -and $f.values) { return [string]$f.values[0].value }
    return $null
}
```

**`Save-Fixture`** — always through `Mask()`:
```powershell
function Save-Fixture([string]$Name, [string]$Content) {
    Set-Content -Path (Join-Path $fixtureDir $Name) -Value (Mask $Content) -Encoding utf8
}
```

**`New-AlmEntity`** — POST + track id for cleanup + optional fixture save:
```powershell
$created = [System.Collections.Generic.List[hashtable]]::new()
function New-AlmEntity {
    param([string]$Collection, [string]$Type, $Fields, [string]$FixtureName)
    $body = Build-Entity $Type $Fields
    $r = Invoke-Alm POST $Collection $body
    if ($r.StatusCode -in 200, 201) {
        $j = $r.Content | ConvertFrom-Json
        $id = Get-FieldValue $j 'id'
        if ($id) { $created.Add(@{ rel = $Collection; id = $id }) }
        if ($FixtureName) { Save-Fixture $FixtureName ([string]$r.Content) }
        return $j
    }
    Write-Host ('  -> FAILED body: ' + (Mask (([string]$r.Content) -replace '\s+', ' ').Substring(0, [Math]::Min(400, ([string]$r.Content).Length))))
    return $null
}
```

**Before/after diff** (in `finally`) — ⚠️ **the delete-and-sweep block that used to be here is gone
deliberately.** It is the rule that changed, not merely the code; see §1.

In Python, use `scripts/probe/probe_state.py` rather than re-deriving this:

```python
import probe_state
WATCHED = ('requirements', 'releases', 'release-folders')       # what this run could touch

before = probe_state.snapshot(call, proj, WATCHED)              # right after login
try:
    ...                                                          # the probe
finally:
    after = probe_state.snapshot(call, proj, WATCHED)
    report = probe_state.diff(before, after)
    probe_state.print_diff(report, before, after, mask)          # masked, always
    # Declare the intent. Anything else comes back as a surprise and must be read.
    surprises = probe_state.expect(report, {'requirements': {'added': 1, 'modified': 1}})
    for line in surprises:
        print(f'   *** UNEXPECTED: {line}')
    # Logout is two calls, both need XSRF; the status varies and the outcome does not.
    call('DELETE', base + '/rest/site-session')
    call('POST', base + '/authentication-point/logout')
```

⚠️ `{'requirements': {'modified': 1}}` above is not a typo: creating a child moves the **parent's**
`ver-stamp` (probe 34), so a probe that creates one requirement legitimately reports one modified row
it never wrote to. Declare it rather than widening the check, so that if it ever stops happening —
or starts happening somewhere new — the run says so.

In PowerShell the shape is the same three steps; there is no shared helper yet.
Reference implementations: `scripts/probe/probe-write-1.ps1` (round 1), `probe-write-3.ps1` (round 3,
adds XML entity building and hand-built multipart).

## 4. PowerShell gotchas found the hard way

- **`$pid` is a reserved automatic variable** (current process id) — using it for a parent/folder id
  silently reads garbage. Use `$parentId` or similar, never `$pid`.
- **Function return values get polluted by pipeline output.** Any unassigned expression inside a
  function (a bare string, a cmdlet call whose output isn't captured) becomes part of that function's
  return value in PowerShell. Use `Write-Host` for status/progress lines inside helper functions —
  never bare string literals or `Write-Output` — or the caller's `$result = Get-Foo` silently captures
  your debug text too.
- **PS7's `-Form` parameter on `Invoke-WebRequest` builds a multipart body this server rejects.** Round
  2's `ref-subtype=1` multipart upload failed with an opaque parse error using `-Form`; round 3's
  hand-built body (explicit boundary, CRLF line endings, text parts first, `file` part **last** with
  its own `Content-Type: image/png`) succeeded 3/3. Treat multipart construction as a
  compatibility risk to verify per HTTP client/stack, not just per server:
  ```powershell
  $boundary = '----AltAlmProbe' + [Guid]::NewGuid().ToString('N')
  $CRLF = "`r`n"
  $ms = [IO.MemoryStream]::new()
  $w = [IO.StreamWriter]::new($ms, [Text.UTF8Encoding]::new($false))
  $w.NewLine = $CRLF
  # ... write text parts (filename, description, ref-subtype) as
  #     --boundary CRLF Content-Disposition: form-data; name="X" CRLF CRLF value CRLF
  # then the file part LAST:
  $w.Write("--$boundary$CRLF")
  $w.Write("Content-Disposition: form-data; name=`"file`"; filename=`"x.png`"$CRLF")
  $w.Write("Content-Type: image/png$CRLF$CRLF")
  $w.Flush(); $ms.Write($pngBytes, 0, $pngBytes.Length)
  $w.Write("$CRLF--$boundary--$CRLF"); $w.Flush()
  Invoke-Alm POST "requirements/$id/attachments" -BodyBytes $ms.ToArray() -ContentType "multipart/form-data; boundary=$boundary"
  ```
- **`AllowInsecureRedirect`** (in the shared `$iwr` splat) is needed where a redirect crosses http/https
  or otherwise would be blocked by default `Invoke-WebRequest` redirect security — set
  `MaximumRedirection = 0` and inspect `Location` manually instead of following redirects blind, since
  ALM's own redirect chains have been a source of confusion.

## 5. Probe protocol

1. **State the hypothesis and what observation would confirm or refute it, before running anything.**
   e.g. "Hypothesis: direct `POST runs` accepts `test-id`+`testcycl-id`+`cycle-id`. Refuting observation:
   any non-201 response citing a missing/invalid field."
2. **Treat a 5xx as "unknown outcome — verify by query," never as "failed."** Follow every 5xx write
   with a GET to check whether the row committed anyway (`alm-api` §1.2 has the exact case that burned
   us).
3. **Record results in `docs/research/live-probe-log.md`** — this file is ground truth and wins all
   conflicts with static documentation. Save fixtures (masked) under `tests/fixtures/`.
4. **Label anything not directly observed as `UNVERIFIED`**, with the specific experiment that would
   settle it. Never upgrade an inference to a verified claim without an actual probe run backing it.

## 6. OTA / COM probing (read this before attempting any OTA work)

**OTA works on our sandbox** (probe 8). Authenticate with the **API key** — no username/password:

```powershell
$td = New-Object -ComObject TDApiOle80.TDConnection      # 32-bit host only
$null = $td.InitConnectionWithApiKeyEx($url, $clientId, $secret)   # $url ends in /qcbin
$null = $td.Connect($domain, $project)                   # -> ProjectConnected = True
```

(An earlier probe concluded OTA was blocked by the SaaS SSO front door. **That was wrong** — it used
a hand-extracted client. Use ALM's deployed client and it connects first try.)

Verified over OTA: test-folder and test create/delete; **BPT components create/delete** (REST's 403
was not a licence gate — use component folder → subfolder → subfolder's `ComponentFactory`); all the
OTA-only factories (Baseline, Library, Host, HostGroup, Milestone, KPI, ScopeItem). **Test
parameters**: `Test.Params` is a *collection* (`AddParam`/`Save`/`ParamName`/`Count`), not a factory;
declaring one directly does not persist, but a **`<<<token>>>` in a design step registers it**.

Client-side setup — get these right or nothing works:

| Constraint | What to do |
|---|---|
| **OTA is 32-bit only** | 64-bit instantiation fails `0x80040154 REGDB_E_CLASSNOTREG`. Run `C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe`. |
| **Client version must match the server** | A 12.53 client against a 26.1 server returns "Invalid server response". Check the registered DLL's `FileVersion` before blaming the server. |
| **Use ALM's DEPLOYED client** | `%LOCALAPPDATA%\HP\ALM-Client\<version>\OTAClient.dll` — these are laid down by the ALM Client Launcher and they work. A payload hand-extracted from `TDConnect_*.exe` did **not** (it produced the bogus "OTA is blocked by SSO" conclusion). |
| **No admin? Register per-user** | Point `HKCU\Software\Classes\CLSID\{clsid}\InprocServer32` at that DLL (+ `ProgID`, + the `TDApiOle80.TDConnection\CLSID` mapping). No admin needed. |
| **WOW64 registry trap** | Those keys **must be written from a 32-bit process** — they redirect to `Wow6432Node`. Keys written from 64-bit are invisible to the 32-bit COM host and the old DLL silently keeps loading. Verify with `(Get-Process -Id $PID).Modules`. |
| **Type library must be registered too** | `regsvr32 /i:user` fails (no `DllInstall`). Use `LoadTypeLibEx(dll, REGKIND_NONE)` + `RegisterTypeLibForUser`. A stale typelib resolves names against the new DLL and every call fails `0x8002802B TYPE_E_ELEMENTNOTFOUND`. |
| **Installer needs elevation** | `TDConnect_*.exe` ignores `/s /v/qn` and blocks on a GUI dialog. Extract its payload instead and register per-user. |

**COM call traps** (each cost a probe run):
- `AddItem(Null)` must be `[System.DBNull]::Value` — `$null` and `[Reflection.Missing]::Value` both
  fail with "Value does not fall within the expected range".
- `SysTreeNode.RemoveNode()` takes the **node object**, not its id (an id is read as a child index).
- **Connect/Login calls return project-list objects that PowerShell prints to stdout, bypassing your
  mask function.** Always `$null = $td.InitConnection...`. This leaked real project names once.
- ⚠️ **An OTA folder delete does NOT cascade to the tests inside it.** Sweep by prefix across
  `tests` *and* `test-folders` afterwards (5 orphans were left behind before this was caught).

Working scripts: `scripts/probe/probe-ota-{1..6}.ps1` (1–3 are the superseded SSO investigation;
**4–6 are the working ones**). Undo the per-user registration from a 32-bit shell:
`Remove-Item -Recurse 'HKCU:\Software\Classes\CLSID\{C5CBD7B2-490C-45F5-8C40-B8C3D108E6D7}','HKCU:\Software\Classes\TDApiOle80.TDConnection'`

**Windows PowerShell 5.1 encoding trap** (the 32-bit host is 5.1, not PS7): it reads UTF-8-without-BOM
as ANSI, so any non-ASCII character (em dashes especially) becomes mojibake that unbalances string
literals and produces a cascade of nonsense parse errors. Write probe scripts **ASCII-only and save
with a BOM**.

⚠️ **ASCII-only is not a PowerShell rule — it is a Windows-console rule, and it applies to the Python
probes too.** The console is cp1252, so `print()`-ing a warning glyph raises `UnicodeEncodeError`
**mid-run**, which jumps straight to your `finally` — cleanup deletes records the run still needed and
the probe reports nothing. This has now cost two runs (probes 27 and 30). Keep every **printed**
string ASCII; a docstring may hold whatever it likes, because it is never written to stdout.

## 7. Currently open experiments

See `docs/plan/risks-and-open-questions.md` (Q1–Q31) for the live list of open questions and their
priority — don't restate them here; that file is the source of truth for what's still unresolved.

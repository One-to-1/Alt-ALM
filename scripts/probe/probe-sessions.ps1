#Requires -Version 7
<#
.SYNOPSIS
  Session-concurrency probe: how many simultaneous sessions can ONE API key hold?
  READ-ONLY with respect to project data - opens/closes sessions only, creates no records.

.DESCRIPTION
  Question: with a username/password login, ALM's concurrent-user licensing typically permits one
  active client at a time. Does the same limit apply to an API key over REST, and if so what is it?

  Method:
    A. Open N independent sessions with the SAME API key, each with its own cookie jar (a separate
       cookie jar is what makes them distinct sessions rather than one shared one).
    B. After all N are open, re-verify EVERY session in order. If the server enforces a cap or
       evicts oldest-first, earlier sessions will have died by now - that is the signal.
    C. Concurrent burst: fire simultaneous authenticated GETs across all live sessions to confirm
       they work at the same instant, not merely that the cookies are still valid.
    D. Inspect Site Admin for a session/connection view and any session- or apikey-related site
       parameters that would document a cap.
    E. Log every session out.

  LIMITATION, stated honestly: all sessions originate from ONE machine/IP. This measures the
  server's per-key session cap, not any per-IP or per-machine binding. If ALM binds a session to a
  client IP, that is NOT tested here - see the report line at the end.
#>
[CmdletBinding()]
param([int]$SessionCount = 10, [switch]$InsecureTLS)

$ErrorActionPreference = 'Stop'
$repoRoot    = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$secretsPath = Join-Path $repoRoot 'Secrets\ALM_API_credentials.json'

$c = Get-Content $secretsPath -Raw | ConvertFrom-Json
$base = ([string]$c.alm_adress).Trim().TrimEnd('/')
if ($base -notmatch '/qcbin$') { $base = "$base/qcbin" }
$script:maskTerms = [System.Collections.Generic.List[string]]::new()
foreach ($m in @(([Uri]$base).Host, $c.api_key, $c.api_secret, $c.domain, $c.project)) {
    if ($m) { $script:maskTerms.Add([string]$m) }
}
function Mask([string]$s) {
    if (-not $s) { return $s }
    foreach ($m in $script:maskTerms) { $s = $s -replace [regex]::Escape($m), 'REDACTED' }
    return $s
}
function Say([string]$s) { Write-Host (Mask $s) }

$iwr = @{ TimeoutSec = 60; SkipHttpErrorCheck = $true; MaximumRedirection = 0; AllowInsecureRedirect = $true }
if ($InsecureTLS) { $iwr.SkipCertificateCheck = $true }

Say "=== ALM session-concurrency probe: how many sessions can ONE API key hold? ==="
Say ("target sessions: {0}   (no project records are created or modified)" -f $SessionCount)
Say ""

$loginBody = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
$sessions  = [System.Collections.Generic.List[hashtable]]::new()

# ---------------------------------------------------------------- A. open N sessions
Say "=== A. opening $SessionCount independent sessions with the same API key ==="
for ($i = 1; $i -le $SessionCount; $i++) {
    try {
        $r = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post `
             -ContentType 'application/json' -Body $loginBody -SessionVariable ws
        if ($r.StatusCode -notin 200, 201) {
            Say ("  session {0,2}: LOGIN FAILED HTTP {1} {2}" -f $i, $r.StatusCode, (Mask $r.Content))
            break
        }
        $r2 = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post -WebSession $ws
        $xsrf = ($ws.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'XSRF-TOKEN').Value
        $qcs  = ($ws.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'QCSession').Value
        $sessions.Add(@{ idx = $i; ws = $ws; xsrf = $xsrf; qcTail = if ($qcs) { $qcs.Substring([Math]::Max(0,$qcs.Length-6)) } else { '??' } })
        Say ("  session {0,2}: login OK  site-session HTTP {1}  QCSession...{2}" -f $i, $r2.StatusCode, $sessions[-1].qcTail)
    } catch {
        Say ("  session {0,2}: EXCEPTION {1}" -f $i, (Mask $_.Exception.Message)); break
    }
}
Say ("  opened {0} session(s)" -f $sessions.Count)

# Which cookie actually distinguishes a session? If a cookie repeats across sessions it is a
# node/affinity token, not a session identity - that matters for BFF routing.
Say ""
Say "  --- cookie identity analysis (distinct values per cookie name) ---"
$byName = @{}
foreach ($s in $sessions) {
    foreach ($ck in $s.ws.Cookies.GetCookies([Uri]$base)) {
        if (-not $byName.ContainsKey($ck.Name)) { $byName[$ck.Name] = [System.Collections.Generic.List[string]]::new() }
        $byName[$ck.Name].Add($ck.Value)
    }
}
foreach ($n in ($byName.Keys | Sort-Object)) {
    $vals = $byName[$n]
    $d = ($vals | Select-Object -Unique).Count
    $verdict = if ($d -eq $sessions.Count) { 'UNIQUE per session -> session identity' }
               elseif ($d -eq 1) { 'SHARED by all -> not a session id' }
               else { "$d distinct across $($sessions.Count) -> node/affinity token" }
    Say ("    {0,-20} {1}" -f $n, $verdict)
}

# ---------------------------------------------------------------- B. re-verify all
Say ""
Say "=== B. re-verifying every session AFTER all were opened (eviction check) ==="
$alive = 0; $dead = @()
foreach ($s in $sessions) {
    try {
        $r = Invoke-WebRequest @iwr -Uri "$base/v2/rest/is-authenticated" `
             -Headers @{ Accept = 'application/json' } -WebSession $s.ws
        if ($r.StatusCode -eq 200) { $alive++; $state = 'ALIVE' }
        else { $state = "DEAD (HTTP $($r.StatusCode))"; $dead += $s.idx }
    } catch { $state = 'DEAD (exception)'; $dead += $s.idx }
    Say ("  session {0,2}: {1}" -f $s.idx, $state)
}
Say ("  RESULT: {0}/{1} still alive" -f $alive, $sessions.Count)
if ($dead.Count -gt 0) { Say ("  evicted session indexes: {0}" -f ($dead -join ', ')) }
else { Say "  NO eviction - the key held every session simultaneously" }

# ---------------------------------------------------------------- C. simultaneous use
Say ""
Say "=== C. simultaneous authenticated requests across all live sessions ==="
$proj = "$base/rest/domains/$($c.domain)/projects/$($c.project)"
$jobs = foreach ($s in $sessions) {
    Start-ThreadJob -ScriptBlock {
        param($uri, $ws, $idx, $insecure)
        $o = @{ TimeoutSec = 60; SkipHttpErrorCheck = $true }
        if ($insecure) { $o.SkipCertificateCheck = $true }
        try {
            $r = Invoke-WebRequest @o -Uri $uri -Headers @{ Accept = 'application/json' } -WebSession $ws
            [pscustomobject]@{ idx = $idx; code = $r.StatusCode }
        } catch { [pscustomobject]@{ idx = $idx; code = 'EXC' } }
    } -ArgumentList "$proj/defects?page-size=1", $s.ws, $s.idx, [bool]$InsecureTLS
}
$res = $jobs | Receive-Job -Wait -AutoRemoveJob
$ok = ($res | Where-Object { $_.code -eq 200 }).Count
foreach ($r in ($res | Sort-Object idx)) { Say ("  session {0,2}: concurrent GET -> {1}" -f $r.idx, $r.code) }
Say ("  RESULT: {0}/{1} succeeded at the same instant" -f $ok, $res.Count)

# ---------------------------------------------------------------- D. Site Admin view
Say ""
Say "=== D. Site Admin: session/connection visibility and any documented cap ==="
$sa = $sessions[0]
$saPaths = @(
    'v2/sa/api/site-params',
    'v2/sa/api/connections',
    'v2/sa/api/site-sessions',
    'rest/site-session/connections'
)
foreach ($p in $saPaths) {
    try {
        $r = Invoke-WebRequest @iwr -Uri "$base/$p" -Headers @{ Accept = 'application/json' } -WebSession $sa.ws
        Say ("  GET /{0,-32} HTTP {1}" -f $p, $r.StatusCode)
        if ($r.StatusCode -eq 200 -and $p -match 'site-params') {
            try {
                $j = $r.Content | ConvertFrom-Json
                $rows = if ($j -is [array]) { $j } elseif ($j.siteParams) { $j.siteParams } else { $j.PSObject.Properties.Value | Select-Object -First 1 }
                $hits = $rows | Where-Object { $_.name -match 'SESSION|APIKEY|LICENS|CONCURRENT|MAX_.*USER' }
                foreach ($h in $hits) { Say ("      {0} = {1}" -f $h.name, (Mask ([string]$h.value))) }
                if (-not $hits) { Say "      (no SESSION/APIKEY/LICENSE-related params matched)" }
            } catch { Say "      (could not parse site-params body)" }
        }
    } catch { Say ("  GET /{0,-32} EXCEPTION" -f $p) }
}

# ---------------------------------------------------------------- E. logout
Say ""
Say "=== E. logging every session out ==="
$closed = 0
foreach ($s in $sessions) {
    try {
        $null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Delete `
                -Headers @{ 'X-XSRF-TOKEN' = $s.xsrf } -WebSession $s.ws
        $null = Invoke-WebRequest @iwr -Uri "$base/authentication-point/logout" -WebSession $s.ws
        $closed++
    } catch { }
}
Say ("  closed {0}/{1}" -f $closed, $sessions.Count)

Say ""
Say "=== VERDICT ==="
Say ("  concurrent sessions held by ONE API key : {0}" -f $alive)
Say ("  simultaneous in-flight requests OK      : {0}" -f $ok)
Say "  CAVEAT: all sessions came from one machine/IP. This measures the per-key session cap,"
Say "  not any per-IP or per-machine binding. Multi-machine behaviour remains UNVERIFIED."

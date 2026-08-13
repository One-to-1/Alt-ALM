# Probe 13 - session teardown semantics.
#
# Question: what does DELETE /qcbin/rest/site-session actually destroy, and is it enough?
# Answer (see docs/research/live-probe-log.md, Probe 13): it ends the PROJECT session only. The
# LWSSO authentication survives it, so a client that stops there leaks one authenticated identity
# per session. POST /qcbin/authentication-point/logout - WITH the XSRF header, which it needs like
# every other non-GET - is what actually ends the authentication.
#
# Read-only apart from tearing down the sessions it opens itself. All output masked.
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
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
# Write-Host, never Write-Output: this sits alongside functions that return values.
function Say([string]$s) { Write-Host (Mask $s) }

$iwr = @{ TimeoutSec = 60; SkipHttpErrorCheck = $true; MaximumRedirection = 0; AllowInsecureRedirect = $true }
$proj = "$base/rest/domains/$($c.domain)/projects/$($c.project)"

# Returns the cookie header as a FROZEN literal string - this is what a Java/HTTP client replays,
# as opposed to a PowerShell WebSession which quietly updates itself from every Set-Cookie.
function Open-Session {
    $body = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
    $null = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post `
        -ContentType 'application/json' -Body $body -SessionVariable s
    $null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post -WebSession $s
    $jar = $s.Cookies.GetCookies([Uri]$base)
    return @{
        xsrf   = ($jar | Where-Object Name -eq 'XSRF-TOKEN').Value
        cookie = (($jar | Where-Object { $_.Name -in 'JSESSIONID','LWSSO_COOKIE_KEY','QCSession','XSRF-TOKEN' } |
                   ForEach-Object { "$($_.Name)=$($_.Value)" }) -join '; ')
    }
}
function Try-Status([string]$uri, [string]$cookie) {
    # A fully logged-out request 302s to the login form, and PS7 throws on that when
    # MaximumRedirection=0 - SkipHttpErrorCheck does not cover redirects. Report it, don't follow it.
    try {
        $r = Invoke-WebRequest @iwr -Uri $uri -Headers @{ Accept = 'application/json'; Cookie = $cookie }
        return [string]$r.StatusCode
    } catch {
        if ($_.Exception.Message -match 'redirection') { return '302' }
        return 'ERR'
    }
}
function Probe-State([string]$label, [string]$cookie) {
    Say ('{0,-34} is-authenticated={1,-6} project-read={2}' -f $label,
         (Try-Status "$base/v2/rest/is-authenticated" $cookie),
         (Try-Status "$proj/defects?page-size=1&fields=id" $cookie))
}

Say '--- A: DELETE site-session alone ---'
$a = Open-Session
Probe-State 'A after login' $a.cookie
$r = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Delete `
    -Headers @{ 'X-XSRF-TOKEN' = $a.xsrf; Cookie = $a.cookie }
Say ('A DELETE rest/site-session -> HTTP {0}' -f $r.StatusCode)
Probe-State 'A after DELETE' $a.cookie          # <- project-read dies, is-authenticated does NOT

Say ''
Say '--- B: POST logout WITHOUT the XSRF header ---'
$b = Open-Session
$r = Invoke-WebRequest @iwr -Uri "$base/authentication-point/logout" -Method Post `
    -Headers @{ Cookie = $b.cookie }
Say ('B POST logout (no XSRF)   -> HTTP {0}' -f $r.StatusCode)   # 401 - the XSRF gate, nothing more
Probe-State 'B after POST logout' $b.cookie                       # still fully alive

Say ''
Say '--- C: POST logout WITH the XSRF header (the correct teardown) ---'
$d = Open-Session
$r = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Delete `
    -Headers @{ 'X-XSRF-TOKEN' = $d.xsrf; Cookie = $d.cookie }
Say ('C DELETE site-session     -> HTTP {0}' -f $r.StatusCode)
$r = Invoke-WebRequest @iwr -Uri "$base/authentication-point/logout" -Method Post `
    -Headers @{ 'X-XSRF-TOKEN' = $d.xsrf; Cookie = $d.cookie }
Say ('C POST logout (with XSRF) -> HTTP {0}' -f $r.StatusCode)

# Replaying a logged-out session's cookies is a 500, NOT a 401. Body says why.
$r = Invoke-WebRequest @iwr -Uri "$base/v2/rest/is-authenticated" `
    -Headers @{ Accept = 'application/json'; Cookie = $d.cookie }
$short = (([string]$r.Content) -replace '\s+', ' ')
if ($short.Length -gt 200) { $short = $short.Substring(0, 200) }
Say ('C replay stale cookies    -> HTTP {0}  body: {1}' -f $r.StatusCode, (Mask $short))

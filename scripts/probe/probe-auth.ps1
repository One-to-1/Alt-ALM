#Requires -Version 7
<#
.SYNOPSIS
  Read-only probe of the ALM auth handshake. Evidence-gathering only — creates/modifies nothing.

.DESCRIPTION
  Reads Secrets/ALM_API_credentials.json at runtime. Prints ONLY: HTTP status codes, cookie NAMES,
  boolean presence checks, and masked headers. Never prints the host, domain, project, key, secret,
  or any response body. Tries the candidate API-key sign-in endpoints in order and reports which
  one the server accepts.

.NOTES
  Candidates tried (all are hypotheses to verify, see docs/prompts/fable-5-research-and-plan.md §21):
    1. POST /qcbin/rest/oauth2/login            body {"clientId","secret"}
    2. POST /qcbin/api/authentication/sign-in   Authorization: Basic base64(key:secret)
    3. GET  /qcbin/authentication-point/authenticate  Authorization: Basic base64(key:secret)
#>
[CmdletBinding()]
param(
    # Pass only if the server uses a self-signed/private-CA cert and you accept skipping validation.
    [switch]$InsecureTLS
)

$ErrorActionPreference = 'Stop'
$secretsPath = Join-Path $PSScriptRoot '..\..\Secrets\ALM_API_credentials.json'
$c = Get-Content $secretsPath -Raw | ConvertFrom-Json

# --- normalise base URL to end at /qcbin ---
$base = ([string]$c.alm_adress).Trim().TrimEnd('/')
if ($base -notmatch '/qcbin$') { $base = "$base/qcbin" }
$maskHost = ([Uri]$base).Host

# --- masking: nothing sensitive may reach stdout ---
function Mask([string]$s) {
    if (-not $s) { return $s }
    foreach ($m in @($maskHost, $c.api_key, $c.api_secret, $c.domain, $c.project)) {
        if ($m) { $s = $s -replace [regex]::Escape([string]$m), '<masked>' }
    }
    return $s
}

$iwr = @{ TimeoutSec = 25; SkipHttpErrorCheck = $true; MaximumRedirection = 0 }
if ($InsecureTLS) { $iwr.SkipCertificateCheck = $true }

$session = $null
function Cookies() {
    if (-not $script:session) { return '' }
    ($script:session.Cookies.GetAllCookies() | ForEach-Object Name | Sort-Object -Unique) -join ','
}
function Report([string]$label, $resp) {
    '{0,-52} HTTP {1,-4} cookies: {2}' -f $label, $resp.StatusCode, (Cookies)
}

'=== ALM auth probe (read-only) ==='
'base URL ends with /qcbin : ' + ($base -match '/qcbin$')

try {
    # 1 — unauthenticated check: expect 401 pointing at the authentication point
    $r = Invoke-WebRequest @iwr -Uri "$base/rest/is-authenticated" -SessionVariable session
    Report 'GET /rest/is-authenticated (no auth)' $r
    if ($r.Headers['WWW-Authenticate']) { 'WWW-Authenticate: ' + (Mask ($r.Headers['WWW-Authenticate'] -join ' ')) }

    # 2 — API-key sign-in candidates, first success wins
    $winner = $null
    $jsonBody = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
    $r = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post -ContentType 'application/json' -Body $jsonBody -WebSession $session
    Report 'POST /rest/oauth2/login (clientId/secret JSON)' $r
    if ($r.StatusCode -in 200, 201) { $winner = 'rest/oauth2/login' }

    $basicPair = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$($c.api_key):$($c.api_secret)"))
    if (-not $winner) {
        $r = Invoke-WebRequest @iwr -Uri "$base/api/authentication/sign-in" -Method Post -Headers @{ Authorization = "Basic $basicPair" } -WebSession $session
        Report 'POST /api/authentication/sign-in (Basic key)' $r
        if ($r.StatusCode -in 200, 201) { $winner = 'api/authentication/sign-in' }
    }
    if (-not $winner) {
        $r = Invoke-WebRequest @iwr -Uri "$base/authentication-point/authenticate" -Headers @{ Authorization = "Basic $basicPair" } -WebSession $session
        Report 'GET /authentication-point/authenticate (Basic)' $r
        if ($r.StatusCode -eq 200) { $winner = 'authentication-point/authenticate' }
    }

    "sign-in accepted by  : $(if ($winner) { $winner } else { 'NONE — all candidates rejected' })"
    if (-not $winner) { return }

    # 3 — site session (QCSession / XSRF-TOKEN cookies)
    $r = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post -WebSession $session
    Report 'POST /rest/site-session' $r
    $r = Invoke-WebRequest @iwr -Uri "$base/rest/is-authenticated" -WebSession $session
    Report 'GET /rest/is-authenticated (authed)' $r

    # 4 — read-only reachability: domains and projects, reported as booleans/counts only
    $r = Invoke-WebRequest @iwr -Uri "$base/rest/domains" -Headers @{ Accept = 'application/json' } -WebSession $session
    Report 'GET /rest/domains' $r
    if ($r.StatusCode -eq 200) {
        'domains payload contains configured domain   : ' + ([string]$r.Content).Contains([string]$c.domain)
    }
    $r = Invoke-WebRequest @iwr -Uri "$base/rest/domains/$($c.domain)/projects" -Headers @{ Accept = 'application/json' } -WebSession $session
    Report 'GET /rest/domains/<D>/projects' $r
    if ($r.StatusCode -eq 200) {
        'projects payload contains configured project : ' + ([string]$r.Content).Contains([string]$c.project)
    }

    # 5 — logout
    $r = Invoke-WebRequest @iwr -Uri "$base/authentication-point/logout" -WebSession $session
    Report 'GET /authentication-point/logout' $r
}
catch {
    'TRANSPORT ERROR (masked): ' + (Mask $_.Exception.Message)
    if ($_.Exception.InnerException) { 'inner: ' + (Mask $_.Exception.InnerException.Message) }
    'Hint: if this is a TLS/certificate error and the server uses a private CA, re-run with -InsecureTLS. If it is a timeout, check VPN/reachability.'
}

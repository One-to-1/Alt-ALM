# Probe 14 - does ALM send CORS headers to a third-party browser origin?
#
# Question: could a static SPA (GitHub Pages or similar) call /qcbin directly, removing the need to
# host the BFF anywhere? ADR 0001 rejected direct browser access partly on "no CORS allowance is
# documented or observed" - an absence of evidence, never an actual test. This is the test.
#
# Answer: NO, definitively. See docs/research/live-probe-log.md Probe 14.
# Strictly read-only; tears down the one session it opens.
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$c = Get-Content (Join-Path $repoRoot 'Secrets\ALM_API_credentials.json') -Raw | ConvertFrom-Json
$base = ([string]$c.alm_adress).Trim().TrimEnd('/')
if ($base -notmatch '/qcbin$') { $base = "$base/qcbin" }

$script:maskTerms = @(([Uri]$base).Host, $c.api_key, $c.api_secret, $c.domain, $c.project) |
    Where-Object { $_ }
function Mask([string]$s) {
    foreach ($m in $script:maskTerms) { $s = $s -replace [regex]::Escape($m), 'REDACTED' }
    return $s
}
function Say([string]$s) { Write-Host (Mask $s) }

$iwr = @{ TimeoutSec = 45; SkipHttpErrorCheck = $true; MaximumRedirection = 0; AllowInsecureRedirect = $true }

# Any third-party origin will do; a GitHub Pages URL is the concrete case that prompted this.
$origin = 'https://example.github.io'

function Show-Cors([string]$label, $resp) {
    $names = @('Access-Control-Allow-Origin', 'Access-Control-Allow-Credentials',
               'Access-Control-Allow-Headers', 'Access-Control-Allow-Methods')
    $found = foreach ($n in $names) {
        if ($resp.Headers.ContainsKey($n)) { "{0}: {1}" -f $n, ($resp.Headers[$n] -join ',') }
    }
    if (-not $found) {
        Say ('{0,-40} HTTP {1}  -> NO CORS headers at all' -f $label, $resp.StatusCode)
    } else {
        Say ('{0,-40} HTTP {1}  -> {2}' -f $label, $resp.StatusCode, ($found -join ' | '))
    }
}

# 1. The preflight a browser sends before any cross-origin JSON POST.
$r = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Options -Headers @{
    'Origin'                         = $origin
    'Access-Control-Request-Method'  = 'POST'
    'Access-Control-Request-Headers' = 'content-type'
}
Show-Cors 'OPTIONS preflight (login)' $r      # -> 501 Not Implemented

# 2. A simple GET carrying Origin, which a browser attaches automatically.
try {
    $r = Invoke-WebRequest @iwr -Uri "$base/v2/rest/is-authenticated" `
        -Headers @{ Origin = $origin; Accept = 'application/json' }
    Show-Cors 'GET is-authenticated with Origin' $r
} catch {
    Say 'GET is-authenticated with Origin         -> 302 to login form, no CORS headers'
}

# 3. The credentialed login itself. Succeeds - but the response carries no CORS headers, so a
#    browser would refuse to let JS read it even though the server processed it fine.
$body = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
$r = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post `
    -ContentType 'application/json' -Body $body -Headers @{ Origin = $origin } -SessionVariable s
Show-Cors 'POST oauth2/login with Origin' $r

# Full teardown (both calls, both with XSRF - see probe 13).
$xsrf = ($s.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'XSRF-TOKEN').Value
if ($xsrf) {
    $null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Delete `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $s
    $null = Invoke-WebRequest @iwr -Uri "$base/authentication-point/logout" -Method Post `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $s
    Say 'session torn down'
}

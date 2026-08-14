# Probe 17 - what actually separates multiple order-by fields, comma or semicolon?
#
# Our own alm-api-reference.md §4.3 contradicts itself: the grammar-summary line writes
# `order-by={field[,field...]}` (comma) while its worked example writes
# `order-by={status;name[DESC]}` (semicolon). AlmQuery implemented comma and labelled it
# UNVERIFIED rather than guessing. One request settles it.
#
# STRICTLY READ-ONLY. Reads a populated project (read-only grant, 2026-08-14) and prints
# ORDERINGS ONLY - ids, never names or any other field value, since this is another team's data.
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$c = Get-Content (Join-Path $repoRoot 'Secrets\ALM_API_credentials.json') -Raw | ConvertFrom-Json
$map = Get-Content (Join-Path $repoRoot 'Secrets\alm-read-projects.json') -Raw | ConvertFrom-Json
$base = ([string]$c.alm_adress).Trim().TrimEnd('/')
if ($base -notmatch '/qcbin$') { $base = "$base/qcbin" }

$script:maskTerms = [System.Collections.Generic.List[string]]::new()
foreach ($m in @(([Uri]$base).Host, $c.api_key, $c.api_secret, $c.domain, $c.project)) {
    if ($m) { $script:maskTerms.Add([string]$m) }
}
foreach ($p in $map.projects) { if ($p.name) { $script:maskTerms.Add([string]$p.name) } }
function Mask([string]$s) {
    if (-not $s) { return $s }
    foreach ($m in $script:maskTerms) { $s = $s -replace [regex]::Escape($m), 'REDACTED' }
    return $s
}
function Say([string]$s) { Write-Host (Mask $s) }

$iwr = @{ TimeoutSec = 60; SkipHttpErrorCheck = $true; MaximumRedirection = 0; AllowInsecureRedirect = $true }

$body = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
$null = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post `
    -ContentType 'application/json' -Body $body -SessionVariable session
$xsrf = ($session.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'XSRF-TOKEN').Value
$null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $session
$r = Invoke-WebRequest @iwr -Uri "$base/v2/rest/is-authenticated" `
    -Headers @{ Accept = 'application/json' } -WebSession $session
if ($r.StatusCode -eq 200) {
    $me = ($r.Content | ConvertFrom-Json).AuthenticationInfo.Username
    if ($me) { $script:maskTerms.Add([string]$me) }
}

# The populated read-only project.
$target = ($map.projects | Where-Object { $_.access -eq 'READ-ONLY' } | Sort-Object totalRows -Descending)[0]
$proj = "$base/rest/domains/$($map.domain)/projects/$($target.name)"
Say ("signed in; reading {0} (read-only)" -f $target.alias)

function Try-OrderBy([string]$label, [string]$raw) {
    $uri = "$proj/requirements?fields=id,type-id&page-size=8&order-by=$raw"
    $rr = Invoke-WebRequest @iwr -Uri $uri -Method Get `
        -Headers @{ Accept = 'application/json' } -WebSession $session
    if ($rr.StatusCode -ne 200) {
        $snip = (([string]$rr.Content) -replace '\s+', ' ')
        if ($snip.Length -gt 200) { $snip = $snip.Substring(0, 200) }
        Say ('{0,-34} -> HTTP {1}  {2}' -f $label, $rr.StatusCode, $snip)
        return
    }
    $j = $rr.Content | ConvertFrom-Json
    # ids and type-ids only - both are opaque integers, not content.
    $seq = foreach ($e in @($j.entities)) {
        $id = ($e.Fields | Where-Object Name -eq 'id').values[0].value
        $ty = ($e.Fields | Where-Object Name -eq 'type-id').values[0].value
        "$ty/$id"
    }
    Say ('{0,-34} -> HTTP 200  {1}' -f $label, ($seq -join ' '))
}

try {
    Say ''
    Say '=== single field (baseline) ==='
    Try-OrderBy 'order-by={id}'            '{id}'
    Try-OrderBy 'order-by={id[DESC]}'      '{id[DESC]}'

    Say ''
    Say '=== multi-field: comma vs semicolon ==='
    Try-OrderBy 'order-by={type-id,id}'    '{type-id,id}'
    Try-OrderBy 'order-by={type-id;id}'    '{type-id;id}'

    Say ''
    Say '=== multi-field with DESC on the second key ==='
    Try-OrderBy 'order-by={type-id,id[DESC]}' '{type-id,id[DESC]}'
    Try-OrderBy 'order-by={type-id;id[DESC]}' '{type-id;id[DESC]}'

    Say ''
    Say '=== a deliberately bogus separator, to see what rejection looks like ==='
    Try-OrderBy 'order-by={type-id|id}'    '{type-id|id}'
    Try-OrderBy 'order-by={no-such-field}' '{no-such-field}'
} finally {
    if ($xsrf) {
        $null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Delete `
            -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $session
        $null = Invoke-WebRequest @iwr -Uri "$base/authentication-point/logout" -Method Post `
            -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $session
        Say ''
        Say 'session torn down'
    }
}

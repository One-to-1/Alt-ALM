# Probe 15b - follow-ups to probe-grids.ps1. Strictly READ-ONLY.
#
# Probe 15's run refuted the assumption that {parent-id[0]} is the universal tree-root discovery
# rule, and its page-size cap test ran against empty collections so it proved less than it looked.
# This settles both properly.
#
#   B2. Name the actual root row of every tree, under BOTH parent-id[0] and parent-id[-1], so the
#       discovery rule can be stated from evidence rather than from the two trees that happened to
#       answer. Critically: test-set-folders' parent-id[0] row is "Recycle Bin", NOT the tree root -
#       a UI built on that rule would show users the wrong tree.
#
#   C2. Find a collection with real row counts, then test the cap against it. Also test the "max"
#       page-size keyword that the -1 error message revealed.
#
#   A2. Diff the alm-web group body against plain JSON on a collection that actually HAS data,
#       and capture the full body - probe 15 only saw top-level key names.
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$c = Get-Content (Join-Path $repoRoot 'Secrets\ALM_API_credentials.json') -Raw | ConvertFrom-Json
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

$body = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
$null = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post `
    -ContentType 'application/json' -Body $body -SessionVariable session
$xsrf = ($session.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'XSRF-TOKEN').Value
$null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $session
$proj = "$base/rest/domains/$($c.domain)/projects/$($c.project)"

$r = Invoke-WebRequest @iwr -Uri "$base/v2/rest/is-authenticated" `
    -Headers @{ Accept = 'application/json' } -WebSession $session
if ($r.StatusCode -eq 200) {
    $me = ($r.Content | ConvertFrom-Json).AuthenticationInfo.Username
    if ($me) { $script:maskTerms.Add([string]$me) }
}
Say 'signed in'

function Get-Alm([string]$Rel, [string]$Accept = 'application/json') {
    return Invoke-WebRequest @iwr -Uri "$proj/$Rel" -Method Get `
        -Headers @{ Accept = $Accept } -WebSession $session
}
function FieldOf($Entity, [string]$Name) {
    $f = ($Entity.Fields | Where-Object Name -eq $Name)
    if ($f -and $f.values) { return [string]$f.values[0].value }
    return ''
}

$fixtureDir = Join-Path $repoRoot 'tests\fixtures\grids'
New-Item -ItemType Directory -Force -Path $fixtureDir | Out-Null

try {
    # === B2. Name every candidate root row ====================================================
    Say ''
    Say '=== B2. root rows by parent-id, per tree ==='

    $trees = @('requirements', 'test-folders', 'test-set-folders', 'release-folders', 'bpm-folders',
        'resource-folders')

    foreach ($t in $trees) {
        foreach ($pv in @('0', '-1')) {
            $r = Get-Alm "$t`?query={parent-id[$pv]}&fields=id,name,parent-id&page-size=20"
            if ($r.StatusCode -ne 200) { Say ('{0,-18} parent-id={1,-3} -> HTTP {2}' -f $t, $pv, $r.StatusCode); continue }
            $j = $r.Content | ConvertFrom-Json
            $rows = foreach ($e in @($j.entities)) {
                "id={0} name='{1}'" -f (FieldOf $e 'id'), (FieldOf $e 'name')
            }
            if (@($j.entities).Count -eq 0) { $rows = @('(none)') }
            Say ('{0,-18} parent-id={1,-3} -> {2}' -f $t, $pv, ($rows -join '; '))
        }
        # What sits directly under whichever row is the real root? Confirms it IS the root.
        $r = Get-Alm "$t`?fields=id,name,parent-id&page-size=8"
        if ($r.StatusCode -eq 200) {
            $j = $r.Content | ConvertFrom-Json
            $rows = foreach ($e in @($j.entities)) {
                "id={0} parent={1} '{2}'" -f (FieldOf $e 'id'), (FieldOf $e 'parent-id'), (FieldOf $e 'name')
            }
            Say ('{0,-18} first rows      -> total={1}: {2}' -f $t, $j.TotalResults, ($rows -join '; '))
        }
        Say ''
    }

    # === C2. Page-size cap against a collection that actually has rows =========================
    Say '=== C2. row counts per collection, to find one big enough to test the cap ==='
    $counts = @{}
    foreach ($col in @('requirements', 'tests', 'defects', 'test-instances', 'runs', 'design-steps',
            'test-sets', 'test-folders', 'releases', 'release-cycles')) {
        $r = Get-Alm "$col`?fields=id&page-size=1"
        if ($r.StatusCode -eq 200) {
            $n = ($r.Content | ConvertFrom-Json).TotalResults
            $counts[$col] = $n
            Say ('{0,-16} TotalResults={1}' -f $col, $n)
        } else {
            Say ('{0,-16} HTTP {1}' -f $col, $r.StatusCode)
        }
    }

    $biggest = ($counts.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 1)
    Say ''
    Say ('largest collection: {0} ({1} rows)' -f $biggest.Key, $biggest.Value)

    Say ''
    Say '--- page-size boundary values ---'
    foreach ($ps in @('1', '2000', '2001', '5000', 'max', '0')) {
        $r = Get-Alm "$($biggest.Key)?fields=id&page-size=$ps"
        if ($r.StatusCode -ne 200) {
            $snippet = (([string]$r.Content) -replace '\s+', ' ')
            if ($snippet.Length -gt 200) { $snippet = $snippet.Substring(0, 200) }
            Say ('page-size={0,-6} -> HTTP {1}  {2}' -f $ps, $r.StatusCode, $snippet)
            continue
        }
        $j = $r.Content | ConvertFrom-Json
        Say ('page-size={0,-6} -> HTTP 200  returned {1,5}, TotalResults={2}' -f `
                $ps, @($j.entities).Count, $j.TotalResults)
    }

    # === A2. Full alm-web group body on a collection with data ================================
    Say ''
    Say '=== A2. alm-web group body, full ==='
    foreach ($case in @(
            @{ col = 'requirements'; field = 'type-id' },
            @{ col = 'requirements'; field = 'status' },
            @{ col = 'tests'; field = 'owner' })) {
        $rel = "$($case.col)/groups/$($case.field)"
        $plain = Get-Alm $rel 'application/json'
        $web = Get-Alm $rel 'application/json;schema=alm-web'
        Say ''
        Say ("--- $rel ---")
        Say ('  plain  : ' + (([string]$plain.Content) -replace '\s+', ' '))
        Say ('  alm-web: ' + (([string]$web.Content) -replace '\s+', ' '))
        if ($web.StatusCode -eq 200) {
            Set-Content -Path (Join-Path $fixtureDir ("groups-{0}-{1}-almweb.json" -f $case.col, $case.field)) `
                -Value (Mask ([string]$web.Content)) -Encoding utf8
        }
        if ($plain.StatusCode -eq 200) {
            Set-Content -Path (Join-Path $fixtureDir ("groups-{0}-{1}-plain.json" -f $case.col, $case.field)) `
                -Value (Mask ([string]$plain.Content)) -Encoding utf8
        }
    }

    # Does the dialect alter a normal collection read? Probe 15 said "not identical" - see how.
    Say ''
    Say '=== A2b. alm-web on a plain collection read ==='
    $plain = Get-Alm 'requirements?fields=id,name&page-size=2' 'application/json'
    $web = Get-Alm 'requirements?fields=id,name&page-size=2' 'application/json;schema=alm-web'
    Say ('  plain  : ' + (([string]$plain.Content) -replace '\s+', ' '))
    Say ('  alm-web: ' + (([string]$web.Content) -replace '\s+', ' '))
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

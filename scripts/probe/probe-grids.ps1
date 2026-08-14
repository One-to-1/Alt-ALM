# Probe 15 - P1's phase-start deferred probes. Strictly READ-ONLY.
#
# Three questions P1 (read-only Alt-ALM) cannot be designed around without answers:
#
#   A. Does Accept: application/json;schema=alm-web return a different body shape from plain JSON
#      on .../groups/{groupsFields}? (open item #10, risk R11, Q2)
#      Decides: server-side group-by, or the client-side aggregation fallback.
#
#   B. What is the root id of each tree entity, especially release-folders? (open item #10)
#      Decides: whether runtime root discovery via {parent-id[0]} generalizes, or release-folders
#      need a different discovery rule. ADR 0005 forbids hardcoding either way - this probe
#      establishes the DISCOVERY RULE, not the values.
#
#   C. Is REST_API_MAX_PAGE_SIZE really a silent cap - does an over-cap page-size get clamped with
#      no error and no signal? Decides how the grid detects "more results than shown".
#
# Opens one session, reads, tears it down. Creates nothing, so no ALTALM- prefix / cleanup applies.
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

# --- sign in -----------------------------------------------------------------------------------
$body = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
$null = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post `
    -ContentType 'application/json' -Body $body -SessionVariable session
$xsrf = ($session.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'XSRF-TOKEN').Value
$null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $session
$proj = "$base/rest/domains/$($c.domain)/projects/$($c.project)"

# The resolved username is PII and appears in owner/detected-by fields - mask it from here on.
$r = Invoke-WebRequest @iwr -Uri "$base/v2/rest/is-authenticated" `
    -Headers @{ Accept = 'application/json' } -WebSession $session
if ($r.StatusCode -eq 200) {
    $me = ($r.Content | ConvertFrom-Json).AuthenticationInfo.Username
    if ($me) { $script:maskTerms.Add([string]$me) }
}
Say 'signed in'

function Get-Alm([string]$Rel, [string]$Accept = 'application/json') {
    $r = Invoke-WebRequest @iwr -Uri "$proj/$Rel" -Method Get `
        -Headers @{ Accept = $Accept } -WebSession $session
    return $r
}

$fixtureDir = Join-Path $repoRoot 'tests\fixtures\grids'
New-Item -ItemType Directory -Force -Path $fixtureDir | Out-Null
function Save-Fixture([string]$Name, [string]$Content) {
    Set-Content -Path (Join-Path $fixtureDir $Name) -Value (Mask $Content) -Encoding utf8
}

try {
    # === A. alm-web dialect vs plain JSON on groups/{groupsFields} =============================
    Say ''
    Say '=== A. groups/{groupsFields}: plain JSON vs alm-web dialect ==='

    # One grouping field per entity that the stock UI actually groups by.
    $cases = @(
        @{ col = 'defects';      field = 'status' },
        @{ col = 'defects';      field = 'severity' },
        @{ col = 'requirements'; field = 'type-id' },
        @{ col = 'tests';        field = 'subtype-id' }
    )

    foreach ($case in $cases) {
        $rel = "$($case.col)/groups/$($case.field)"

        $plain = Get-Alm $rel 'application/json'
        $web = Get-Alm $rel 'application/json;schema=alm-web'

        $plainLen = ([string]$plain.Content).Length
        $webLen = ([string]$web.Content).Length
        $same = (([string]$plain.Content) -eq ([string]$web.Content))

        Say ('{0,-34} plain HTTP {1} ({2,6} B) | alm-web HTTP {3} ({4,6} B) | identical: {5}' -f `
                $rel, $plain.StatusCode, $plainLen, $web.StatusCode, $webLen, $same)

        # The whole point is the SHAPE, so record the top-level keys of each, not just the length.
        foreach ($pair in @(@{ n = 'plain'; r = $plain }, @{ n = 'alm-web'; r = $web })) {
            if ($pair.r.StatusCode -eq 200) {
                try {
                    $j = $pair.r.Content | ConvertFrom-Json
                    $keys = ($j.PSObject.Properties.Name -join ', ')
                    Say ('    {0,-8} top-level keys: {1}' -f $pair.n, $keys)
                } catch {
                    Say ('    {0,-8} body is not JSON' -f $pair.n)
                }
            } else {
                $snippet = (([string]$pair.r.Content) -replace '\s+', ' ')
                if ($snippet.Length -gt 220) { $snippet = $snippet.Substring(0, 220) }
                Say ('    {0,-8} -> {1}' -f $pair.n, $snippet)
            }
        }

        if ($plain.StatusCode -eq 200) {
            Save-Fixture ("groups-{0}-{1}-plain.json" -f $case.col, $case.field) ([string]$plain.Content)
        }
        if ($web.StatusCode -eq 200 -and -not $same) {
            Save-Fixture ("groups-{0}-{1}-almweb.json" -f $case.col, $case.field) ([string]$web.Content)
        }
    }

    # Does the dialect change a PLAIN collection read too, or only the grouping endpoints?
    # If it changes nothing anywhere, "42 ops advertise it" is a documentation artifact.
    $plain = Get-Alm 'defects?page-size=1' 'application/json'
    $web = Get-Alm 'defects?page-size=1' 'application/json;schema=alm-web'
    Say ('{0,-34} plain HTTP {1} | alm-web HTTP {2} | identical: {3}' -f `
            'defects (plain collection read)', $plain.StatusCode, $web.StatusCode,
        (([string]$plain.Content) -eq ([string]$web.Content)))

    # === B. Tree roots - is {parent-id[0]} the universal discovery rule? =======================
    Say ''
    Say '=== B. root discovery per tree entity ==='

    $trees = @('requirements', 'test-folders', 'test-set-folders', 'release-folders', 'bpm-folders',
        'resource-folders', 'favorite-folders')

    foreach ($t in $trees) {
        $r = Get-Alm "$t`?query={parent-id[0]}&fields=id,name,parent-id&page-size=10"
        if ($r.StatusCode -ne 200) {
            $snippet = (([string]$r.Content) -replace '\s+', ' ')
            if ($snippet.Length -gt 160) { $snippet = $snippet.Substring(0, 160) }
            Say ('{0,-18} parent-id[0] -> HTTP {1}  {2}' -f $t, $r.StatusCode, $snippet)
            continue
        }
        $j = $r.Content | ConvertFrom-Json
        $rows = foreach ($e in @($j.entities)) {
            $id = ($e.Fields | Where-Object Name -eq 'id').values[0].value
            $nm = ($e.Fields | Where-Object Name -eq 'name').values[0].value
            "id=$id name='$nm'"
        }
        Say ('{0,-18} parent-id[0] -> {1} row(s): {2}' -f $t, $j.TotalResults, ($rows -join '; '))
    }

    # If a tree has no parent-id[0] row, its root may be parented to -1, or be absent entirely.
    Say ''
    Say '--- fallback: parent-id[-1] for any tree with no parent-id[0] row ---'
    foreach ($t in $trees) {
        $r = Get-Alm "$t`?query={parent-id[-1]}&fields=id,name,parent-id&page-size=10"
        if ($r.StatusCode -eq 200) {
            $j = $r.Content | ConvertFrom-Json
            Say ('{0,-18} parent-id[-1] -> {1} row(s)' -f $t, $j.TotalResults)
        } else {
            Say ('{0,-18} parent-id[-1] -> HTTP {1}' -f $t, $r.StatusCode)
        }
    }

    # === C. Is the page-size cap silent? ======================================================
    Say ''
    Say '=== C. page-size cap behaviour ==='

    foreach ($ps in @(10, 2000, 5000, -1)) {
        $r = Get-Alm "defects?fields=id&page-size=$ps"
        if ($r.StatusCode -ne 200) {
            $snippet = (([string]$r.Content) -replace '\s+', ' ')
            if ($snippet.Length -gt 160) { $snippet = $snippet.Substring(0, 160) }
            Say ('page-size={0,-6} -> HTTP {1}  {2}' -f $ps, $r.StatusCode, $snippet)
            continue
        }
        $j = $r.Content | ConvertFrom-Json
        Say ('page-size={0,-6} -> HTTP 200  returned {1,5} entities, TotalResults={2}' -f `
                $ps, @($j.entities).Count, $j.TotalResults)
    }
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

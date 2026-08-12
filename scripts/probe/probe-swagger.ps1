#Requires -Version 7
<#
.SYNOPSIS
  Read-only probe round 3: Swagger/OpenAPI discovery, resource-list inventory, v2 is-authenticated.
  Creates/modifies nothing on the server.

.DESCRIPTION
  Signs in with the API key (flow verified by probe-auth.ps1), then:
    1. Probes /qcbin/api-doc/v2/ and /qcbin/api-doc/sa/v2/ (ALM 24.1+ per-instance Swagger UI)
       plus common spec-file locations; if an HTML shell is returned, scans it for spec URLs and
       follows them.
    2. Harvests any OpenAPI JSON found: saves a REDACTED fixture, writes a sorted path inventory,
       and prints counts plus hits for high-interest gap keywords (design-steps writes, hosts,
       timeslots, purge, milestones, baselines, libraries, list-item writes).
    3. GET /qcbin/rest/resource-list and project-scoped variant (documented resource inventory).
    4. GET /qcbin/v2/rest/is-authenticated (17.0.1+ JSON session check).
  Output masks host/domain/project/key material everywhere; fixtures are redacted before write.
#>
[CmdletBinding()]
param([switch]$InsecureTLS)

$ErrorActionPreference = 'Stop'
$repoRoot    = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$secretsPath = Join-Path $repoRoot 'Secrets\ALM_API_credentials.json'
$fixtureDir  = Join-Path $repoRoot 'tests\fixtures'
New-Item -ItemType Directory -Force $fixtureDir | Out-Null

$c = Get-Content $secretsPath -Raw | ConvertFrom-Json
$base = ([string]$c.alm_adress).Trim().TrimEnd('/')
if ($base -notmatch '/qcbin$') { $base = "$base/qcbin" }
$maskHost = ([Uri]$base).Host

function Mask([string]$s) {
    if (-not $s) { return $s }
    foreach ($m in @($maskHost, $c.api_key, $c.api_secret, $c.domain, $c.project)) {
        if ($m) { $s = $s -replace [regex]::Escape([string]$m), 'REDACTED' }
    }
    return $s
}

$iwr = @{ TimeoutSec = 60; SkipHttpErrorCheck = $true; MaximumRedirection = 0 }
if ($InsecureTLS) { $iwr.SkipCertificateCheck = $true }

'=== ALM Swagger/OpenAPI + inventory probe (read-only) ==='

# --- sign in (verified flow) ---
$jsonBody = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
$r = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post -ContentType 'application/json' -Body $jsonBody -SessionVariable session
if ($r.StatusCode -notin 200, 201) { "sign-in failed: HTTP $($r.StatusCode)"; return }
$null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post -WebSession $session
'signed in : true'
''

$proj = "$base/rest/domains/$($c.domain)/projects/$($c.project)"

function Probe([string]$rel, [string]$accept = 'application/json') {
    $r = Invoke-WebRequest @iwr -Uri "$base/$rel" -Headers @{ Accept = $accept } -WebSession $session
    $ct  = ([string]$r.Headers.'Content-Type') -replace ';.*', ''
    $len = ([string]$r.Content).Length
    # Write-Host so this prints instead of being captured by callers assigning the return value
    Write-Host (Mask ('{0,-52} HTTP {1}  ct:{2,-26} len:{3}' -f "GET /$rel", $r.StatusCode, $ct, $len))
    return $r
}

function Harvest-OpenApi([string]$content, [string]$tag) {
    try { $spec = $content | ConvertFrom-Json -AsHashtable } catch { "  [$tag] not parseable as JSON"; return $false }
    if (-not $spec.ContainsKey('paths')) { "  [$tag] JSON but no 'paths' key (keys: $($spec.Keys -join ', '))"; return $false }
    $ver = $spec['openapi'] ?? $spec['swagger'] ?? '?'
    $title = ''
    if ($spec.ContainsKey('info')) { $title = [string]$spec['info']['title'] }
    $paths = $spec['paths']
    "  [$tag] OpenAPI/Swagger version: $ver  title: $(Mask $title)  paths: $($paths.Count)"

    # fixture: full masked spec
    Set-Content -Path (Join-Path $fixtureDir "api-doc-$tag-openapi.json") -Value (Mask $content) -Encoding utf8

    # fixture: sorted "METHOD path" inventory
    $inv = foreach ($p in ($paths.Keys | Sort-Object)) {
        foreach ($m in ($paths[$p].Keys | Where-Object { $_ -in 'get','put','post','delete','patch','head','options' })) {
            '{0,-7} {1}' -f $m.ToUpper(), $p
        }
    }
    Set-Content -Path (Join-Path $fixtureDir "api-doc-$tag-paths.txt") -Value (Mask ($inv -join "`n")) -Encoding utf8
    "  [$tag] operations: $($inv.Count)  -> fixtures api-doc-$tag-openapi.json / api-doc-$tag-paths.txt"

    # high-interest keyword hits (prints operations, which are generic)
    $keywords = 'design-step','host','timeslot','purge','milestone','baseline','librar','list','resource','coverage','trace','history','audit','alert','favorite','parameter','attachment','version'
    foreach ($k in $keywords) {
        $hits = @($inv | Where-Object { $_ -match $k })
        if ($hits.Count -gt 0) {
            "  [$tag] '$k' ($($hits.Count)):"
            $hits | Select-Object -First 12 | ForEach-Object { '      ' + (Mask $_) }
            if ($hits.Count -gt 12) { "      ... +$($hits.Count-12) more (see fixture)" }
        }
    }
    return $true
}

try {
    '--- 1. Swagger UI shells + spec-file candidates ---'
    $shellPaths = @('api-doc/v2/', 'api-doc/sa/v2/', 'api-doc/')
    $specCandidates = [System.Collections.Generic.List[string]]::new()
    foreach ($sp in $shellPaths) {
        $r = Probe $sp 'text/html'
        if ($r.StatusCode -in 200 -and ([string]$r.Content) -match '<') {
            # scan HTML/JS shell for spec URLs
            $found = [regex]::Matches([string]$r.Content, '["'']([^"''<>\s]*?\.(?:json|yaml|yml))["'']') |
                     ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
            foreach ($f in $found) {
                if ($f -match '^https?://') { continue } # skip absolute externals
                $resolved = if ($f.StartsWith('/')) { $f.TrimStart('/') -replace '^qcbin/', '' } else { ($sp.TrimEnd('/') + '/' + $f) }
                $specCandidates.Add($resolved)
                '  shell references spec candidate: /' + (Mask $resolved)
            }
        }
    }
    # static guesses regardless of shell findings
    foreach ($g in @('api-doc/v2/openapi.json','api-doc/v2/swagger.json','api-doc/v2/api-docs',
                     'api-doc/v2/v2/api-docs','api/api-doc','rest/api-doc',
                     'api-doc/sa/v2/openapi.json','api-doc/sa/v2/swagger.json')) { $specCandidates.Add($g) }

    ''
    '--- 2. spec candidates ---'
    $harvested = @{}
    foreach ($cand in ($specCandidates | Sort-Object -Unique)) {
        $r = Probe $cand
        if ($r.StatusCode -eq 200 -and ([string]$r.Content).TrimStart().StartsWith('{')) {
            $tag = if ($cand -match 'sa') { 'sa-v2' } else { 'v2' }
            if (-not $harvested[$tag]) { $harvested[$tag] = Harvest-OpenApi ([string]$r.Content) $tag }
        }
    }
    if ($harvested.Count -eq 0) { '  (no OpenAPI JSON harvested from any candidate)' }

    ''
    '--- 3. resource-list inventory ---'
    foreach ($cand in @('rest/resource-list', "rest/domains/$($c.domain)/projects/$($c.project)/resource-list")) {
        $r = Probe $cand
        if ($r.StatusCode -eq 200) {
            $content = [string]$r.Content
            $ext = if ($content.TrimStart().StartsWith('{')) { 'json' } else { 'xml' }
            $name = if ($cand -eq 'rest/resource-list') { 'resource-list-site' } else { 'resource-list-project' }
            Set-Content -Path (Join-Path $fixtureDir "$name.$ext") -Value (Mask $content) -Encoding utf8
            "  saved fixture $name.$ext"
        }
    }

    ''
    '--- 4. v2 is-authenticated + misc version checks ---'
    # print body shape only, with any Username value redacted (user data is never echoed)
    $redactUser = { param($s) [regex]::Replace([string]$s, '("Username"\s*:\s*")[^"]*(")', '${1}REDACTED${2}') }
    $r = Probe 'v2/rest/is-authenticated'
    if ($r.StatusCode -eq 200) { '  body: ' + (Mask (& $redactUser (([string]$r.Content) -replace '\s+',' '))) }
    $r = Probe 'rest/is-authenticated'
    if ($r.StatusCode -eq 200) { '  body: ' + (Mask (& $redactUser (([string]$r.Content) -replace '\s+',' '))) }
}
finally {
    $null = Invoke-WebRequest @iwr -Uri "$base/authentication-point/logout" -WebSession $session
    ''
    'logged out : true'
}

#Requires -Version 7
<#
.SYNOPSIS
  Read-only probe of ALM project customization metadata. Creates/modifies nothing on the server.

.DESCRIPTION
  Signs in with the API key (endpoint verified by probe-auth.ps1), then GETs per-entity field
  metadata, lookup lists, users (count only), and server-version candidates. Prints only statuses,
  counts, and generic metadata (field type identifiers). Saves field/list metadata as REDACTED
  fixtures under tests/fixtures/ (host, domain, project, key material replaced). User data is
  never saved.
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

$iwr = @{ TimeoutSec = 30; SkipHttpErrorCheck = $true; MaximumRedirection = 0 }
if ($InsecureTLS) { $iwr.SkipCertificateCheck = $true }

'=== ALM metadata probe (read-only) ==='

# --- sign in (verified flow) ---
$jsonBody = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
$r = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post -ContentType 'application/json' -Body $jsonBody -SessionVariable session
if ($r.StatusCode -notin 200, 201) { "sign-in failed: HTTP $($r.StatusCode)"; return }
$null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post -WebSession $session
'signed in : true'

$proj = "$base/rest/domains/$($c.domain)/projects/$($c.project)"

try {
    # --- server version candidates (numbers only are printed; harmless 404s expected) ---
    foreach ($cand in @('rest/server/version', 'api/server/version', 'rest/site/version', 'rest/sa/version')) {
        $r = Invoke-WebRequest @iwr -Uri "$base/$cand" -Headers @{ Accept = 'application/json' } -WebSession $session
        $note = ''
        if ($r.StatusCode -eq 200) {
            $note = ' -> ' + (Mask (([string]$r.Content) -replace '\s+', ' ').Substring(0, [Math]::Min(200, ([string]$r.Content).Length)))
        }
        '{0,-46} HTTP {1}{2}' -f "GET /$cand", $r.StatusCode, $note
    }

    # --- per-entity field metadata ---
    $entities = @('requirement','test','design-step','test-config','test-folder','test-set-folder',
                  'test-set','test-instance','run','run-step','defect','release','release-cycle','release-folder','resource')
    $allTypes = [System.Collections.Generic.SortedSet[string]]::new()
    foreach ($e in $entities) {
        $r = Invoke-WebRequest @iwr -Uri "$proj/customization/entities/$e/fields" -Headers @{ Accept = 'application/json' } -WebSession $session
        if ($r.StatusCode -ne 200) {
            '{0,-46} HTTP {1}' -f "fields[$e]", $r.StatusCode
            continue
        }
        $ct = [string]$r.Headers.'Content-Type'
        $content = [string]$r.Content
        $isJson = $ct -match 'json' -or $content.TrimStart().StartsWith('{')
        $fieldCount = -1; $types = @()
        if ($isJson) {
            try {
                $j = $content | ConvertFrom-Json
                $fieldArr = @($j.Fields.Field); if (-not $fieldArr -or $fieldArr.Count -eq 0) { $fieldArr = @($j.fields) }
                $fieldCount = $fieldArr.Count
                $types = $fieldArr | ForEach-Object { [string]($_.Type ?? $_.type) } | Where-Object { $_ } | Sort-Object -Unique
            } catch { $fieldCount = -1 }
        }
        if ($fieldCount -lt 0) {
            # XML or unexpected shape: extract Type="..." attributes
            $types = [regex]::Matches($content, 'Type="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
            $fieldCount = ([regex]::Matches($content, '<Field\b')).Count
        }
        $types | ForEach-Object { $null = $allTypes.Add($_) }
        '{0,-46} HTTP 200  fields:{1,3}  json:{2}  types: {3}' -f "fields[$e]", $fieldCount, $isJson, ($types -join ',')
        Set-Content -Path (Join-Path $fixtureDir "customization-fields-$e$(if ($isJson) {'.json'} else {'.xml'})") -Value (Mask $content) -Encoding utf8
    }
    ''
    'DISTINCT FIELD TYPE IDENTIFIERS OBSERVED: ' + ($allTypes -join ', ')
    ''

    # --- lookup lists ---
    foreach ($cand in @('customization/used-lists', 'customization/lists')) {
        $r = Invoke-WebRequest @iwr -Uri "$proj/$cand" -Headers @{ Accept = 'application/json' } -WebSession $session
        $extra = ''
        if ($r.StatusCode -eq 200) {
            $content = [string]$r.Content
            $listCount = if ($content.TrimStart().StartsWith('{')) { @(($content | ConvertFrom-Json).lists).Count } else { ([regex]::Matches($content, '<List\b')).Count }
            $extra = "  lists:$listCount"
            $ext = if ($content.TrimStart().StartsWith('{')) { 'json' } else { 'xml' }
            Set-Content -Path (Join-Path $fixtureDir ("$($cand -replace '[/]','-').$ext")) -Value (Mask $content) -Encoding utf8
        }
        '{0,-46} HTTP {1}{2}' -f "GET /$cand", $r.StatusCode, $extra
    }

    # --- requirement types (hypothesis endpoint) ---
    $r = Invoke-WebRequest @iwr -Uri "$proj/customization/entities/requirement/types" -Headers @{ Accept = 'application/json' } -WebSession $session
    '{0,-46} HTTP {1}' -f 'GET req types', $r.StatusCode
    if ($r.StatusCode -eq 200) {
        Set-Content -Path (Join-Path $fixtureDir 'customization-requirement-types.txt') -Value (Mask ([string]$r.Content)) -Encoding utf8
    }

    # --- users: COUNT ONLY, never saved ---
    $r = Invoke-WebRequest @iwr -Uri "$proj/customization/users" -Headers @{ Accept = 'application/json' } -WebSession $session
    $userCount = -1
    if ($r.StatusCode -eq 200) {
        $content = [string]$r.Content
        $userCount = if ($content.TrimStart().StartsWith('{')) { @(($content | ConvertFrom-Json).Users.User).Count } else { ([regex]::Matches($content, '<User\b')).Count }
    }
    '{0,-46} HTTP {1}  users:{2}' -f 'GET customization/users', $r.StatusCode, $userCount

    # --- one-entity envelope shape check: keys only, nothing saved ---
    $r = Invoke-WebRequest @iwr -Uri "$proj/defects?page-size=1" -Headers @{ Accept = 'application/json' } -WebSession $session
    '{0,-46} HTTP {1}' -f 'GET defects?page-size=1', $r.StatusCode
    if ($r.StatusCode -eq 200) {
        $content = [string]$r.Content
        if ($content.TrimStart().StartsWith('{')) {
            $j = $content | ConvertFrom-Json
            'defects JSON top-level keys : ' + (($j.PSObject.Properties.Name) -join ', ')
            'TotalResults                : ' + $j.TotalResults
            $first = @($j.entities)[0]
            if ($first) { 'entity keys                 : ' + (($first.PSObject.Properties.Name) -join ', ') }
        } else {
            'defects returned non-JSON content-type: ' + (Mask ([string]$r.Headers.'Content-Type'))
        }
    }
}
finally {
    $null = Invoke-WebRequest @iwr -Uri "$base/authentication-point/logout" -WebSession $session
    'logged out : true'
}

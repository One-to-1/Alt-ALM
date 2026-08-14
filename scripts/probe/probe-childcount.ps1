# Probe 19 - is `children-count` actually populated, or always 0?
#
# The tree UI uses children-count to decide whether to draw an expander. Against a populated
# project every folder reported 0 while a filtered read proved one of them has a child, so the
# expander would never appear and the tree could not be drilled.
#
# Two candidate causes, and they need different fixes:
#   (a) the server only populates it when asked a certain way (e.g. a fields projection suppresses
#       it, or it needs the tree-specific endpoint), or
#   (b) it is genuinely always 0 on this ALM version, and the UI must infer children another way.
#
# STRICTLY READ-ONLY against a read-only project. Counts and ids only - no names, no field values.
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
$null = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post -ContentType 'application/json' -Body $body -SessionVariable session
$xsrf = ($session.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'XSRF-TOKEN').Value
$null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $session
$r = Invoke-WebRequest @iwr -Uri "$base/v2/rest/is-authenticated" -Headers @{ Accept = 'application/json' } -WebSession $session
if ($r.StatusCode -eq 200) { $me = ($r.Content | ConvertFrom-Json).AuthenticationInfo.Username; if ($me) { $script:maskTerms.Add([string]$me) } }

$target = ($map.projects | Where-Object { $_.access -eq 'READ-ONLY' } | Sort-Object totalRows -Descending)[0]
$proj = "$base/rest/domains/$($map.domain)/projects/$($target.name)"
Say ("signed in; reading {0} (read-only)" -f $target.alias)

function Show([string]$label, [string]$rel) {
    $rr = Invoke-WebRequest @iwr -Uri "$proj/$rel" -Method Get -Headers @{ Accept = 'application/json' } -WebSession $session
    if ($rr.StatusCode -ne 200) {
        $s = (([string]$rr.Content) -replace '\s+', ' '); if ($s.Length -gt 160) { $s = $s.Substring(0, 160) }
        Say ('{0,-52} HTTP {1} {2}' -f $label, $rr.StatusCode, $s); return
    }
    $j = $rr.Content | ConvertFrom-Json
    # Report only ids and the children-count attribute. Never a name or any field value.
    $pairs = foreach ($e in @($j.entities)) {
        $id = ($e.Fields | Where-Object Name -eq 'id').values[0].value
        $cc = if ($null -ne $e.'children-count') { $e.'children-count' } else { '<absent>' }
        "$id`:$cc"
    }
    Say ('{0,-52} HTTP 200  id:children-count -> {1}' -f $label, ($pairs -join '  '))
}

try {
    Say ''
    Say '=== children-count under varying reads (requirements, children of root) ==='
    Show 'fields=id,name,parent-id (what the BFF sends)' 'requirements?query={parent-id[0]}&fields=id,name,parent-id&page-size=10'
    Show 'fields=id only'                                'requirements?query={parent-id[0]}&fields=id&page-size=10'
    Show 'no fields projection at all'                   'requirements?query={parent-id[0]}&page-size=10'
    Show 'with an explicit order-by'                     'requirements?query={parent-id[0]}&fields=id,name&order-by={name}&page-size=10'

    Say ''
    Say '=== is there actually a child? (filter by each folder id) ==='
    $rr = Invoke-WebRequest @iwr -Uri "$proj/requirements?query={parent-id[0]}&fields=id&page-size=10" -Method Get -Headers @{ Accept = 'application/json' } -WebSession $session
    $ids = foreach ($e in @(($rr.Content | ConvertFrom-Json).entities)) { ($e.Fields | Where-Object Name -eq 'id').values[0].value }
    foreach ($id in $ids) {
        $cr = Invoke-WebRequest @iwr -Uri "$proj/requirements?query={parent-id[$id]}&fields=id&page-size=1" -Method Get -Headers @{ Accept = 'application/json' } -WebSession $session
        $n = if ($cr.StatusCode -eq 200) { ($cr.Content | ConvertFrom-Json).TotalResults } else { "HTTP$($cr.StatusCode)" }
        Say ("  folder {0,-6} actual children by query = {1}" -f $id, $n)
    }

    Say ''
    Say '=== does test-folders behave the same? ==='
    Show 'test-folders children of root(2)' 'test-folders?query={parent-id[2]}&fields=id,name&page-size=10'
} finally {
    if ($xsrf) {
        $null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Delete -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $session
        $null = Invoke-WebRequest @iwr -Uri "$base/authentication-point/logout" -Method Post -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $session
        Say ''
        Say 'session torn down'
    }
}

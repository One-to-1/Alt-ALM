#Requires -Version 7
<#
.SYNOPSIS
  READ-ONLY probe of the most promising "NO"-verdict re-checks from
  docs/research/_raw/no-verdict-recheck.md. Creates and modifies NOTHING.

.DESCRIPTION
  Targets, in the report's recommended order:
    A. #18 Analyze / Testing Policy matrix - web research showed "Analyze" is a documented lookup
       against an admin-configured Risk x Functional Complexity grid, NOT a hidden algorithm. The
       resource-list contains no risk/rbt/testing-policy path at all (checked offline), so this
       hunts the project customization surface for where that grid lives.
    B. #205 Data-hiding - SA Swagger exposes /permissions, /permissions/metadata, /roles. Probe
       whether they return per-group, per-module data-hiding rules.
    C. Session visibility - SA Swagger has /site-connections (+ /groups/{groupedBy}) which probe 10
       did not try; this would corroborate probe 10's 50-session result from the server side.
    D. #186 Per-attachment history - resource-list has no attachments/{id}/audits path; confirm the
       404 empirically rather than trusting the inventory (it has known false negatives).
#>
[CmdletBinding()]
param([switch]$InsecureTLS)

$ErrorActionPreference = 'Stop'
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
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
    # Structural masking. Some SA endpoints (notably site-connections) return THIRD-PARTY identities
    # from other projects in the same tenant - usernames, client hostnames, project names - which no
    # credential-derived term list can anticipate. Redact them by JSON key, not by value.
    foreach ($k in @('username','host','project','domain','session-data','user-name','client-host')) {
        $s = $s -replace ('"' + $k + '"\s*:\s*"[^"]*"'), ('"' + $k + '":"REDACTED"')
    }
    return $s
}
function Say([string]$s) { Write-Host (Mask $s) }

$iwr = @{ TimeoutSec = 60; SkipHttpErrorCheck = $true; MaximumRedirection = 0; AllowInsecureRedirect = $true }
if ($InsecureTLS) { $iwr.SkipCertificateCheck = $true }

Say "=== NO-verdict re-check probe (READ-ONLY: no records created or modified) ==="
$loginBody = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
$r = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post -ContentType 'application/json' -Body $loginBody -SessionVariable ws
if ($r.StatusCode -notin 200,201) { Say "login failed"; return }
$null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post -WebSession $ws
$xsrf = ($ws.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'XSRF-TOKEN').Value
Say "signed in : true"
$proj = "rest/domains/$($c.domain)/projects/$($c.project)"

function Try-Get([string]$rel, [string]$label, [int]$preview = 0) {
    try {
        $resp = Invoke-WebRequest @iwr -Uri "$base/$rel" -Headers @{ Accept='application/json' } -WebSession $ws
        Say ("  {0,-46} HTTP {1}" -f $label, $resp.StatusCode)
        if ($resp.StatusCode -eq 200 -and $preview -gt 0) {
            $t = (Mask $resp.Content); if ($t.Length -gt $preview) { $t = $t.Substring(0,$preview) + ' ...' }
            Say ("      " + $t)
        }
        return $resp
    } catch { Say ("  {0,-46} EXCEPTION" -f $label); return $null }
}

Say ""
Say "=== A. #18 Testing Policy matrix (Analyze) ==="
Try-Get "$proj/customization"                      "customization root"                400 | Out-Null
Try-Get "$proj/customization/entities/requirement/types" "requirement types"            300 | Out-Null
Try-Get "$proj/customization/riskbasedqualitymanagement" "customization/riskbased..."     200 | Out-Null
Try-Get "$proj/customization/testing-policy"       "customization/testing-policy"        200 | Out-Null
Try-Get "$proj/customization/extensions"           "customization/extensions"            300 | Out-Null

Say ""
Say "=== B. #205 Data-hiding: SA permissions / roles ==="
Try-Get "v2/sa/api/permissions"                    "sa/permissions"                      400 | Out-Null
Try-Get "v2/sa/api/permissions/metadata"           "sa/permissions/metadata"             400 | Out-Null
Try-Get "v2/sa/api/roles"                          "sa/roles"                            400 | Out-Null
Try-Get "v2/sa/api/domains/$($c.domain)/projects/$($c.project)/groups" "sa project groups" 400 | Out-Null

Say ""
Say "=== C. Session visibility (corroborates probe 10) ==="
Try-Get "v2/sa/api/site-connections"               "sa/site-connections"                 400 | Out-Null
Try-Get "v2/sa/api/site-connections/groups/user"   "sa/site-connections/groups/user"     400 | Out-Null

Say ""
Say "=== D. #186 Per-attachment history ==="
$att = Try-Get "$proj/attachments?page-size=1" "attachments (find one)" 0
$attId = $null
if ($att -and $att.StatusCode -eq 200) {
    try {
        $e = ($att.Content | ConvertFrom-Json).entities
        if ($e -and $e.Count -gt 0) { $attId = (($e[0].Fields | Where-Object Name -eq 'id').values[0].value) }
    } catch { }
}
if ($attId) { Try-Get "$proj/attachments/$attId/audits" "attachments/{id}/audits" 300 | Out-Null }
else { Say "  (no attachment exists in the sandbox to test against - inconclusive, not a negative)" }

try { $null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Delete -Headers @{ 'X-XSRF-TOKEN'=$xsrf } -WebSession $ws } catch { }
Say ""
Say "=== END (read-only; nothing created or modified) ==="

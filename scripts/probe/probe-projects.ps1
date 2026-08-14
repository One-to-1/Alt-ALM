# Probe 16 - which domains/projects can this API key reach, and which have enough data to
# validate P1's grids against? STRICTLY READ-ONLY, and read-only against projects that are NOT
# our sandbox.
#
# Authorized by the user 2026-08-14: "you can use other projects as a read ONLY."
#
# SAFETY RULES SPECIFIC TO THIS PROBE - other projects contain real third-party data:
#   * GET only. No POST/PUT/DELETE is issued anywhere in this script, to any project.
#   * NOTHING from a non-sandbox project is written to disk. This script prints COUNTS and
#     STRUCTURE only - never a name, description, owner or any other field value.
#   * Project and domain names of other tenants are themselves sensitive, so they are printed
#     as stable pseudonyms (DOMAIN-1 / PROJECT-3), with the mapping held in memory only.
#     The point is to learn "a populated project exists and here is its shape", not to publish
#     somebody's project inventory into our git history.
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

# Pseudonym table - every foreign domain/project name is replaced by a stable label.
$script:alias = @{}
$script:aliasN = 0
function Alias([string]$kind, [string]$real) {
    if ($real -eq $c.domain -or $real -eq $c.project) { return "OURS($kind)" }
    $key = "$kind::$real"
    if (-not $script:alias.ContainsKey($key)) {
        $script:aliasN++
        $script:alias[$key] = "$kind-$($script:aliasN)"
    }
    return $script:alias[$key]
}

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
Say 'signed in'

# GET-only helper. Guards against its own misuse: this probe must never issue a write.
function Get-Only([string]$Uri, [string]$Accept = 'application/json') {
    return Invoke-WebRequest @iwr -Uri $Uri -Method Get -Headers @{ Accept = $Accept } -WebSession $session
}

try {
    Say ''
    Say '=== reachable domains and projects ==='
    $r = Get-Only "$base/rest/domains?include-projects-info=y"
    if ($r.StatusCode -ne 200) {
        # Fall back to the plain form, then per-domain project listing.
        $r = Get-Only "$base/rest/domains"
    }
    if ($r.StatusCode -ne 200) {
        Say ("domains listing -> HTTP {0}; cannot enumerate" -f $r.StatusCode)
        return
    }

    $doms = $r.Content | ConvertFrom-Json
    $domainNames = @()
    foreach ($d in @($doms.domains.domain)) { if ($d.Name) { $domainNames += [string]$d.Name } }
    if (-not $domainNames) { foreach ($d in @($doms.Domain)) { if ($d.Name) { $domainNames += [string]$d.Name } } }
    Say ("domains visible: {0}" -f $domainNames.Count)

    $candidates = [System.Collections.Generic.List[hashtable]]::new()

    foreach ($dn in $domainNames) {
        $dAlias = Alias 'DOMAIN' $dn
        $pr = Get-Only "$base/rest/domains/$dn/projects"
        if ($pr.StatusCode -ne 200) {
            Say ("{0,-12} projects -> HTTP {1}" -f $dAlias, $pr.StatusCode)
            continue
        }
        $pj = $pr.Content | ConvertFrom-Json
        $projNames = @()
        foreach ($p in @($pj.Projects.Project)) { if ($p.Name) { $projNames += [string]$p.Name } }
        if (-not $projNames) { foreach ($p in @($pj.Project)) { if ($p.Name) { $projNames += [string]$p.Name } } }
        Say ("{0,-12} -> {1} project(s)" -f $dAlias, $projNames.Count)

        foreach ($pn in $projNames) {
            $pAlias = Alias 'PROJECT' $pn
            $isOurs = ($dn -eq $c.domain -and $pn -eq $c.project)
            $proj = "$base/rest/domains/$dn/projects/$pn"

            # Count-only reads. No field values are requested beyond id, and nothing is stored.
            $counts = [ordered]@{}
            $reachable = $true
            foreach ($col in @('requirements', 'tests', 'defects', 'test-sets', 'test-instances', 'runs')) {
                $cr = Get-Only "$proj/$col`?fields=id&page-size=1"
                if ($cr.StatusCode -ne 200) { $counts[$col] = "HTTP$($cr.StatusCode)"; if ($col -eq 'requirements') { $reachable = $false }; continue }
                try { $counts[$col] = [int](($cr.Content | ConvertFrom-Json).TotalResults) }
                catch { $counts[$col] = '?' }
            }

            $line = ($counts.GetEnumerator() | ForEach-Object { "{0}={1}" -f $_.Key, $_.Value }) -join '  '
            Say ("  {0,-12} {1} {2}" -f $pAlias, $(if ($isOurs) { '[SANDBOX]' } else { '[read-only]' }), $line)

            if ($reachable) {
                $total = 0
                foreach ($v in $counts.Values) { if ($v -is [int]) { $total += $v } }
                $candidates.Add(@{ domain = $dn; project = $pn; alias = $pAlias; dAlias = $dAlias; total = $total; ours = $isOurs })
            }
        }
    }

    Say ''
    Say '=== ranked by total rows (best P1 read target first) ==='
    foreach ($cand in ($candidates | Sort-Object { $_.total } -Descending)) {
        Say ("  {0,-12} / {1,-12} total={2} {3}" -f $cand.dAlias, $cand.alias, $cand.total,
            $(if ($cand.ours) { '<- our sandbox (writable)' } else { '<- READ-ONLY' }))
    }

    Say ''
    Say 'NOTE: real domain/project names were deliberately NOT printed. Re-run interactively and'
    Say 'inspect $script:alias in-session if you need to resolve a pseudonym to configure the BFF.'
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

# probe-ota-8.ps1 - NO-verdict re-check, OTA phase 1: ACQUIRE AND ENUMERATE (READ-ONLY).
#
# Drives the recommended probe order in docs/research/_raw/no-verdict-recheck.md:
#   A. #18  Testing Policy matrix   -> TDConnection.Customization subtree
#   B. #166 Alerts row indicators   -> TDConnection.AlertManager
#   C. #130/#145 PPT Graphs/KPI     -> KPIFactory / ScopeItemFactory (acquire + read only here)
#   D. #132/#133 Reports            -> OtaReport80.Reporter / ReportConfig
#   E. #129/#175/#51/#52 long tail  -> GraphBuilder / BaselineFactory / QCResourceFactory
#
# This phase CREATES NOTHING. Writes come in a follow-up phase, informed by what is found here.
# MUST run under 32-bit Windows PowerShell 5.1. ASCII-only source (5.1 reads UTF-8-no-BOM as ANSI).

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$cred = Get-Content (Join-Path $repoRoot 'Secrets\ALM_API_credentials.json') -Raw | ConvertFrom-Json
$url = ([string]$cred.alm_adress).TrimEnd('/'); if ($url -notmatch '/qcbin$') { $url += '/qcbin' }
$apiKey = [string]$cred.api_key; $apiSec = [string]$cred.api_secret
$domain = [string]$cred.domain;  $project = [string]$cred.project

$maskTerms = @($url, $apiKey, $apiSec, $domain, $project, ([Uri]$url).Host) | Where-Object { $_ -and $_.Length -gt 2 }
function Mask([string]$s) {
    if ($null -eq $s) { return '' }
    foreach ($t in $maskTerms) { $s = $s -replace [Regex]::Escape($t), 'REDACTED' }
    # structural: OTA can surface third-party user/host names just as SA REST does
    $s = $s -replace '"(username|host|project|domain)"\s*:\s*"[^"]*"', '"$1":"REDACTED"'
    return $s
}
# MUST be Write-Host, not Write-Output: inside a helper function that also returns a value,
# Write-Output joins the return value and the caller silently receives an array of
# [status-string, real-object]. That is what made phase 1's first run report String members
# (PadLeft/Substring/...) for every COM object. See alm-live-probe skill section 4.
function Say([string]$s) { Write-Host (Mask $s) }

# Acquire a property without letting PowerShell print the returned object (masking bypass, probe 8).
function Get-Prop($obj, [string]$name) {
    return $obj.GetType().InvokeMember($name, 'GetProperty', $null, $obj, @())
}
function Show-Members($obj, [string]$label, [int]$max = 900) {
    if ($null -eq $obj) { Say ("    {0}: <null>" -f $label); return }
    try {
        $names = @($obj | Get-Member -ErrorAction Stop | Where-Object { $_.MemberType -match 'Method|Property' } |
                   Select-Object -ExpandProperty Name | Sort-Object -Unique)
        $joined = $names -join ', '
        if ($joined.Length -gt $max) { $joined = $joined.Substring(0, $max) + ' ...' }
        Say ("    {0} [{1} members]: {2}" -f $label, $names.Count, $joined)
    } catch {
        Say ("    {0}: Get-Member failed: {1}" -f $label, (Mask $_.Exception.Message))
    }
}
function Try-Acquire($parent, [string]$name) {
    try {
        $o = Get-Prop $parent $name
        if ($null -eq $o) { Say ("  {0,-24} ACQUIRED but null" -f $name); return $null }
        Say ("  {0,-24} ACQUIRED  ({1})" -f $name, $o.GetType().Name)
        return $o
    } catch {
        Say ("  {0,-24} FAILED: {1}" -f $name, (Mask $_.Exception.Message))
        return $null
    }
}

Say ("bitness: {0} bytes/ptr (must be 4)" -f [IntPtr]::Size)
Say "=== OTA phase 1: acquire + enumerate (READ-ONLY, creates nothing) ==="

$td = New-Object -ComObject TDApiOle80.TDConnection
$connected = $false; $projConn = $false
try {
    $null = $td.InitConnectionWithApiKeyEx($url, $apiKey, $apiSec); $connected = $true
    $null = $td.Connect($domain, $project); $projConn = [bool]$td.ProjectConnected
    Say ("connected={0} projectConnected={1}" -f $connected, $projConn)

    # ---------------------------------------------------------------- A. #18 Testing Policy
    Say ""
    Say "=== A. #18 Testing Policy matrix via TDConnection.Customization ==="
    $cust = Try-Acquire $td 'Customization'
    if ($cust) {
        try { $cust.Load() ; Say "    Customization.Load() OK" } catch { Say ("    Load() failed: " + (Mask $_.Exception.Message)) }
        Show-Members $cust 'Customization'
        # RBT = Risk-Based Testing. This is the Testing Policy matrix's likely home (#18).
        # KPITypes -> #145 scorecard; ReportProjectTemplates -> #132/#133; BusinessViews -> #129;
        # Permissions -> #205 data-hiding.
        foreach ($sub in @('RBT','KPITypes','ReportProjectTemplates','BusinessViews','Permissions',
                           'Fields','Lists','Modules','Relations','EntitiesMetadata','MailConditions')) {
            $s = $null
            try { $s = Get-Prop $cust $sub } catch { Say ("    -> Customization.{0} FAILED: {1}" -f $sub, (Mask $_.Exception.Message)); continue }
            if ($null -ne $s) {
                Say ("    -> Customization.{0} ACQUIRED ({1})" -f $sub, $s.GetType().Name)
                Show-Members $s ("Customization." + $sub) 700
            } else { Say ("    -> Customization.{0} returned null" -f $sub) }
        }
    }

    # ---------------------------------------------------------------- B. #166 Alerts
    Say ""
    Say "=== B. #166/#109/#196/#197 Alerts via AlertManager ==="
    $am = Try-Acquire $td 'AlertManager'
    if ($am) {
        Show-Members $am 'AlertManager'
        foreach ($m in @('GetAlerts','Alerts','GetFollowUps','FollowUps')) {
            try {
                $r = $am.GetType().InvokeMember($m, 'GetProperty,InvokeMethod', $null, $am, @())
                if ($null -ne $r) {
                    $cnt = try { $r.Count } catch { 'n/a' }
                    Say ("    {0}() -> OK, count={1}" -f $m, $cnt)
                }
            } catch { Say ("    {0}() -> {1}" -f $m, (Mask $_.Exception.Message)) }
        }
    }

    # ---------------------------------------------------------------- C. #130/#145 KPI + ScopeItem
    Say ""
    Say "=== C. #130/#145 PPT Graphs + Scorecard: KPIFactory / ScopeItemFactory ==="
    foreach ($f in @('KPIFactory','ScopeItemFactory','MilestoneFactory','ReleaseFactory')) {
        $o = Try-Acquire $td $f
        if ($o) {
            Show-Members $o $f 500
            try {
                $lst = $o.NewList('')
                Say ("    {0}.NewList('') -> {1} existing item(s)" -f $f, $lst.Count)
            } catch { Say ("    {0}.NewList('') -> {1}" -f $f, (Mask $_.Exception.Message)) }
        }
    }

    # ---------------------------------------------------------------- D. #132/#133 Reports
    Say ""
    Say "=== D. #132/#133 Project Reports / Excel Reports ==="
    foreach ($progid in @('OtaReport80.Reporter','OtaReport80.ReportConfig','TDApiOle80.Reporter')) {
        try {
            $rep = New-Object -ComObject $progid
            Say ("  {0,-28} INSTANTIATED ({1})" -f $progid, $rep.GetType().Name)
            Show-Members $rep $progid 600
            try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($rep) } catch { }
        } catch {
            Say ("  {0,-28} FAILED: {1}" -f $progid, (Mask $_.Exception.Message))
        }
    }
    # Reports may also hang off the connection itself
    foreach ($p in @('Reporter','ReportFactory','AnalysisItemFactory','DashboardFactory')) { $null = Try-Acquire $td $p }

    # ---------------------------------------------------------------- E. long tail
    Say ""
    Say "=== E. #129/#175/#51/#52 long tail ==="
    foreach ($f in @('GraphBuilder','BaselineFactory','LibraryFactory','QCResourceFactory',
                     'AssetRelationFactory','DependencyFactory','ComponentFactory','FavoriteFactory')) {
        $o = Try-Acquire $td $f
        if ($o) { Show-Members $o $f 400 }
    }

} catch {
    Say ("FATAL: " + (Mask $_.Exception.Message))
} finally {
    Say ""
    Say "=== cleanup (nothing was created; closing connection) ==="
    try { if ($projConn) { $td.DisconnectProject() } } catch { }
    try { if ($connected) { $td.Logout(); $td.ReleaseConnection() } } catch { }
    try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($td) } catch { }
    Say "=== PHASE 1 END ==="
}

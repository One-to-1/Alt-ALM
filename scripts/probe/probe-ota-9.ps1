# probe-ota-9.ps1 - NO-verdict re-check, OTA phase 2: READ ACTUAL VALUES (READ-ONLY).
#
# Phase 1 (probe-ota-8) located the objects. This reads real data out of them to settle each row:
#   #18       Customization.RBT.TestingPolicyMatrix / RiskCalculationMatrix  <- the whole point
#   #145      Customization.KPITypes
#   #132/#133 Customization.ReportProjectTemplates
#   #129      Customization.BusinessViews
#   #205      Customization.Modules.IsVisibleForGroup + Customization.Permissions
#   #166      AlertManager.AlertList
#   #209/#210 Customization.Workflow  (these are scored CONFIRMED NO - test that too)
#
# CREATES NOTHING. 32-bit Windows PowerShell 5.1 only. ASCII-only source.

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
    return $s
}
# Write-Host, never Write-Output: see probe-ota-8 header / alm-live-probe skill section 4.
function Say([string]$s) { Write-Host (Mask $s) }
function Get-Prop($obj, [string]$name) { return $obj.GetType().InvokeMember($name, 'GetProperty', $null, $obj, @()) }
function Get-PropArg($obj, [string]$name, $argv) { return $obj.GetType().InvokeMember($name, 'GetProperty', $null, $obj, $argv) }
function Call($obj, [string]$name, $argv) { return $obj.GetType().InvokeMember($name, 'InvokeMethod', $null, $obj, $argv) }

Say ("bitness: {0} (must be 4)" -f [IntPtr]::Size)
Say "=== OTA phase 2: read actual values (READ-ONLY, creates nothing) ==="

$td = New-Object -ComObject TDApiOle80.TDConnection
$connected = $false; $projConn = $false
try {
    $null = $td.InitConnectionWithApiKeyEx($url, $apiKey, $apiSec); $connected = $true
    $null = $td.Connect($domain, $project); $projConn = [bool]$td.ProjectConnected
    Say ("connected={0} projectConnected={1}" -f $connected, $projConn)

    $cust = Get-Prop $td 'Customization'
    try { $cust.Load() } catch { }

    # ---------------------------------------------------------------- #18 THE BIG ONE
    Say ""
    Say "=== #18 Customization.RBT - Testing Policy matrix ==="
    $rbt = $null
    try { $rbt = Get-Prop $cust 'RBT' } catch { Say ("  RBT acquire failed: " + (Mask $_.Exception.Message)) }
    if ($rbt) {
        foreach ($p in @('DisplayedTimeUnits','BILevelRiskLowerThreshold','FCLevelRiskLowerThreshold',
                         'FPLevelRiskLowerThreshold','Updated')) {
            try { Say ("  {0,-28} = {1}" -f $p, (Get-Prop $rbt $p)) }
            catch { Say ("  {0,-28} -> {1}" -f $p, (Mask $_.Exception.Message)) }
        }
        # The matrix itself. Try as plain property first, then indexed by (risk, complexity).
        foreach ($m in @('TestingPolicyMatrix','RiskCalculationMatrix')) {
            try {
                $v = Get-Prop $rbt $m
                Say ("  {0} -> plain read OK, type={1}" -f $m, $(if ($null -eq $v) { 'null' } else { $v.GetType().Name }))
                if ($v -is [array]) { Say ("      array length {0}: {1}" -f $v.Length, ((($v | Select-Object -First 30) -join ','))) }
                elseif ($null -ne $v) { Say ("      value: " + (Mask ([string]$v))) }
            } catch { Say ("  {0} -> plain read: {1}" -f $m, (Mask $_.Exception.Message)) }
            # indexed form: risk level x functional complexity level, both commonly 1..3
            $grid = @()
            for ($r = 1; $r -le 3; $r++) {
                $row = @()
                for ($f = 1; $f -le 3; $f++) {
                    $cell = '?'
                    try { $cell = [string](Get-PropArg $rbt $m @($r, $f)) } catch { $cell = 'x' }
                    $row += $cell
                }
                $grid += ($row -join ' ')
            }
            if (($grid -join '') -notmatch '^[x ]*$') {
                Say ("      indexed [risk x complexity] grid:")
                foreach ($g in $grid) { Say ("        " + $g) }
            }
        }
        foreach ($m in @('TestingLevelPercentage','TestingEffortForFCLevel','FPLevelTestingEffortInHours')) {
            for ($i = 1; $i -le 4; $i++) {
                try { Say ("  {0}[{1}] = {2}" -f $m, $i, (Get-PropArg $rbt $m @($i))) } catch { break }
            }
        }
        foreach ($q in @('BIQuestions','FCQuestions','FPQuestions')) {
            try { $lst = Get-Prop $rbt $q; Say ("  {0,-14} count = {1}" -f $q, $(try { $lst.Count } catch { 'n/a' })) }
            catch { Say ("  {0,-14} -> {1}" -f $q, (Mask $_.Exception.Message)) }
        }
    }

    # ---------------------------------------------------------------- #145 / #132 / #133 / #129
    Say ""
    Say "=== #145 KPITypes / #132-#133 ReportProjectTemplates / #129 BusinessViews ==="
    foreach ($pair in @(@('KPITypes','KPITypes'), @('ReportProjectTemplates','ReportProjectTemplates'), @('BusinessViews','BusinessViews'))) {
        $obj = $null
        try { $obj = Get-Prop $cust $pair[0] } catch { }
        if ($obj) {
            try {
                $lst = Get-Prop $obj $pair[1]
                $n = try { $lst.Count } catch { 'n/a' }
                Say ("  Customization.{0}.{1} -> count = {2}" -f $pair[0], $pair[1], $n)
                if ($n -is [int] -and $n -gt 0) {
                    for ($i = 1; $i -le [Math]::Min(5, $n); $i++) {
                        try { $it = $lst.Item($i); Say ("      [{0}] {1}" -f $i, (Mask ([string]$it.Name))) } catch { }
                    }
                }
            } catch { Say ("  Customization.{0}.{1} -> {2}" -f $pair[0], $pair[1], (Mask $_.Exception.Message)) }
        }
    }

    # ---------------------------------------------------------------- #205 data-hiding
    Say ""
    Say "=== #205 per-group per-module visibility ==="
    $mods = $null
    try { $mods = Get-Prop $cust 'Modules' } catch { }
    if ($mods) {
        try {
            $list = Get-Prop $mods 'Modules'
            $n = try { $list.Count } catch { 0 }
            Say ("  module count = {0}" -f $n)
            for ($i = 1; $i -le [Math]::Min(12, $n); $i++) {
                try {
                    $m = $list.Item($i)
                    $nm = [string](Get-Prop $m 'Name')
                    $vis = try { [string](Get-Prop $m 'Visible') } catch { '?' }
                    $vfg = try { $g = Get-Prop $m 'VisibleForGroups'; [string]$g } catch { '?' }
                    Say ("    {0,-24} Visible={1}  VisibleForGroups={2}" -f $nm, $vis, (Mask $vfg))
                } catch { }
            }
        } catch { Say ("  Modules.Modules -> " + (Mask $_.Exception.Message)) }
    }
    $ugroups = $null
    try { $ugroups = Get-Prop $cust 'UsersGroups' } catch { }
    if ($ugroups) { Say ("  Customization.UsersGroups ACQUIRED ({0})" -f $ugroups.GetType().Name) }

    # ---------------------------------------------------------------- #166 alerts
    Say ""
    Say "=== #166 AlertManager.AlertList ==="
    $am = $null
    try { $am = Get-Prop $td 'AlertManager' } catch { }
    if ($am) {
        foreach ($form in @(@(), @(''), @('', ''))) {
            try {
                $al = Get-PropArg $am 'AlertList' $form
                Say ("  AlertList(argc={0}) -> OK, count={1}" -f $form.Count, $(try { $al.Count } catch { 'n/a' }))
                break
            } catch { Say ("  AlertList(argc={0}) -> {1}" -f $form.Count, (Mask $_.Exception.Message)) }
        }
        try { Say ("  GetFilterText() -> " + (Mask ([string](Call $am 'GetFilterText' @())))) } catch { Say ("  GetFilterText() -> " + (Mask $_.Exception.Message)) }
    }

    # ---------------------------------------------------------------- #209/#210 workflow
    Say ""
    Say "=== #209/#210 Customization.Workflow (currently scored CONFIRMED NO) ==="
    $wf = $null
    try { $wf = Get-Prop $cust 'Workflow' } catch { Say ("  Workflow -> " + (Mask $_.Exception.Message)) }
    if ($wf) {
        Say ("  Workflow ACQUIRED ({0})" -f $wf.GetType().Name)
        try {
            $names = @($wf | Get-Member -ErrorAction Stop | Where-Object { $_.MemberType -match 'Method|Property' } |
                       Select-Object -ExpandProperty Name | Sort-Object -Unique)
            Say ("    members [{0}]: {1}" -f $names.Count, ($names -join ', '))
        } catch { }
    }

} catch {
    Say ("FATAL: " + (Mask $_.Exception.Message))
} finally {
    Say ""
    try { if ($projConn) { $td.DisconnectProject() } } catch { }
    try { if ($connected) { $td.Logout(); $td.ReleaseConnection() } } catch { }
    try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($td) } catch { }
    Say "=== PHASE 2 END (nothing created or modified) ==="
}

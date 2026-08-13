# probe-ota-7-paramcheck.ps1 - OTA cross-check helper for probe-write-4b.
#
# Reads a REST-created test's parameters over OTA/COM to confirm the parameter the REST
# <<<token>>> route registered is a real object, and whether OTA can see the default value
# that REST set (OTA itself cannot set one - probe 8 left that UNVERIFIED).
#
# MUST run under 32-bit Windows PowerShell. Invoked by probe-write-4b.ps1.

param([Parameter(Mandatory = $true)][string]$TestId)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$cred = Get-Content (Join-Path $repoRoot 'Secrets\ALM_API_credentials.json') -Raw | ConvertFrom-Json
$url = ([string]$cred.alm_adress).TrimEnd('/'); if ($url -notmatch '/qcbin$') { $url += '/qcbin' }
$apiKey = [string]$cred.api_key; $apiSec = [string]$cred.api_secret
$domain = [string]$cred.domain;  $project = [string]$cred.project
$maskTerms = @($url, $apiKey, $apiSec, $domain, $project, ([Uri]$url).Host) | Where-Object { $_ -and $_.Length -gt 2 }
function Mask([string]$s) { if ($null -eq $s) { return '' }; foreach ($t in $maskTerms) { $s = $s -replace [Regex]::Escape($t), 'REDACTED' }; $s }
function Say([string]$s) { Write-Output (Mask $s) }
function P($o) { return $o.GetType().InvokeMember('Params', 'GetProperty', $null, $o, @()) }

Say ("bitness: {0} bytes/ptr (4 = 32-bit, required)" -f [IntPtr]::Size)

$td = New-Object -ComObject TDApiOle80.TDConnection
$connected = $false; $projConn = $false
try {
    # NOTE: assign connect/login results to $null - they return objects that print to stdout
    # and would bypass Mask().
    $null = $td.InitConnectionWithApiKeyEx($url, $apiKey, $apiSec); $connected = $true
    $null = $td.Connect($domain, $project); $projConn = [bool]$td.ProjectConnected
    Say ("OTA connected={0} projectConnected={1}" -f $connected, $projConn)

    $test = $td.TestFactory.Item([int]$TestId)
    Say ("test loaded: HasParam={0}" -f [string]$test.HasParam)
    $pf = P $test
    Say ("OTA sees Params.Count = {0}" -f $pf.Count)
    $count = [int]$pf.Count
    for ($i = 1; $i -le $count; $i++) {
        $n = '<read failed>'
        try { $n = [string]$pf.ParamName($i) } catch { }
        # ParamValue raises "Invalid field type definition" on this build - REST reads the same
        # default-value fine, so this is an OTA-side limitation, not missing data.
        $v = '<read failed>'
        try { $v = [string]$pf.ParamValue($i) } catch { }
        Say ("   [{0}] name='{1}' value='{2}'" -f $i, (Mask $n), (Mask $v))
    }
    if ($pf.Count -gt 0) { Say "*** OTA CONFIRMS the REST-registered parameter is a real object ***" }
    else { Say "!!! OTA does not see the parameter REST reported" }
} catch {
    Say ("OTA cross-check failed: " + (Mask $_.Exception.Message))
} finally {
    try { if ($projConn) { $td.DisconnectProject() } } catch { }
    try { if ($connected) { $td.Logout(); $td.ReleaseConnection() } } catch { }
    try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($td) } catch { }
}

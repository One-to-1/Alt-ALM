# probe-ota-6.ps1 - OTA/COM spike, PHASE 6: test-parameter persistence (SANDBOX WRITES)
#
# Phase 5: Test.Params.AddParam(name, value) is ACCEPTED and Params.Save() returns OK, but a
# re-read shows Count=0 / HasParam=False - the parameter did not persist. This isolates why:
# checks Count on the same object, then posts the test, then re-reads from a fresh handle, and
# also tries the design-step <<<token>>> route which is what registers a parameter in the UI.
#
# MUST run under 32-bit Windows PowerShell.

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

$prefix = "ALTALM-OTA-" + (Get-Date -Format 'yyyyMMdd-HHmmss')
Say "=== OTA PHASE 6: parameter persistence (prefix $prefix) ==="

$td = New-Object -ComObject TDApiOle80.TDConnection
$folder = $null; $connected = $false; $projConn = $false
try {
    $null = $td.InitConnectionWithApiKeyEx($url, $apiKey, $apiSec); $connected = $true
    $null = $td.Connect($domain, $project); $projConn = [bool]$td.ProjectConnected
    $folder = $td.TreeManager.NodeByPath("Subject").AddNode($prefix); $folder.Post()
    $test = $folder.TestFactory.AddItem([System.DBNull]::Value)
    $test.Name = "$prefix-TEST"; $test.Type = "MANUAL"; $test.Post()
    Say ("test id={0}" -f $test.ID)

    Say ""
    Say "=== A. AddParam -> Save -> count on SAME object ==="
    $pf = P $test
    $null = $pf.GetType().InvokeMember('AddParam', 'InvokeMethod', $null, $pf, @('altalm_param', 'seed-value'))
    Say ("   count immediately after AddParam (before Save): {0}" -f $pf.Count)
    $pf.Save()
    Say ("   count after Save(): {0}" -f $pf.Count)

    Say "=== B. post the test, then re-check the same handle ==="
    try { $test.Post(); Say "   test.Post() OK" } catch { Say ("   test.Post() failed: " + (Mask $_.Exception.Message)) }
    Say ("   count after test.Post(): {0}" -f (P $test).Count)

    Say "=== C. re-read the test from a fresh factory handle ==="
    $fresh = $td.TestFactory.Item($test.ID)
    $fp = P $fresh
    Say ("   fresh handle count: {0}   HasParam={1}" -f $fp.Count, [string]$fresh.HasParam)
    try { $fp.Refresh(); Say ("   after Params.Refresh(): {0}" -f $fp.Count) } catch { Say ("   Refresh failed: " + (Mask $_.Exception.Message)) }

    Say "=== D. does a design-step <<<token>>> register the parameter? ==="
    try {
        $dsf = $test.DesignStepFactory
        $ds = $dsf.AddItem([System.DBNull]::Value)
        $ds.StepName = "Step 1"
        $ds.StepDescription = "Use <<<altalm_param>>> here"
        $ds.Post()
        Say "   design step created with a <<<token>>>"
        $fresh2 = $td.TestFactory.Item($test.ID)
        $fp2 = P $fresh2
        Say ("   parameters after token step: {0}   HasParam={1}" -f $fp2.Count, [string]$fresh2.HasParam)
        for ($i = 1; $i -le $fp2.Count; $i++) { Say ("     - '{0}' = '{1}'" -f $fp2.ParamName($i), $fp2.ParamValue($i)) }
        if ($fp2.Count -gt 0) {
            try {
                $null = $fp2.GetType().InvokeMember('AddParam', 'InvokeMethod', $null, $fp2, @('altalm_param', 'seed-value'))
                $fp2.Save()
                $fp3 = P ($td.TestFactory.Item($test.ID))
                Say ("   after setting value on the registered param: count={0}" -f $fp3.Count)
                for ($i = 1; $i -le $fp3.Count; $i++) { Say ("     - '{0}' = '{1}'" -f $fp3.ParamName($i), $fp3.ParamValue($i)) }
            } catch { Say ("   value set failed: " + (Mask $_.Exception.Message)) }
        }
    } catch { Say ("   design-step route failed: " + (Mask $_.Exception.Message)) }

} finally {
    Say ""
    Say "=== cleanup ==="
    if ($folder) {
        try { $td.TreeManager.NodeByPath("Subject").RemoveNode($folder); Say "   folder deleted" }
        catch { Say ("   folder delete FAILED - sweep via REST: " + (Mask $_.Exception.Message)) }
    }
    try { if ($projConn) { $td.DisconnectProject() } } catch { }
    try { if ($connected) { $td.Logout(); $td.ReleaseConnection() } } catch { }
    try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($td) } catch { }
    Say "=== PHASE 6 END ==="
}

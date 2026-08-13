# probe-ota-5.ps1 - OTA/COM spike, PHASE 5: diagnostics for the last two unknowns (SANDBOX WRITES)
#
# Phase 4 established OTA connects and writes (test folder + test created and deleted). Two things
# remained unresolved because the helper swallowed the real errors:
#   B) Test.Params is the parameter factory, but AddItem rejected DBNull/name/empty - need the
#      actual per-form error and the factory's own member list.
#   C) BPT component create returns "Invalid owner specified: 0" even with ParentId set - need the
#      correct owner accessor (likely the folder object's own ComponentFactory).
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

$prefix = "ALTALM-OTA-" + (Get-Date -Format 'yyyyMMdd-HHmmss')
Say "=== OTA PHASE 5 diagnostics (prefix $prefix) ==="

$td = New-Object -ComObject TDApiOle80.TDConnection
$folder = $null; $connected = $false; $projConn = $false
try {
    $null = $td.InitConnectionWithApiKeyEx($url, $apiKey, $apiSec); $connected = $true
    $null = $td.Connect($domain, $project); $projConn = [bool]$td.ProjectConnected
    Say ("connected={0}" -f $projConn)

    $folder = $td.TreeManager.NodeByPath("Subject").AddNode($prefix)
    $folder.Post()
    $test = $folder.TestFactory.AddItem([System.DBNull]::Value)
    $test.Name = "$prefix-TEST"; $test.Type = "MANUAL"; $test.Post()
    Say ("test id={0}" -f $test.ID)

    # ---------- B. parameter factory diagnostics ----------
    Say ""
    Say "=== B. Test.Params diagnostics ==="
    # Test.Params is NOT a factory - it is a parameter COLLECTION with its own verb set:
    # AddParam / DeleteParam / ParamExist / ParamName / ParamValue / Count / Save.
    $pf = $test.GetType().InvokeMember('Params', 'GetProperty', $null, $test, @())
    Say ("   Params collection members: " + (($pf | Get-Member | Where-Object { $_.MemberType -match 'Method|Property' } | Select-Object -ExpandProperty Name | Sort-Object) -join ', '))
    Say ("   parameters before: {0}" -f $pf.Count)
    $added = $false
    foreach ($form in @(
            @{ l = 'AddParam(name, value)'; a = @('altalm_param', 'seed-value') },
            @{ l = 'AddParam(name)';        a = @('altalm_param') })) {
        if ($added) { break }
        try {
            $null = $pf.GetType().InvokeMember('AddParam', 'InvokeMethod', $null, $pf, $form.a)
            Say ("   {0} accepted" -f $form.l)
            try { $pf.Save(); Say "   Params.Save() OK" } catch { Say ("   Save failed: " + (Mask $_.Exception.Message)) }
            $added = $true
        } catch {
            Say ("   {0} failed: {1}" -f $form.l, (Mask $_.Exception.Message))
        }
    }
    if ($added) {
        # re-read from a FRESH object to prove it persisted server-side
        try {
            $reread = $td.TestFactory.Item($test.ID)
            $rp = $reread.GetType().InvokeMember('Params', 'GetProperty', $null, $reread, @())
            Say ("   *** VERIFIED after re-read: parameter count = {0} ***" -f $rp.Count)
            for ($i = 1; $i -le $rp.Count; $i++) {
                Say ("     - name='{0}' value='{1}'" -f $rp.ParamName($i), $rp.ParamValue($i))
            }
            Say ("   test.HasParam = " + [string]$reread.HasParam)
        } catch { Say ("   re-read verification failed: " + (Mask $_.Exception.Message)) }
    }

    # ---------- C. BPT owner diagnostics ----------
    Say ""
    Say "=== C. BPT component owner diagnostics ==="
    $cff = $td.ComponentFolderFactory
    $fl = $cff.NewList("")
    Say ("   component folders: {0}" -f $fl.Count)
    if ($fl.Count -gt 0) {
        $f1 = $fl.Item(1)
        Say ("   folder[1] members: " + (($f1 | Get-Member | Where-Object { $_.MemberType -match 'Method|Property' } | Select-Object -ExpandProperty Name | Sort-Object) -join ', '))
        try { Say ("   folder[1] id={0} name={1}" -f $f1.ID, $f1.Name) } catch { }
        # preferred pattern: ask the FOLDER for its component factory (mirrors TestFolder.TestFactory)
        # Components cannot sit directly under the root COMPONENTS folder - create a subfolder first.
        $subFolder = $null
        try {
            $sff = $f1.GetType().InvokeMember('ComponentFolderFactory', 'GetProperty', $null, $f1, @())
            $subFolder = $sff.AddItem([System.DBNull]::Value)
            $subFolder.Name = "$prefix-CFOLDER"
            $subFolder.Post()
            Say ("   component SUBFOLDER created id={0}" -f $subFolder.ID)
        } catch { Say ("   subfolder create failed: " + (Mask $_.Exception.Message)) }

        if ($subFolder) {
            try {
                $fcf = $subFolder.GetType().InvokeMember('ComponentFactory', 'GetProperty', $null, $subFolder, @())
                $comp = $fcf.AddItem([System.DBNull]::Value)
                $comp.Name = "$prefix-COMP"
                $comp.Post()
                Say ("   *** BPT COMPONENT CREATED id={0} - BPT IS WRITABLE VIA OTA ***" -f $comp.ID)
                try { $fcf.RemoveItem($comp.ID); Say "   (component deleted)" } catch { Say ("   component delete failed: " + (Mask $_.Exception.Message)) }
            } catch {
                Say ("   component create failed: " + (Mask $_.Exception.Message))
            }
            try { $sff.RemoveItem($subFolder.ID); Say "   (subfolder deleted)" } catch { Say ("   subfolder delete failed: " + (Mask $_.Exception.Message)) }
        }
    }
} finally {
    Say ""
    Say "=== cleanup ==="
    $deleted = $false
    if ($folder) {
        try { $td.TreeManager.NodeByPath("Subject").RemoveNode($folder); $deleted = $true; Say "   folder deleted" }
        catch { Say ("   folder delete FAILED: " + (Mask $_.Exception.Message)) }
        if (-not $deleted) { try { Say ("   !! sweep test-folders id " + $folder.NodeID + " via REST") } catch { } }
    }
    try { if ($projConn) { $td.DisconnectProject() } } catch { }
    try { if ($connected) { $td.Logout(); $td.ReleaseConnection() } } catch { }
    try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($td) } catch { }
    Say "=== PHASE 5 END ==="
}

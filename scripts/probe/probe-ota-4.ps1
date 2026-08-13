# probe-ota-4.ps1 - OTA/COM spike, PHASE 4: THE decisive capability probe (SANDBOX WRITES)
#
# Phase 1 (rerun, after the properly deployed 26.1 client was registered) established that OTA
# CONNECTS via InitConnectionWithApiKeyEx - Connected/LoggedIn/ProjectConnected all True.
# This phase answers what that buys us:
#   Q18  - can OTA *define* a test parameter? (REST cannot: 5 failed attempts, 2 rounds)
#   BPT  - is ComponentFactory usable, or licence-gated as REST's 403 suggested?
#   plus presence/usability of the other OTA-only candidates from the feasibility matrix.
#
# WRITES: creates an ALTALM-OTA-<ts> test folder + one test (+ a parameter) in the SANDBOX ONLY,
# and deletes everything in the finally block. Sandbox designated by the user 2026-08-12.
#
# MUST run under 32-bit Windows PowerShell.

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
function Say([string]$s) { Write-Output (Mask $s) }

# OTA factories take AddItem(Null) in VBScript. PowerShell cannot pass VT_NULL as $null or
# Missing.Value (both -> "Value does not fall within the expected range"); DBNull marshals
# correctly. Some factories also accept the item name/key directly, so fall back to that.
function New-OtaItem($factory, [string]$name) {
    try { return $factory.AddItem([System.DBNull]::Value) } catch { }
    try { return $factory.AddItem($name) } catch { }
    try { return $factory.AddItem("") } catch { }
    throw "AddItem rejected DBNull, name and empty-string forms"
}

$stamp  = Get-Date -Format 'yyyyMMdd-HHmmss'
$prefix = "ALTALM-OTA-$stamp"

Say "=== OTA PHASE 4: capability probe (sandbox writes, prefix $prefix) ==="
if ([IntPtr]::Size -eq 8) { Say "FATAL: run under 32-bit PowerShell"; exit 1 }

$td = New-Object -ComObject TDApiOle80.TDConnection
$connected = $false; $projConn = $false
$createdFolder = $null

try {
    $null = $td.InitConnectionWithApiKeyEx($url, $apiKey, $apiSec)
    $connected = [bool]$td.Connected
    $null = $td.Connect($domain, $project)
    $projConn = [bool]$td.ProjectConnected
    Say ("connected={0} projectConnected={1}" -f $connected, $projConn)
    if (-not $projConn) { exit 2 }

    # ---------- A. create a test to work against ----------
    Say ""
    Say "=== A. create test folder + test (OTA writes) ==="
    $tm = $td.TreeManager
    $subject = $tm.NodeByPath("Subject")
    Say ("   Subject node acquired: " + [bool]$subject)
    $createdFolder = $subject.AddNode($prefix)
    $createdFolder.Post()
    Say ("   folder created via OTA: {0} (nodeId={1})" -f $prefix, $createdFolder.NodeID)

    $tf = $createdFolder.TestFactory
    $test = New-OtaItem $tf "$prefix-TEST"
    $test.Name = "$prefix-TEST"
    $test.Type = "MANUAL"
    $test.Post()
    Say ("   test created via OTA: id={0} name={1}-TEST" -f $test.ID, $prefix)

    # ---------- B. THE question: define a test parameter ----------
    Say ""
    Say "=== B. TEST PARAMETER DEFINITION (REST cannot do this) ==="
    $tMembers = $test | Get-Member | Select-Object -ExpandProperty Name
    $paramish = $tMembers | Where-Object { $_ -match 'Param' }
    Say ("   Test members matching 'Param': " + $(if ($paramish) { ($paramish -join ', ') } else { 'NONE' }))

    # The live object model exposes Params (a TestParameterFactory), NOT TestParameterFactory
    # directly on Test - discovered by enumerating members above.
    $tpf = $null
    foreach ($accessor in 'Params','TestParameterFactory') {
        if ($tpf) { break }
        try {
            $cand = $test.GetType().InvokeMember($accessor, 'GetProperty', $null, $test, @())
            if ($cand) { $tpf = $cand; Say ("   parameter factory obtained via Test.$accessor") }
        } catch { Say ("   Test.$accessor not usable: " + (Mask $_.Exception.Message)) }
    }
    if ($tpf) {
        Say "   TestParameterFactory acquired OK"
        try {
            $tp = New-OtaItem $tpf "altalm_param"
            $tpm = $tp | Get-Member | Select-Object -ExpandProperty Name
            Say ("   TestParameter members: " + (($tpm | Sort-Object) -join ', '))
            $tp.Name = "altalm_param"
            try { $tp.DefaultValue = "seed-value" } catch { Say ("   DefaultValue set failed: " + (Mask $_.Exception.Message)) }
            try { $tp.Description  = "created by ALTALM OTA spike" } catch { }
            $tp.Post()
            Say ("   *** PARAMETER CREATED via OTA: id={0} name={1} ***" -f $tp.ID, $tp.Name)

            # verify by re-reading through a fresh list
            $verify = $tpf.NewList("")
            Say ("   verification - parameters now on this test: {0}" -f $verify.Count)
            foreach ($v in $verify) { Say ("     - name={0} default='{1}'" -f $v.Name, $v.DefaultValue) }
        } catch {
            Say ("   parameter creation FAILED: " + (Mask $_.Exception.Message))
        }
    }

    # ---------- C. BPT ----------
    Say ""
    Say "=== C. BPT / business components (REST returned 403) ==="
    try {
        $cf = $td.ComponentFactory
        $cl = $cf.NewList("")
        Say ("   ComponentFactory OK, existing components: {0}" -f $cl.Count)
        # "Invalid owner specified: 0" on the first attempt means a parent COMPONENT FOLDER is
        # required - the same class of error as milestones needing a release. Find/So use one.
        $ownerId = $null
        try {
            $cff = $td.ComponentFolderFactory
            $fl = $cff.NewList("")
            Say ("   component folders visible: {0}" -f $fl.Count)
            if ($fl.Count -gt 0) { $ownerId = $fl.Item(1).ID; Say ("   using existing component folder id={0}" -f $ownerId) }
            else {
                try { $root = $cff.Root; if ($root) { $ownerId = $root.ID; Say ("   using ComponentFolderFactory.Root id={0}" -f $ownerId) } } catch { }
            }
        } catch { Say ("   ComponentFolderFactory failed: " + (Mask $_.Exception.Message)) }

        try {
            $comp = New-OtaItem $cf "$prefix-COMP"
            $comp.Name = "$prefix-COMP"
            if ($ownerId) { try { $comp.ParentId = $ownerId } catch { try { $comp.FolderId = $ownerId } catch { } } }
            $comp.Post()
            Say ("   *** BPT COMPONENT CREATED via OTA: id={0} - BPT IS REACHABLE ***" -f $comp.ID)
            try { $cf.RemoveItem($comp.ID); Say "   (component deleted)" } catch { Say ("   component delete failed: " + (Mask $_.Exception.Message)) }
        } catch {
            Say ("   component create FAILED: " + (Mask $_.Exception.Message))
            Say "   >> distinguish: 'Invalid owner' = needs a parent folder; a licence/permission"
            Say "      message = the same gate REST's 403 hit."
        }
    } catch {
        Say ("   ComponentFactory FAILED: " + (Mask $_.Exception.Message))
    }

    # ---------- D. other OTA-only feasibility-matrix candidates ----------
    Say ""
    Say "=== D. other OTA-only candidates (acquire + count, read-only) ==="
    foreach ($n in 'BaselineFactory','LibraryFactory','HostFactory','HostGroupFactory','MilestoneFactory','KPIFactory','ScopeItemFactory') {
        try {
            $fac = $td.GetType().InvokeMember($n, 'GetProperty', $null, $td, @())
            $lst = $fac.NewList("")
            Say ("   {0}: ACQUIRED, items visible = {1}" -f $n, $lst.Count)
        } catch {
            Say ("   {0}: failed -> {1}" -f $n, (Mask $_.Exception.Message))
        }
    }
    foreach ($n in 'PurgeRuns','PurgeRuns2','SynchronizeFollowUps','AlertManager','ExtendedStorage') {
        Say ("   {0} present on TDConnection: {1}" -f $n, ($td | Get-Member | Where-Object { $_.Name -eq $n } | Measure-Object).Count)
    }
    try {
        $ext = $td.GetExtensions()
        $names = @(); foreach ($e in $ext) { $names += [string]$e }
        Say ("   enabled extensions: " + ($names -join ', '))
    } catch { Say ("   GetExtensions failed: " + (Mask $_.Exception.Message)) }

} finally {
    # ---------- cleanup: remove everything created ----------
    Say ""
    Say "=== cleanup ==="
    $folderId = $null
    try { if ($createdFolder) { $folderId = $createdFolder.NodeID } } catch { }
    $deleted = $false
    if ($createdFolder) {
        # RemoveNode takes the NODE OBJECT, not its id (passing the id is read as a child index).
        try {
            $parent = $td.TreeManager.NodeByPath("Subject")
            $parent.RemoveNode($createdFolder)
            $deleted = $true
            Say "   test folder (and its test) deleted via OTA"
        } catch { Say ("   OTA folder delete failed: " + (Mask $_.Exception.Message)) }
    }
    if ($createdFolder -and -not $deleted) {
        Say ("   !! CLEANUP INCOMPLETE - test-folder id {0} still exists; sweep it via REST:" -f $folderId)
        Say ("      DELETE /rest/domains/<d>/projects/<p>/test-folders/{0}" -f $folderId)
    }
    try { if ($projConn) { $td.DisconnectProject() } } catch { }
    try { if ($connected) { $td.Logout(); $td.ReleaseConnection() } } catch { }
    try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($td) } catch { }
    Say "=== PHASE 4 END ==="
}

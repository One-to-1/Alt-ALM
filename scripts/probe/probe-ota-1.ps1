# probe-ota-1.ps1 - OTA/COM spike, PHASE 1: connectivity + capability discovery (READ-ONLY)
#
# Answers: can OTA connect to this ALM instance at all, and with an API key rather than a
# username/password? Then enumerates the OTA object model for the capabilities REST cannot reach
# (test-parameter definition, BPT components).
#
# NO WRITES. This script creates nothing and deletes nothing.
#
# MUST run under 32-bit Windows PowerShell - TDApiOle80 is a 32-bit COM server and instantiation
# from a 64-bit host fails with 0x80040154 REGDB_E_CLASSNOTREG (verified on this machine):
#   C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe -NoProfile -File <this script>
#
# Credentials are read at runtime from Secrets/ALM_API_credentials.json and never printed.
# Every output line passes through Mask().

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Continue'

# --- locate repo root (script lives in scripts/probe/) ---
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$credPath = Join-Path $repoRoot 'Secrets\ALM_API_credentials.json'
if (-not (Test-Path $credPath)) { Write-Output "FATAL: credentials file not found at the expected path"; exit 1 }
$cred = Get-Content $credPath -Raw | ConvertFrom-Json

$almUrl  = [string]$cred.alm_adress
$apiKey  = [string]$cred.api_key
$apiSec  = [string]$cred.api_secret
$domain  = [string]$cred.domain
$project = [string]$cred.project

# --- masking: nothing sensitive may reach stdout ---
$maskTerms = @($almUrl, $apiKey, $apiSec, $domain, $project) | Where-Object { $_ -and $_.Length -gt 2 }
# also mask the bare host, in case the URL carries a scheme/path
try { $maskTerms += ([Uri]$almUrl).Host } catch { }
function Mask([string]$s) {
    if ($null -eq $s) { return '' }
    foreach ($t in $maskTerms) { if ($t) { $s = $s -replace [Regex]::Escape($t), 'REDACTED' } }
    return $s
}
function Say([string]$s) { Write-Output (Mask $s) }

Say "=== OTA SPIKE PHASE 1 (read-only) ==="
Say ("host process bitness: {0}-bit" -f $(if ([IntPtr]::Size -eq 8) { 64 } else { 32 }))
if ([IntPtr]::Size -eq 8) { Say "FATAL: must run under 32-bit PowerShell (see header)"; exit 1 }

# --- 1. instantiate ---
$td = $null
try {
    $td = New-Object -ComObject TDApiOle80.TDConnection
    Say "1. instantiate TDApiOle80.TDConnection : OK"
} catch {
    Say ("1. instantiate : FAILED : " + $_.Exception.Message); exit 1
}

$connected = $false
$loggedIn  = $false
$projConn  = $false

try {
    $url = $almUrl.TrimEnd('/')
    if ($url -notmatch '/qcbin$') { $url = $url + '/qcbin' }

    # --- 2+3. API-key auth. The 26.1 OTA client exposes InitConnectionWithApiKeyEx, which
    # establishes the connection AND authenticates in one call - no username/password needed.
    # Fall back to the classic InitConnectionEx + Login(user,pass) shape if it is absent. ---
    try {
        $null = $td.InitConnectionWithApiKeyEx($url, $apiKey, $apiSec)
        $connected = [bool]$td.Connected
        $loggedIn  = [bool]$td.LoggedIn
        Say ("2. InitConnectionWithApiKeyEx(url, clientId, secret) : Connected={0} LoggedIn={1}" -f $connected, $loggedIn)
    } catch {
        Say ("2. InitConnectionWithApiKeyEx : FAILED : " + $_.Exception.Message)
        try {
            $null = $td.InitConnectionEx($url)
            $connected = [bool]$td.Connected
            Say ("2b. InitConnectionEx fallback : Connected={0}" -f $connected)
            $null = $td.Login($apiKey, $apiSec)
            $loggedIn = [bool]$td.LoggedIn
            Say ("3b. Login(api_key, api_secret) : LoggedIn={0}" -f $loggedIn)
        } catch {
            Say ("3b. fallback auth FAILED : " + $_.Exception.Message)
        }
    }
    if (-not $connected) { Say "2. no connection established - stopping"; exit 2 }

    $srvVer = 'n/a'
    try { $srvVer = [string]$td.ServerVersion } catch { }
    Say ("   server-reported version: " + $srvVer)

    if (-not $loggedIn) { Say "3. not logged in - cannot probe the object model; stopping"; exit 3 }

    # --- 4. project connect ---
    try {
        $null = $td.Connect($domain, $project)
        $projConn = [bool]$td.ProjectConnected
        Say ("4. Connect(domain, project) : ProjectConnected={0}" -f $projConn)
    } catch {
        Say ("4. Connect : FAILED : " + $_.Exception.Message)
    }
    if (-not $projConn) { Say "4. project not connected - stopping"; exit 4 }

    # --- 5. capability discovery: which factories does this connection expose? ---
    Say ""
    Say "=== 5. TDConnection members (names only) ==="
    $members = $td | Get-Member | Where-Object { $_.MemberType -match 'Method|Property' } | Select-Object -ExpandProperty Name
    Say (($members | Sort-Object) -join ', ')

    # --- 6. THE key question: test-parameter support ---
    Say ""
    Say "=== 6. test-parameter capability ==="
    foreach ($f in 'TestParameterFactory','TestFactory') {
        $present = $members -contains $f
        Say ("   TDConnection.{0} present: {1}" -f $f, $present)
    }
    try {
        $tf = $td.TestFactory
        Say "   TestFactory acquired: OK"
        # read-only: fetch at most one existing test and inspect its parameter surface
        $tl = $tf.NewList("")
        Say ("   existing tests visible: {0}" -f $tl.Count)
        if ($tl.Count -gt 0) {
            $t = $tl.Item(1)
            $tMembers = $t | Get-Member | Select-Object -ExpandProperty Name
            $paramish = $tMembers | Where-Object { $_ -match 'Param' }
            Say ("   Test object parameter-related members: " + $(if ($paramish) { ($paramish -join ', ') } else { 'NONE FOUND' }))
            Say ("   Test object members (full): " + (($tMembers | Sort-Object) -join ', '))
        } else {
            Say "   (no existing tests to inspect - phase 2 will create one)"
        }
    } catch {
        Say ("   TestFactory probe FAILED: " + $_.Exception.Message)
    }

    # --- 7. BPT: is it licence-gated for COM too? ---
    Say ""
    Say "=== 7. BPT / business components ==="
    foreach ($f in 'ComponentFactory','BusinessComponentFactory') {
        Say ("   TDConnection.{0} present: {1}" -f $f, ($members -contains $f))
    }
    try {
        $cf = $td.ComponentFactory
        $cl = $cf.NewList("")
        Say ("   ComponentFactory acquired, components visible: {0}  << BPT reachable via COM" -f $cl.Count)
    } catch {
        Say ("   ComponentFactory FAILED: " + $_.Exception.Message)
        Say "   >> if this is a licence error, BPT is out of reach for every API, not just REST"
    }

    # --- 8. other OTA-only candidates, presence check only ---
    Say ""
    Say "=== 8. other OTA-only capability candidates (presence only) ==="
    foreach ($f in 'BaselineFactory','LibraryFactory','AlertFactory','TimeslotFactory','FollowUpFactory','CustomizationFactory','ExtendedStorage') {
        Say ("   {0}: {1}" -f $f, ($members -contains $f))
    }

} finally {
    # --- always disconnect cleanly; sessions are visible server-side ---
    try { if ($projConn) { $td.DisconnectProject() ; Say "`ncleanup: DisconnectProject OK" } } catch { Say ("cleanup: DisconnectProject failed: " + $_.Exception.Message) }
    try { if ($loggedIn) { $td.Logout()           ; Say "cleanup: Logout OK" } }          catch { Say ("cleanup: Logout failed: " + $_.Exception.Message) }
    try { if ($connected) { $td.ReleaseConnection(); Say "cleanup: ReleaseConnection OK" } } catch { Say ("cleanup: ReleaseConnection failed: " + $_.Exception.Message) }
    try { if ($td) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($td) } } catch { }
    Say "=== PHASE 1 END ==="
}

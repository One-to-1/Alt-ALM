# probe-ota-2.ps1 - OTA/COM spike, PHASE 2: cookie-bridged auth + capability discovery (READ-ONLY)
#
# Phase 1 established:
#   * OTA is 32-bit only (64-bit instantiation -> 0x80040154).
#   * The machine-registered client was 12.53 (2017); the 26.1 client is registered per-user.
#   * Direct OTA auth fails with "Invalid server response" because this SaaS instance redirects
#     /qcbin/servlet/tdservlet/TdServlet (302) to /authentication-point/discovery.jsp (SSO).
#     The OTA client cannot negotiate that redirect.
#
# This phase bridges the two APIs: authenticate over REST (fully verified route), then hand the
# resulting session cookies to OTA via InitConnectionWithCookies().
#
# NO WRITES. Read-only discovery only.
#
# MUST run under 32-bit Windows PowerShell:
#   C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe -NoProfile -File <this script>

$ErrorActionPreference = 'Continue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$cred = Get-Content (Join-Path $repoRoot 'Secrets\ALM_API_credentials.json') -Raw | ConvertFrom-Json
$almUrl  = ([string]$cred.alm_adress).TrimEnd('/')
if ($almUrl -notmatch '/qcbin$') { $almUrl = $almUrl + '/qcbin' }
$apiKey  = [string]$cred.api_key
$apiSec  = [string]$cred.api_secret
$domain  = [string]$cred.domain
$project = [string]$cred.project

$maskTerms = @($almUrl, $apiKey, $apiSec, $domain, $project)
try { $maskTerms += ([Uri]$almUrl).Host } catch { }
$maskTerms = $maskTerms | Where-Object { $_ -and $_.Length -gt 2 }
function Mask([string]$s) {
    if ($null -eq $s) { return '' }
    foreach ($t in $maskTerms) { if ($t) { $s = $s -replace [Regex]::Escape($t), 'REDACTED' } }
    # cookie VALUES are session secrets - never echo them
    $s = $s -replace '(LWSSO_COOKIE_KEY|QCSession|XSRF-TOKEN|ALM_USER|JSESSIONID)=[^;\s"]+', '$1=REDACTED'
    return $s
}
function Say([string]$s) { Write-Output (Mask $s) }

Say "=== OTA SPIKE PHASE 2: REST-cookie bridge (read-only) ==="
if ([IntPtr]::Size -eq 8) { Say "FATAL: must run under 32-bit PowerShell"; exit 1 }

# --- 1. authenticate over REST (the verified route) ---
$sess = $null
try {
    $body = (@{ clientId = $apiKey; secret = $apiSec } | ConvertTo-Json -Compress)
    $null = Invoke-WebRequest -Uri "$almUrl/rest/oauth2/login" -Method Post -Body $body `
            -ContentType 'application/json' -SessionVariable sess -TimeoutSec 60 -UseBasicParsing
    Say "1. REST oauth2/login : OK"
} catch {
    Say ("1. REST oauth2/login FAILED : " + $_.Exception.Message); exit 1
}

$cookies = $sess.Cookies.GetCookies([Uri]$almUrl)
$names = @(); foreach ($c in $cookies) { $names += $c.Name }
Say ("   cookies obtained: " + ($names -join ', '))
if ($names.Count -eq 0) { Say "   no cookies - cannot bridge"; exit 1 }

function Get-CookieValue([string]$name) {
    foreach ($c in $cookies) { if ($c.Name -eq $name) { return $c.Value } }
    return $null
}
$lwsso = Get-CookieValue 'LWSSO_COOKIE_KEY'
$qcs   = Get-CookieValue 'QCSession'

# candidate cookie-string encodings for InitConnectionWithCookies
$allPairs = @(); foreach ($c in $cookies) { $allPairs += ($c.Name + '=' + $c.Value) }
$candidates = @()
if ($lwsso) { $candidates += @{ label = 'LWSSO only';      value = "LWSSO_COOKIE_KEY=$lwsso" } }
if ($lwsso -and $qcs) { $candidates += @{ label = 'LWSSO+QCSession'; value = "LWSSO_COOKIE_KEY=$lwsso; QCSession=$qcs" } }
$candidates += @{ label = 'all cookies';     value = ($allPairs -join '; ') }
if ($lwsso) { $candidates += @{ label = 'bare LWSSO value'; value = $lwsso } }

# --- 2. bridge into OTA ---
$td = New-Object -ComObject TDApiOle80.TDConnection
Say "2. TDConnection instantiated"

$connected = $false; $loggedIn = $false; $projConn = $false
foreach ($cand in $candidates) {
    foreach ($method in 'InitConnectionWithCookies','InitConnectionWithCookiesEx') {
        try {
            $null = $td.GetType().InvokeMember($method, 'InvokeMethod', $null, $td, @($almUrl, $cand.value))
            $connected = [bool]$td.Connected
            try { $loggedIn = [bool]$td.LoggedIn } catch { $loggedIn = $false }
            Say ("   {0} via [{1}] : Connected={2} LoggedIn={3}" -f $method, $cand.label, $connected, $loggedIn)
            if ($connected) { break }
        } catch {
            Say ("   {0} via [{1}] : FAILED : {2}" -f $method, $cand.label, (Mask $_.Exception.Message))
        }
    }
    if ($connected) { break }
}

if (-not $connected) {
    Say "2. cookie bridge did not establish a connection - stopping"
    Say "   >> OTA may be unreachable on this SaaS deployment; see the probe log write-up."
    exit 2
}

try {
    # --- 3. project connect + capability discovery ---
    $td.Connect($domain, $project)
    $projConn = [bool]$td.ProjectConnected
    Say ("3. Connect(domain, project) : ProjectConnected={0}" -f $projConn)
    if (-not $projConn) { exit 3 }

    $members = $td | Get-Member | Where-Object { $_.MemberType -match 'Method|Property' } | Select-Object -ExpandProperty Name

    Say ""
    Say "=== 4. test-parameter capability (THE question REST could not answer) ==="
    try {
        $tf = $td.TestFactory
        $tl = $tf.NewList("")
        Say ("   tests visible: {0}" -f $tl.Count)
        if ($tl.Count -gt 0) {
            $t = $tl.Item(1)
            $tm = $t | Get-Member | Select-Object -ExpandProperty Name
            $pm = $tm | Where-Object { $_ -match 'Param' }
            Say ("   Test parameter-related members: " + $(if ($pm) { ($pm -join ', ') } else { 'NONE' }))
            try {
                $tpf = $t.TestParameterFactory
                $tpl = $tpf.NewList("")
                Say ("   TestParameterFactory acquired OK; existing parameters on this test: {0}" -f $tpl.Count)
                Say "   >> a REST-unreachable capability IS reachable over OTA (creation to be proven in phase 3)"
                if ($tpl.Count -gt 0) {
                    $tp = $tpl.Item(1)
                    Say ("   TestParameter members: " + (($tp | Get-Member | Select-Object -ExpandProperty Name | Sort-Object) -join ', '))
                }
            } catch {
                Say ("   TestParameterFactory FAILED: " + (Mask $_.Exception.Message))
            }
        }
    } catch {
        Say ("   TestFactory FAILED: " + (Mask $_.Exception.Message))
    }

    Say ""
    Say "=== 5. BPT: same licence gate as REST, or not? ==="
    try {
        $cf = $td.ComponentFactory
        $cl = $cf.NewList("")
        Say ("   ComponentFactory OK; components visible: {0}   << BPT REACHABLE over OTA" -f $cl.Count)
    } catch {
        Say ("   ComponentFactory FAILED: " + (Mask $_.Exception.Message))
        Say "   >> if this is a licence error, BPT is closed to every API, not just REST"
    }

    Say ""
    Say "=== 6. other OTA-only candidates (presence only) ==="
    foreach ($f in 'BaselineFactory','LibraryFactory','AlertFactory','TimeslotFactory','CustomizationFactory','ExtendedStorage','ReqFactory','DefectFactory') {
        Say ("   {0}: {1}" -f $f, ($members -contains $f))
    }

} finally {
    try { if ($projConn)  { $td.DisconnectProject(); Say "`ncleanup: DisconnectProject OK" } } catch { Say ("cleanup: DisconnectProject failed: " + (Mask $_.Exception.Message)) }
    try { if ($loggedIn)  { $td.Logout() } } catch { }
    try { if ($connected) { $td.ReleaseConnection(); Say "cleanup: ReleaseConnection OK" } } catch { Say ("cleanup: ReleaseConnection failed: " + (Mask $_.Exception.Message)) }
    try { $null = Invoke-WebRequest -Uri "$almUrl/authentication-point/logout" -WebSession $sess -TimeoutSec 30 -UseBasicParsing; Say "cleanup: REST logout OK" } catch { }
    try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($td) } catch { }
    Say "=== PHASE 2 END ==="
}

# probe-ota-3.ps1 - OTA/COM spike, PHASE 3: cookie-application sequence matrix (READ-ONLY)
#
# Phase 2 established that the OTA transport endpoint (/qcbin/servlet/tdservlet/TdServlet) returns
# HTTP 200 to an authenticated REST session, so OTA is NOT blocked on this SaaS deployment - the
# client is simply not presenting the session. This phase tries every documented order of applying
# a REST-obtained session to the OTA client.
#
# NO WRITES. MUST run under 32-bit Windows PowerShell.

$ErrorActionPreference = 'Continue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$cred = Get-Content (Join-Path $repoRoot 'Secrets\ALM_API_credentials.json') -Raw | ConvertFrom-Json
$almUrl = ([string]$cred.alm_adress).TrimEnd('/'); if ($almUrl -notmatch '/qcbin$') { $almUrl += '/qcbin' }
$apiKey = [string]$cred.api_key; $apiSec = [string]$cred.api_secret
$domain = [string]$cred.domain;  $project = [string]$cred.project

$maskTerms = @($almUrl, $apiKey, $apiSec, $domain, $project, ([Uri]$almUrl).Host) | Where-Object { $_ -and $_.Length -gt 2 }
function Mask([string]$s) {
    if ($null -eq $s) { return '' }
    foreach ($t in $maskTerms) { $s = $s -replace [Regex]::Escape($t), 'REDACTED' }
    $s -replace '(LWSSO_COOKIE_KEY|QCSession|XSRF-TOKEN|ALM_USER|JSESSIONID)=[^;\s"]+', '$1=REDACTED'
}
function Say([string]$s) { Write-Output (Mask $s) }

Say "=== OTA SPIKE PHASE 3: cookie-application sequence matrix ==="

# --- REST login, fresh session per attempt is safer; get one now for cookie values ---
$body = (@{ clientId = $apiKey; secret = $apiSec } | ConvertTo-Json -Compress)
$null = Invoke-WebRequest -Uri "$almUrl/rest/oauth2/login" -Method Post -Body $body -ContentType 'application/json' -SessionVariable sess -TimeoutSec 60 -UseBasicParsing
$cookies = $sess.Cookies.GetCookies([Uri]$almUrl)
function CV([string]$n) { foreach ($c in $cookies) { if ($c.Name -eq $n) { return $c.Value } } ; return $null }
$lwsso = CV 'LWSSO_COOKIE_KEY'; $qcs = CV 'QCSession'; $almu = CV 'ALM_USER'; $jsess = CV 'JSESSIONID'
Say ("REST login OK; have LWSSO={0} QCSession={1} ALM_USER={2}" -f [bool]$lwsso, [bool]$qcs, [bool]$almu)

function Report($label, $td) {
    $c = $false; $l = $false; $p = $false
    try { $c = [bool]$td.Connected } catch { }
    try { $l = [bool]$td.LoggedIn } catch { }
    try { $p = [bool]$td.ProjectConnected } catch { }
    Say ("   -> Connected={0} LoggedIn={1} ProjectConnected={2}" -f $c, $l, $p)
    return $c
}

# --- sequence A: ApplyCookie(s) BEFORE InitConnectionEx ---
Say ""
Say "A. ApplyCookie(...) then InitConnectionEx"
foreach ($form in @(
        @{ l='LWSSO pair';        v="LWSSO_COOKIE_KEY=$lwsso" },
        @{ l='LWSSO+QCSession';   v="LWSSO_COOKIE_KEY=$lwsso; QCSession=$qcs" },
        @{ l='all four';          v="JSESSIONID=$jsess; LWSSO_COOKIE_KEY=$lwsso; QCSession=$qcs; ALM_USER=$almu" })) {
    $td = New-Object -ComObject TDApiOle80.TDConnection
    try {
        $null = $td.ApplyCookie($form.v)
        Say ("   ApplyCookie[{0}] accepted" -f $form.l)
        try { $td.InitConnectionEx($almUrl); Say "   InitConnectionEx returned" } catch { Say ("   InitConnectionEx FAILED: " + (Mask $_.Exception.Message)) }
        if (Report $form.l $td) {
            try { $td.Connect($domain, $project); Say ("   Connect -> ProjectConnected=" + [bool]$td.ProjectConnected) } catch { Say ("   Connect FAILED: " + (Mask $_.Exception.Message)) }
        }
    } catch {
        Say ("   ApplyCookie[{0}] FAILED: {1}" -f $form.l, (Mask $_.Exception.Message))
    }
    try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($td) } catch { }
}

# --- sequence B: InitConnectionWithCookies then Connect regardless of the Connected flag ---
Say ""
Say "B. InitConnectionWithCookies then Connect() regardless of flags"
foreach ($form in @(
        @{ l='LWSSO pair';      v="LWSSO_COOKIE_KEY=$lwsso" },
        @{ l='LWSSO+QCSession'; v="LWSSO_COOKIE_KEY=$lwsso; QCSession=$qcs" })) {
    $td = New-Object -ComObject TDApiOle80.TDConnection
    try {
        $null = $td.InitConnectionWithCookies($almUrl, $form.v)
        Say ("   InitConnectionWithCookies[{0}] returned" -f $form.l)
        [void](Report $form.l $td)
        try { $td.Connect($domain, $project); Say ("   Connect -> ProjectConnected=" + [bool]$td.ProjectConnected) }
        catch { Say ("   Connect FAILED: " + (Mask $_.Exception.Message)) }
    } catch { Say ("   FAILED: " + (Mask $_.Exception.Message)) }
    try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($td) } catch { }
}

# --- sequence C: authentication token route ---
Say ""
Say "C. GetAuthenticationToken / LoginWithAuthenticationToken"
$td = New-Object -ComObject TDApiOle80.TDConnection
try {
    try { $td.InitConnectionEx($almUrl) } catch { Say ("   InitConnectionEx FAILED: " + (Mask $_.Exception.Message)) }
    try {
        $tok = $td.GetAuthenticationToken($lwsso)
        Say ("   GetAuthenticationToken returned a value: " + [bool]$tok)
        $td.LoginWithAuthenticationToken($tok)
        [void](Report 'token' $td)
    } catch { Say ("   token route FAILED: " + (Mask $_.Exception.Message)) }
} finally { try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($td) } catch { } }

# --- always drop the REST session ---
try { $null = Invoke-WebRequest -Uri "$almUrl/authentication-point/logout" -WebSession $sess -TimeoutSec 30 -UseBasicParsing } catch { }
Say ""
Say "=== PHASE 3 END (REST session logged out) ==="

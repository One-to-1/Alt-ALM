#Requires -Version 7
<#
.SYNOPSIS
  Write-probe round 4b - follow-ups to round 4's test-parameter breakthrough. Sandbox only.

.DESCRIPTION
  Round 4 established (all HTTP-verified):
    - an entity-encoded <<<token>>> in a REST-written design step REGISTERS a real
      `test-parameter` entity, readable at GET tests/{id}/test-parameters;
    - POST step-parameters then WORKS (201) when `parent-id` = the registered test-parameter id
      (earlier probes passed the design-step/test id there -- that was the actual bug);
    - PUT test-parameters/{id} default-value returns 200 (OTA cannot do this).
    - POST test-parameters (flat AND nested) fails 500 "missing required field TP_REF_COUNT"
      even though metadata marks ref-count read-only.

  This round pins the remaining edges:
    A. Can a parameter be created DIRECTLY if ref-count is supplied anyway? (Decides whether the
       generator must author tokens or may create parameters outright.)
    B. Does the design-step description round-trip with the token intact?
    C. Does the step-parameter value read back, and under which owner?
    D. Does deleting the design step cascade-delete the registered parameter?
    E. OTA cross-check (32-bit): does OTA see the REST-created parameter AND the default value
       REST set? (Closes the OTA-side "cannot set default value" UNVERIFIED from both directions.)
#>
[CmdletBinding()]
param([switch]$InsecureTLS, [switch]$SkipOta)

$ErrorActionPreference = 'Stop'
$repoRoot    = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$secretsPath = Join-Path $repoRoot 'Secrets\ALM_API_credentials.json'
$fixtureDir  = Join-Path $repoRoot 'tests\fixtures\write-probe'
New-Item -ItemType Directory -Force $fixtureDir | Out-Null

$c = Get-Content $secretsPath -Raw | ConvertFrom-Json
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

$iwr = @{ TimeoutSec = 60; SkipHttpErrorCheck = $true; MaximumRedirection = 0; AllowInsecureRedirect = $true }
if ($InsecureTLS) { $iwr.SkipCertificateCheck = $true }

$MARK = 'ALTALM-PROBE-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
Say "=== ALM write probe round 4b - test-parameter follow-ups (marker $MARK) ==="

$jsonBody = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
$r = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post -ContentType 'application/json' -Body $jsonBody -SessionVariable session
if ($r.StatusCode -notin 200, 201) { Say "sign-in failed: HTTP $($r.StatusCode)"; return }
$null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post -WebSession $session
$xsrf = ($session.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'XSRF-TOKEN').Value
$r = Invoke-WebRequest @iwr -Uri "$base/v2/rest/is-authenticated" -Headers @{ Accept = 'application/json' } -WebSession $session
$me = (($r.Content | ConvertFrom-Json).AuthenticationInfo.Username)
if ($me) { $script:maskTerms.Add([string]$me) }
Say "signed in : true"

$proj = "$base/rest/domains/$($c.domain)/projects/$($c.project)"
function Invoke-Alm {
    param([string]$Method, [string]$Rel, [string]$BodyJson, [switch]$Quiet)
    $h = @{ Accept = 'application/json' }
    if ($Method -ne 'GET') { $h['X-XSRF-TOKEN'] = $xsrf }
    $a = @{ Uri = "$proj/$Rel"; Method = $Method; Headers = $h; WebSession = $session }
    if ($BodyJson) { $a.ContentType = 'application/json'; $a.Body = $BodyJson }
    $resp = Invoke-WebRequest @iwr @a
    if (-not $Quiet) { Say ('{0,-6} /{1,-56} HTTP {2}' -f $Method, $Rel, $resp.StatusCode) }
    return $resp
}
function Build-Entity([string]$Type, [System.Collections.Specialized.OrderedDictionary]$Fields) {
    $fa = foreach ($k in $Fields.Keys) { [ordered]@{ Name = $k; values = @(@{ value = [string]$Fields[$k] }) } }
    return ([ordered]@{ Fields = @($fa); Type = $Type } | ConvertTo-Json -Depth 6 -Compress)
}
function Get-FieldValue($EntityJson, [string]$Name) {
    $f = ($EntityJson.Fields | Where-Object Name -eq $Name)
    if ($f -and $f.values) { return [string]$f.values[0].value }
    return $null
}
function Show-Err($Resp) {
    $t = $Resp.Content
    if ($t -and $t.Length -gt 300) { $t = $t.Substring(0, 300) }
    Say ("        -> " + (Mask $t))
}
function Save-Fixture([string]$Name, [string]$Content) {
    Set-Content -Path (Join-Path $fixtureDir $Name) -Value (Mask $Content) -Encoding utf8
}

$testId = $null; $folderId = $null; $stepId = $null

try {
    $r = Invoke-Alm GET "test-folders?query={parent-id[0]}&fields=id,name" -Quiet
    $rootId = Get-FieldValue (($r.Content | ConvertFrom-Json).entities[0]) 'id'
    $b = Build-Entity 'test-folder' ([ordered]@{ name = "$MARK-FOLDER"; 'parent-id' = $rootId })
    $folderId = Get-FieldValue ((Invoke-Alm POST "test-folders" $b -Quiet).Content | ConvertFrom-Json) 'id'
    $b = Build-Entity 'test' ([ordered]@{ name = "$MARK-TEST"; 'parent-id' = $folderId; 'subtype-id' = 'MANUAL' })
    $testId = Get-FieldValue ((Invoke-Alm POST "tests" $b -Quiet).Content | ConvertFrom-Json) 'id'
    Say ("scaffold: folder={0} test={1}" -f $folderId, $testId)

    # ------------------------------------------------- A. direct create with ref-count supplied
    Say ""
    Say "=== A. direct POST with ref-count supplied (metadata says read-only) ==="
    $attempts = @(
        @{ label = 'nested, name+ref-count=0'; rel = "tests/$testId/test-parameters";
           body  = ([ordered]@{ name = 'altalm_direct1'; 'ref-count' = '0' }) },
        @{ label = 'nested, name+ref-count=1'; rel = "tests/$testId/test-parameters";
           body  = ([ordered]@{ name = 'altalm_direct2'; 'ref-count' = '1' }) },
        @{ label = 'flat, name+parent+refcnt'; rel = "test-parameters";
           body  = ([ordered]@{ name = 'altalm_direct3'; 'parent-id' = $testId; 'ref-count' = '0' }) },
        @{ label = 'nested, +order+refcount';  rel = "tests/$testId/test-parameters";
           body  = ([ordered]@{ name = 'altalm_direct4'; 'ref-count' = '0'; order = '1' }) }
    )
    foreach ($a in $attempts) {
        $r = Invoke-Alm POST $a.rel (Build-Entity 'test-parameter' $a.body) -Quiet
        Say ("    [{0,-26}] HTTP {1}" -f $a.label, $r.StatusCode)
        if ($r.StatusCode -in 200, 201) {
            Say ("        *** DIRECT CREATE WORKS id={0} ***" -f (Get-FieldValue ($r.Content | ConvertFrom-Json) 'id'))
            Save-Fixture 'r4b-test-parameter-direct.json' $r.Content
        } else { Show-Err $r }
    }

    # ------------------------------------------------- B. token round-trip
    Say ""
    Say "=== B. design-step token round-trip ==="
    $tok = 'altalm_rt'
    $desc = '<html><body>Value is &lt;&lt;&lt;' + $tok + '&gt;&gt;&gt; today</body></html>'
    $b = Build-Entity 'design-step' ([ordered]@{
        name = 'Step 1'; 'parent-id' = $testId; description = $desc; expected = '<html><body>ok</body></html>' })
    $r = Invoke-Alm POST "design-steps" $b -Quiet
    $stepId = Get-FieldValue ($r.Content | ConvertFrom-Json) 'id'
    Say ("    design-step created id={0}" -f $stepId)
    $r = Invoke-Alm GET "design-steps/$stepId" -Quiet
    $stored = Get-FieldValue ($r.Content | ConvertFrom-Json) 'description'
    Say ("    stored description: " + (Mask $stored))
    if ($stored -match [regex]::Escape($tok)) { Say "    *** token name SURVIVES round-trip ***" }
    else { Say "    !!! token name lost on round-trip" }
    Save-Fixture 'r4b-designstep-roundtrip.txt' $stored

    $r = Invoke-Alm GET "tests/$testId/test-parameters" -Quiet
    $params = ($r.Content | ConvertFrom-Json).entities
    Say ("    registered parameters: {0}" -f $params.Count)
    $paramId = $null
    foreach ($p in $params) {
        $paramId = Get-FieldValue $p 'id'
        Say ("      id={0} name='{1}' ref-count={2} order={3}" -f $paramId, (Get-FieldValue $p 'name'), (Get-FieldValue $p 'ref-count'), (Get-FieldValue $p 'order'))
    }

    # ------------------------------------------------- C. step-parameter value round-trip
    Say ""
    Say "=== C. step-parameter value round-trip ==="
    if ($paramId) {
        $b = Build-Entity 'step-parameter' ([ordered]@{
            'used-by-owner-type' = 'design-step'; 'used-by-owner-id' = $stepId
            'parent-id' = $paramId; 'actual-value' = '<html><body>runtime-value</body></html>' })
        $r = Invoke-Alm POST "step-parameters" $b -Quiet
        Say ("    POST step-parameters HTTP {0}" -f $r.StatusCode)
        if ($r.StatusCode -in 200, 201) {
            $spId = Get-FieldValue ($r.Content | ConvertFrom-Json) 'id'
            Save-Fixture 'r4b-step-parameter-created.json' $r.Content
            $r = Invoke-Alm GET "step-parameters/$spId" -Quiet
            if ($r.StatusCode -eq 200) {
                $e = $r.Content | ConvertFrom-Json
                Say ("    read back: actual-value='{0}' owner-type='{1}' parent-id={2}" -f `
                    (Get-FieldValue $e 'actual-value'), (Get-FieldValue $e 'used-by-owner-type'), (Get-FieldValue $e 'parent-id'))
            }
        } else { Show-Err $r }

        Say "    setting default-value on the test-parameter"
        $b = Build-Entity 'test-parameter' ([ordered]@{ 'default-value' = '<html><body>THE-DEFAULT</body></html>' })
        $r = Invoke-Alm PUT "test-parameters/$paramId" $b -Quiet
        Say ("    PUT default-value HTTP {0}" -f $r.StatusCode)
        $r = Invoke-Alm GET "test-parameters/$paramId" -Quiet
        Say ("    read back default-value: " + (Mask (Get-FieldValue ($r.Content | ConvertFrom-Json) 'default-value')))
        Save-Fixture 'r4b-test-parameter-final.json' $r.Content
    }

    # ------------------------------------------------- E. OTA cross-check
    if (-not $SkipOta) {
        Say ""
        Say "=== E. OTA cross-check (32-bit host) ==="
        $ota = Join-Path $PSScriptRoot 'probe-ota-7-paramcheck.ps1'
        if (Test-Path $ota) {
            $ps32 = "$env:WINDIR\SysWOW64\WindowsPowerShell\v1.0\powershell.exe"
            $out = & $ps32 -NoProfile -ExecutionPolicy Bypass -File $ota -TestId $testId 2>&1
            foreach ($line in $out) { Say ("    " + [string]$line) }
        } else { Say "    helper probe-ota-7-paramcheck.ps1 not found - skipped" }
    }

    # ------------------------------------------------- D. cascade check
    Say ""
    Say "=== D. does deleting the design step remove the registered parameter? ==="
    if ($stepId) {
        $r = Invoke-Alm DELETE "design-steps/$stepId" -Quiet
        Say ("    DELETE design-step HTTP {0}" -f $r.StatusCode)
        $stepId = $null
        $r = Invoke-Alm GET "tests/$testId/test-parameters" -Quiet
        $n = ($r.Content | ConvertFrom-Json).TotalResults
        Say ("    parameters remaining after step delete: {0}" -f $n)
        if ([int]$n -gt 0) { Say "    -> parameter SURVIVES step deletion (independent lifetime)" }
        else { Say "    -> parameter was cascade-deleted with the step" }
    }

} finally {
    Say ""
    Say "=== cleanup ==="
    if ($stepId)   { try { $x = Invoke-Alm DELETE "design-steps/$stepId" -Quiet; Say ("    del design-step {0}: HTTP {1}" -f $stepId, $x.StatusCode) } catch { } }
    if ($testId)   { try { $x = Invoke-Alm DELETE "tests/$testId" -Quiet;        Say ("    del test        {0}: HTTP {1}" -f $testId, $x.StatusCode) } catch { } }
    if ($folderId) { try { $x = Invoke-Alm DELETE "test-folders/$folderId" -Quiet; Say ("    del folder      {0}: HTTP {1}" -f $folderId, $x.StatusCode) } catch { } }
    Say ""
    Say "=== orphan sweep (ALTALM-*) ==="
    foreach ($coll in @('tests', 'test-folders')) {
        try {
            $q = [Uri]::EscapeDataString('{name["ALTALM-*"]}')
            $x = Invoke-Alm GET "$coll`?query=$q&fields=id,name" -Quiet
            Say ("    {0,-14} surviving ALTALM-* : {1}" -f $coll, ($x.Content | ConvertFrom-Json).TotalResults)
        } catch { Say ("    sweep {0} failed" -f $coll) }
    }
    try { $null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Delete -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $session } catch { }
    Say "=== ROUND 4b END ==="
}

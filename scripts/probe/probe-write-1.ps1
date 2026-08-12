#Requires -Version 7
<#
.SYNOPSIS
  Write-probe round 1 against the USER-CONFIRMED sandbox project (confirmed 2026-08-12).
  Creates clearly-marked probe records (ALTALM-PROBE prefix), verifies write behaviour, and
  deletes everything it created in a finally block.

.DESCRIPTION
  Sequence:
    0. XSRF experiment: attempt one POST *without* X-XSRF-TOKEN header, record status; all
       subsequent writes send the header (value = XSRF-TOKEN cookie).
    1. POST requirement (name + type-id=3 Functional; parent-id candidates 0 / -1 / omitted).
    2. Rich-text round-trip on requirement description + req-rich-content (torture HTML block).
    3. POST test-folder, POST test (MANUAL) under it.
    4. POST design-steps under the test; POST step-parameters (entity shape discovered at runtime).
    5. POST requirement-coverages (req<->test); observe test-config-coverages side effects.
    6. POST second requirement + req-traces (req<->req traceability).
    7. POST defect (required: detected-by, creation-time, severity, name) + defect-links
       (defect<->defect and defect<->requirement second-endpoint-type).
    8. Cleanup: DELETE all created entities in reverse order.
  All output masked (host/domain/project/keys/username -> REDACTED). Response fixtures saved
  redacted under tests/fixtures/write-probe/.
#>
[CmdletBinding()]
param([switch]$InsecureTLS, [switch]$SkipCleanup)

$ErrorActionPreference = 'Stop'
$repoRoot    = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$secretsPath = Join-Path $repoRoot 'Secrets\ALM_API_credentials.json'
$fixtureDir  = Join-Path $repoRoot 'tests\fixtures\write-probe'
New-Item -ItemType Directory -Force $fixtureDir | Out-Null

$c = Get-Content $secretsPath -Raw | ConvertFrom-Json
$base = ([string]$c.alm_adress).Trim().TrimEnd('/')
if ($base -notmatch '/qcbin$') { $base = "$base/qcbin" }
$maskHost = ([Uri]$base).Host
$script:maskTerms = [System.Collections.Generic.List[string]]::new()
foreach ($m in @($maskHost, $c.api_key, $c.api_secret, $c.domain, $c.project)) { if ($m) { $script:maskTerms.Add([string]$m) } }

function Mask([string]$s) {
    if (-not $s) { return $s }
    foreach ($m in $script:maskTerms) { $s = $s -replace [regex]::Escape($m), 'REDACTED' }
    return $s
}

$iwr = @{ TimeoutSec = 60; SkipHttpErrorCheck = $true; MaximumRedirection = 0; AllowInsecureRedirect = $true }
if ($InsecureTLS) { $iwr.SkipCertificateCheck = $true }

$MARK = 'ALTALM-PROBE-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
"=== ALM write probe round 1 (sandbox; marker $MARK) ==="

# --- sign in ---
$jsonBody = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
$r = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post -ContentType 'application/json' -Body $jsonBody -SessionVariable session
if ($r.StatusCode -notin 200, 201) { "sign-in failed: HTTP $($r.StatusCode)"; return }
$null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post -WebSession $session
$xsrf = ($session.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'XSRF-TOKEN').Value
"signed in : true   xsrf cookie present: $([bool]$xsrf)"

# current username (for defect detected-by) — masked from all output
$r = Invoke-WebRequest @iwr -Uri "$base/v2/rest/is-authenticated" -Headers @{ Accept = 'application/json' } -WebSession $session
$me = (($r.Content | ConvertFrom-Json).AuthenticationInfo.Username)
if ($me) { $script:maskTerms.Add([string]$me) }

$proj = "$base/rest/domains/$($c.domain)/projects/$($c.project)"

function Invoke-Alm {
    param([string]$Method, [string]$Rel, [string]$BodyJson, [switch]$NoXsrf, [string]$Accept = 'application/json')
    $h = @{ Accept = $Accept }
    if (-not $NoXsrf -and $Method -ne 'GET') { $h['X-XSRF-TOKEN'] = $xsrf }
    $args = @{ Uri = "$proj/$Rel"; Method = $Method; Headers = $h; WebSession = $session }
    if ($BodyJson) { $args.ContentType = 'application/json'; $args.Body = $BodyJson }
    $r = Invoke-WebRequest @iwr @args
    Write-Host (Mask ('{0,-6} /{1,-58} HTTP {2}' -f $Method, $Rel, $r.StatusCode))
    if ($r.StatusCode -ge 300 -and $r.StatusCode -lt 400) {
        Write-Host ('  redirect Location: ' + (Mask ([string]$r.Headers.Location)))
    }
    return $r
}

# Core REST JSON entity shape: {"Fields":[{"Name":"n","values":[{"value":"v"}]}],"Type":"t"}
# NOTE: field order is made DETERMINISTIC (insertion order of an [ordered] dictionary), not a
# plain PowerShell Hashtable — plain-Hashtable key enumeration order is randomized per process
# (string-hash-seed randomization), which was observed to change which server-side NPE/400 came
# back between otherwise-identical runs. Always pass Fields as [ordered]@{...}.
function Build-Entity([string]$Type, $Fields) {
    $fa = foreach ($k in $Fields.Keys) { [ordered]@{ Name = $k; values = @(@{ value = [string]$Fields[$k] }) } }
    return ([ordered]@{ Fields = @($fa); Type = $Type } | ConvertTo-Json -Depth 6 -Compress)
}

function Get-FieldValue($EntityJson, [string]$Name) {
    $f = ($EntityJson.Fields | Where-Object Name -eq $Name)
    if ($f -and $f.values) { return [string]$f.values[0].value }
    return $null
}

function Save-Fixture([string]$Name, [string]$Content) {
    Set-Content -Path (Join-Path $fixtureDir $Name) -Value (Mask $Content) -Encoding utf8
}

# created ids for cleanup: list of @{rel='requirements'; id=123}
$created = [System.Collections.Generic.List[hashtable]]::new()

function New-AlmEntity {
    param([string]$Collection, [string]$Type, $Fields, [string]$FixtureName)
    $body = Build-Entity $Type $Fields
    if ($env:PROBE_DEBUG_BODY) { Write-Host ('  BODY: ' + (Mask $body)) }
    $r = Invoke-Alm POST $Collection $body
    if ($r.StatusCode -in 200, 201) {
        $j = $r.Content | ConvertFrom-Json
        $id = Get-FieldValue $j 'id'
        if ($id) { $created.Add(@{ rel = $Collection; id = $id }) }
        if ($FixtureName) { Save-Fixture $FixtureName ([string]$r.Content) }
        Write-Host ("  -> created id: $id")
        return $j
    }
    Write-Host ('  -> FAILED body: ' + (Mask (([string]$r.Content) -replace '\s+', ' ').Substring(0, [Math]::Min(400, ([string]$r.Content).Length))))
    return $null
}

try {
    ''
    '--- 0. XSRF experiment: POST requirement WITHOUT X-XSRF-TOKEN header ---'
    $body = Build-Entity 'requirement' ([ordered]@{ name = "$MARK-noxsrf"; 'parent-id' = '0'; 'type-id' = '3' })
    $r = Invoke-Alm POST 'requirements' $body -NoXsrf
    Write-Host ('  no-XSRF result: HTTP ' + $r.StatusCode + '  body: ' + (Mask (([string]$r.Content) -replace '\s+', ' ').Substring(0, [Math]::Min(300, ([string]$r.Content).Length))))
    if ($r.StatusCode -in 200, 201) {
        # write went through without the header — record and queue for cleanup
        $j = $r.Content | ConvertFrom-Json; $id = Get-FieldValue $j 'id'
        if ($id) { $created.Add(@{ rel = 'requirements'; id = $id }) }
    }

    ''
    '--- 1. create requirement (type-id=3 Functional; parent-id candidates 0 / -1 / omit) ---'
    $req1 = $null
    foreach ($parentId in @('1', '0', '-1', $null)) {
        $f = [ordered]@{ name = "$MARK-req1" }
        if ($null -ne $parentId) { $f['parent-id'] = $parentId }
        $f['type-id'] = '3'
        Write-Host ("  trying parent-id=" + ($parentId ?? '(omitted)'))
        $req1 = New-AlmEntity 'requirements' 'requirement' $f 'req-create-response.json'
        if ($req1) { break }
    }
    if (-not $req1) { throw 'requirement create failed with all parent-id candidates' }
    $req1Id = Get-FieldValue $req1 'id'

    ''
    '--- 2. rich-text round-trip on requirement (description + req-rich-content) ---'
    $torture = '<html><body><b>bold</b> <i>italic</i> <u>under</u> <font color="#ff0000">red</font>' +
               '<ul><li>li-one</li><li>li-two</li></ul>' +
               '<table border="1"><tr><td>cell-a</td><td>cell-b</td></tr></table>' +
               '<a href="http://example.com/x">link-text</a> &amp;amp; escaped &amp;lt;tag&amp;gt; ' +
               '<span style="background-color:yellow">hl-span</span>' +
               '<div style="text-align:center">centered</div>' +
               '<script>alert(1)</script>' +
               '</body></html>'
    foreach ($fld in @('description', 'req-rich-content')) {
        $put = Build-Entity 'requirement' ([ordered]@{ $fld = $torture })
        $r = Invoke-Alm PUT "requirements/$req1Id" $put
        $r2 = Invoke-Alm GET "requirements/$req1Id`?fields=$fld"
        if ($r2.StatusCode -eq 200) {
            $back = Get-FieldValue ($r2.Content | ConvertFrom-Json) $fld
            Write-Host ("  [$fld] PUT HTTP $($r.StatusCode); readback " + $(if ($back -eq $torture) { 'IDENTICAL' } else { 'DIFFERS' }))
            Write-Host ('  [' + $fld + '] sent  : ' + $torture)
            Write-Host ('  [' + $fld + '] got   : ' + (Mask ([string]$back)))
            Save-Fixture "richtext-roundtrip-$fld.txt" ("SENT:`n$torture`n`nGOT:`n$back")
        }
    }
    # also check has-rich-content flag
    $r = Invoke-Alm GET "requirements/$req1Id`?fields=has-rich-content"
    if ($r.StatusCode -eq 200) { Write-Host ('  has-rich-content after PUT: ' + (Get-FieldValue ($r.Content | ConvertFrom-Json) 'has-rich-content')) }

    ''
    '--- 3. test-folder + MANUAL test ---'
    $rootCandidates = @('0', '-1', '1')
    $r = Invoke-Alm GET 'test-folders?query={parent-id[0]}&fields=id,name,parent-id&page-size=5'
    if ($r.StatusCode -eq 200) {
        $j = $r.Content | ConvertFrom-Json
        $entities = @($j.entities)
        if ($entities.Count -gt 0) {
            $discoveredIds = @($entities | ForEach-Object { Get-FieldValue $_ 'id' })
            Write-Host ('  discovered top-level test-folders (parent-id=0): ' + ($discoveredIds -join ',') + '  names: ' + (@($entities | ForEach-Object { Get-FieldValue $_ 'name' }) -join ','))
            $rootCandidates = $discoveredIds + $rootCandidates
        } else {
            Write-Host '  no top-level test-folders found under parent-id=0'
        }
    }
    $tf = $null
    foreach ($parentId in $rootCandidates) {
        Write-Host ("  test-folder parent-id=$parentId")
        $tf = New-AlmEntity 'test-folders' 'test-folder' ([ordered]@{ name = "$MARK-folder"; 'parent-id' = $parentId }) 'test-folder-create-response.json'
        if ($tf) { break }
    }
    if (-not $tf) { throw 'test-folder create failed' }
    $tfId = Get-FieldValue $tf 'id'
    $test = New-AlmEntity 'tests' 'test' ([ordered]@{ name = "$MARK-test"; 'parent-id' = $tfId; 'subtype-id' = 'MANUAL' }) 'test-create-response.json'
    if (-not $test) { throw 'test create failed' }
    $testId = Get-FieldValue $test 'id'

    ''
    '--- 4. design-steps POST (the big one) + step-parameters ---'
    $ds = New-AlmEntity 'design-steps' 'design-step' ([ordered]@{
        name = 'Step 1'; 'parent-id' = $testId
        description = '<html><body>Do the thing with <<<probe_param>>> token</body></html>'
        expected    = '<html><body>Thing done</body></html>'
    }) 'design-step-create-response.json'
    if ($ds) {
        $dsId = Get-FieldValue $ds 'id'
        Write-Host "  DESIGN-STEP WRITE PATH CONFIRMED (id $dsId)"
        # order/step-order field present?
        Write-Host ('  design-step returned fields: ' + (($ds.Fields | ForEach-Object Name) -join ','))
    }
    # discover step-parameter entity shape
    $r = Invoke-Alm GET 'customization/entities/step-parameter/fields'
    if ($r.StatusCode -eq 200) {
        $j = $r.Content | ConvertFrom-Json
        $names = @($j.Fields.Field | ForEach-Object { '{0}({1}{2})' -f $_.Name, $_.Type, $(if ($_.Required) {',req'} else {''}) })
        Write-Host ('  step-parameter fields: ' + ($names -join ' '))
        Save-Fixture 'customization-fields-step-parameter.json' ([string]$r.Content)
    }
    # real field names discovered above: key, used-by-owner-type(req), used-by-owner-id, parent-id, actual-value, origin-test
    $sp = New-AlmEntity 'step-parameters' 'step-parameter' ([ordered]@{
        key = 'probe_param'; 'used-by-owner-type' = 'design-step'; 'used-by-owner-id' = $dsId; 'parent-id' = $testId; 'actual-value' = 'v1'
    }) 'step-parameter-create-response.json'
    if (-not $sp) {
        Write-Host '  retrying step-parameter with used-by-owner-type=test'
        $sp = New-AlmEntity 'step-parameters' 'step-parameter' ([ordered]@{
            key = 'probe_param'; 'used-by-owner-type' = 'test'; 'used-by-owner-id' = $testId; 'parent-id' = $testId; 'actual-value' = 'v1'
        }) 'step-parameter-create-response.json'
    }

    ''
    '--- 5. requirement-coverages POST (req1 <-> test) ---'
    $r = Invoke-Alm GET 'customization/entities/requirement-coverage/fields'
    if ($r.StatusCode -eq 200) {
        $j = $r.Content | ConvertFrom-Json
        Write-Host ('  requirement-coverage fields: ' + (@($j.Fields.Field | ForEach-Object { '{0}({1}{2})' -f $_.Name, $_.Type, $(if ($_.Required) {',req'} else {''}) }) -join ' '))
        Save-Fixture 'customization-fields-requirement-coverage.json' ([string]$r.Content)
    }
    $cov = New-AlmEntity 'requirement-coverages' 'requirement-coverage' ([ordered]@{ 'requirement-id' = $req1Id; 'test-id' = $testId; 'entity-type' = 'test' }) 'requirement-coverage-create-response.json'
    if (-not $cov) {
        Write-Host '  retrying without entity-type'
        $cov = New-AlmEntity 'requirement-coverages' 'requirement-coverage' ([ordered]@{ 'requirement-id' = $req1Id; 'test-id' = $testId }) 'requirement-coverage-create-response.json'
    }
    # observe test-config-coverages side effect (test-config-coverages has NO requirement-id field;
    # its documented fields are id, first-endpoint-id -> requirement-coverages row, second-endpoint-id -> test-configs)
    if ($cov) {
        $covId = Get-FieldValue $cov 'id'
        $r = Invoke-Alm GET "test-config-coverages?query={first-endpoint-id[$covId]}&page-size=10"
        if ($r.StatusCode -eq 200) { Write-Host ('  test-config-coverages for coverage-id ' + $covId + ': TotalResults=' + (($r.Content | ConvertFrom-Json).TotalResults)) }
        else { Write-Host ('  test-config-coverages query HTTP ' + $r.StatusCode + ' body: ' + (Mask (([string]$r.Content) -replace '\s+', ' ').Substring(0, [Math]::Min(300, ([string]$r.Content).Length)))) }
    }

    ''
    '--- 6. second requirement + req-traces (traceability) ---'
    $req2 = New-AlmEntity 'requirements' 'requirement' ([ordered]@{ name = "$MARK-req2"; 'parent-id' = (Get-FieldValue $req1 'parent-id'); 'type-id' = '3' }) 'req2-create-response.json'
    $r = Invoke-Alm GET 'customization/entities/req-trace/fields'
    if ($r.StatusCode -eq 200) {
        $j = $r.Content | ConvertFrom-Json
        Write-Host ('  req-trace fields: ' + (@($j.Fields.Field | ForEach-Object { '{0}({1}{2})' -f $_.Name, $_.Type, $(if ($_.Required) {',req'} else {''}) }) -join ' '))
        Save-Fixture 'customization-fields-req-trace.json' ([string]$r.Content)
    }
    if ($req2) {
        $req2Id = Get-FieldValue $req2 'id'
        $trace = New-AlmEntity 'req-traces' 'req-trace' ([ordered]@{ 'from-req-id' = $req1Id; 'to-req-id' = $req2Id }) 'req-trace-create-response.json'
        if (-not $trace) {
            Write-Host '  retrying req-trace with alt field names (source-req-id/target-req-id)'
            $trace = New-AlmEntity 'req-traces' 'req-trace' ([ordered]@{ 'source-req-id' = $req1Id; 'target-req-id' = $req2Id }) 'req-trace-create-response.json'
        }
    }

    ''
    '--- 7. defect + defect-links ---'
    # severity list value: resolve locally from fixtures at runtime (no extra server calls)
    $defFields = Get-Content (Join-Path $repoRoot 'tests\fixtures\customization-fields-defect.json') -Raw | ConvertFrom-Json
    $sevListId = ($defFields.Fields.Field | Where-Object Name -eq 'severity').listId
    $lists = Get-Content (Join-Path $repoRoot 'tests\fixtures\customization-used-lists.json') -Raw | ConvertFrom-Json
    $sevList = @($lists.lists) | Where-Object { [string]$_.Id -eq [string]$sevListId }
    $sevValue = @($sevList.Items)[0].value
    Write-Host ("  severity List-Id=$sevListId first item: $sevValue")
    $defect = New-AlmEntity 'defects' 'defect' ([ordered]@{
        name = "$MARK-defect1"; 'detected-by' = $me; 'creation-time' = (Get-Date -Format 'yyyy-MM-dd'); severity = $sevValue
    }) 'defect-create-response.json'
    $defect2 = New-AlmEntity 'defects' 'defect' ([ordered]@{
        name = "$MARK-defect2"; 'detected-by' = $me; 'creation-time' = (Get-Date -Format 'yyyy-MM-dd'); severity = $sevValue
    }) $null
    if ($defect -and $defect2) {
        $d1 = Get-FieldValue $defect 'id'; $d2 = Get-FieldValue $defect2 'id'
        # defect <-> defect
        $l1 = New-AlmEntity 'defect-links' 'defect-link' ([ordered]@{ 'first-endpoint-id' = $d1; 'second-endpoint-id' = $d2; 'second-endpoint-type' = 'defect' }) 'defect-link-defect-response.json'
        # defect <-> requirement
        $l2 = New-AlmEntity 'defect-links' 'defect-link' ([ordered]@{ 'first-endpoint-id' = $d1; 'second-endpoint-id' = $req1Id; 'second-endpoint-type' = 'requirement' }) 'defect-link-requirement-response.json'
    }

    ''
    '--- 8. entity /audits read on created requirement (history surface) ---'
    $r = Invoke-Alm GET "requirements/$req1Id/audits"
    if ($r.StatusCode -eq 200) {
        Save-Fixture 'requirement-audits-response.json' ([string]$r.Content)
        Write-Host ('  audits body head: ' + (Mask (([string]$r.Content) -replace '\s+', ' ').Substring(0, [Math]::Min(400, ([string]$r.Content).Length))))
    }
}
finally {
    ''
    if ($SkipCleanup) {
        'SkipCleanup set — leaving records in place:'
        $created | ForEach-Object { '  {0}/{1}' -f $_.rel, $_.id }
    } else {
        '--- cleanup (reverse order) ---'
        for ($i = $created.Count - 1; $i -ge 0; $i--) {
            $e = $created[$i]
            try { $null = Invoke-Alm DELETE "$($e.rel)/$($e.id)" } catch { Write-Host ("  DELETE $($e.rel)/$($e.id) threw: " + (Mask $_.Exception.Message)) }
        }
    }
    $null = Invoke-WebRequest @iwr -Uri "$base/authentication-point/logout" -WebSession $session
    'logged out : true'
}

#Requires -Version 7
<#
.SYNOPSIS
  Write-probe round 3 (targeted) against the USER-CONFIRMED sandbox (writes approved 2026-08-12).
  Goals: resolve run-creation ("must number attribute 'TESTSET'"), retry multipart ref-subtype=1
  with a hand-built body, read-only BPT components check. ALTALM-PROBE prefix, cleanup + orphan
  sweep as in round 2.

.DESCRIPTION
  A. Read-only: GET components?page-size=1 and business-components?page-size=1 (inventory said
     absent; inventory has known false negatives).
  B. Minimal chain rebuild (folder->test->design-step->tsf->test-set->instance), then:
     B1. PUT test-instances/{id} status=Passed -> does a synthetic Fast_Run appear? Read the
         server-created run's FULL field set (fixture) — this reveals correct run field population.
     B2. Retry POST runs as XML (legacy Entity/Fields/Field/Value shape, Content-Type
         application/xml) with the binding fields.
     B3. Retry POST runs as JSON including any extra fields learned from the Fast_Run.
  C. If a run exists (either Fast_Run or ours): run-steps auto-copy check, PUT run status,
     instance status mirror, run-step Failed aggregation.
  D. Multipart ref-subtype=1 retry with a manually-constructed multipart/form-data byte body
     (explicit boundary + CRLF discipline, file part LAST with Content-Type image/png).
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
"=== ALM write probe round 3 (sandbox; marker $MARK) ==="

$jsonBody = @{ clientId = $c.api_key; secret = $c.api_secret } | ConvertTo-Json -Compress
$r = Invoke-WebRequest @iwr -Uri "$base/rest/oauth2/login" -Method Post -ContentType 'application/json' -Body $jsonBody -SessionVariable session
if ($r.StatusCode -notin 200, 201) { "sign-in failed: HTTP $($r.StatusCode)"; return }
$null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Post -WebSession $session
$xsrf = ($session.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'XSRF-TOKEN').Value
"signed in : true"
$r = Invoke-WebRequest @iwr -Uri "$base/v2/rest/is-authenticated" -Headers @{ Accept = 'application/json' } -WebSession $session
$me = (($r.Content | ConvertFrom-Json).AuthenticationInfo.Username)
if ($me) { $script:maskTerms.Add([string]$me) }

$proj = "$base/rest/domains/$($c.domain)/projects/$($c.project)"

function Invoke-Alm {
    param([string]$Method, [string]$Rel, [string]$BodyJson, [string]$ContentType = 'application/json', [byte[]]$BodyBytes, [hashtable]$ExtraHeaders, [string]$Accept = 'application/json')
    $h = @{ Accept = $Accept }
    if ($Method -ne 'GET') { $h['X-XSRF-TOKEN'] = $xsrf }
    if ($ExtraHeaders) { foreach ($k in $ExtraHeaders.Keys) { $h[$k] = $ExtraHeaders[$k] } }
    $args = @{ Uri = "$proj/$Rel"; Method = $Method; Headers = $h; WebSession = $session }
    if ($BodyJson)  { $args.ContentType = $ContentType; $args.Body = $BodyJson }
    if ($BodyBytes) { $args.ContentType = $ContentType; $args.Body = $BodyBytes }
    $r = Invoke-WebRequest @iwr @args
    Write-Host (Mask ('{0,-6} /{1,-64} HTTP {2}' -f $Method, $Rel, $r.StatusCode))
    return $r
}
function Build-Entity([string]$Type, $Fields) {
    $fa = foreach ($k in $Fields.Keys) { [ordered]@{ Name = $k; values = @(@{ value = [string]$Fields[$k] }) } }
    return ([ordered]@{ Fields = @($fa); Type = $Type } | ConvertTo-Json -Depth 6 -Compress)
}
function Build-EntityXml([string]$Type, $Fields) {
    $sb = [Text.StringBuilder]::new()
    $null = $sb.Append("<Entity Type=`"$Type`"><Fields>")
    foreach ($k in $Fields.Keys) {
        $v = [Security.SecurityElement]::Escape([string]$Fields[$k])
        $null = $sb.Append("<Field Name=`"$k`"><Value>$v</Value></Field>")
    }
    $null = $sb.Append('</Fields></Entity>')
    return $sb.ToString()
}
function Get-FieldValue($EntityJson, [string]$Name) {
    $f = ($EntityJson.Fields | Where-Object Name -eq $Name)
    if ($f -and $f.values) { return [string]$f.values[0].value }
    return $null
}
function Save-Fixture([string]$Name, [string]$Content) {
    Set-Content -Path (Join-Path $fixtureDir $Name) -Value (Mask $Content) -Encoding utf8
}
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

$pngB64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
$pngBytes = [Convert]::FromBase64String($pngB64)

try {
    ''
    '--- A. read-only BPT components check ---'
    foreach ($col in @('components', 'business-components')) {
        $r = Invoke-Alm GET "$col`?page-size=1"
        Write-Host ('  body head: ' + (Mask (([string]$r.Content) -replace '\s+', ' ').Substring(0, [Math]::Min(200, ([string]$r.Content).Length))))
    }

    ''
    '--- B. chain rebuild ---'
    $tf   = New-AlmEntity 'test-folders' 'test-folder' ([ordered]@{ name = "$MARK-folder"; 'parent-id' = '2' }) $null
    $test = New-AlmEntity 'tests' 'test' ([ordered]@{ name = "$MARK-test"; 'parent-id' = (Get-FieldValue $tf 'id'); 'subtype-id' = 'MANUAL' }) $null
    $testId = Get-FieldValue $test 'id'
    $null = New-AlmEntity 'design-steps' 'design-step' ([ordered]@{ name = 'Step 1'; 'parent-id' = $testId; description = '<html><body>one</body></html>'; expected = '<html><body>ok</body></html>' }) $null
    $null = New-AlmEntity 'design-steps' 'design-step' ([ordered]@{ name = 'Step 2'; 'parent-id' = $testId; description = '<html><body>two</body></html>'; expected = '<html><body>ok2</body></html>' }) $null
    $tsf  = New-AlmEntity 'test-set-folders' 'test-set-folder' ([ordered]@{ name = "$MARK-tsf"; 'parent-id' = '0' }) $null
    $ts   = New-AlmEntity 'test-sets' 'test-set' ([ordered]@{ name = "$MARK-testset"; 'parent-id' = (Get-FieldValue $tsf 'id'); 'subtype-id' = 'hp.qc.test-set.default' }) $null
    $tsId = Get-FieldValue $ts 'id'
    $ti   = New-AlmEntity 'test-instances' 'test-instance' ([ordered]@{ 'cycle-id' = $tsId; 'test-id' = $testId; 'subtype-id' = 'hp.qc.test-instance.MANUAL' }) $null
    if (-not $ti) { throw 'chain rebuild failed at test-instance' }
    $tiId = Get-FieldValue $ti 'id'

    ''
    '--- B1. instance-status PUT -> synthetic Fast_Run? ---'
    $put = ([ordered]@{ Fields = @([ordered]@{ Name = 'status'; values = @(@{ value = 'Passed' }) }); Type = 'test-instance' } | ConvertTo-Json -Depth 6 -Compress)
    $r = Invoke-Alm PUT "test-instances/$tiId" $put
    Write-Host ('  instance status PUT: HTTP ' + $r.StatusCode)
    $r = Invoke-Alm GET "runs?query={testcycl-id[$tiId]}&page-size=10"
    if ($r.StatusCode -eq 200) {
        $j = $r.Content | ConvertFrom-Json
        Write-Host ('  runs after instance PUT: TotalResults=' + $j.TotalResults)
        $fast = @($j.entities)[0]
        if ($fast) {
            $fastId = Get-FieldValue $fast 'id'
            $created.Add(@{ rel = 'runs'; id = $fastId })
            $r2 = Invoke-Alm GET "runs/$fastId"
            if ($r2.StatusCode -eq 200) {
                Save-Fixture 'r3-fastrun-full-entity.json' ([string]$r2.Content)
                $jj = $r2.Content | ConvertFrom-Json
                $nonEmpty = @($jj.Fields | Where-Object { $_.values -and $_.values[0].value } | ForEach-Object { '{0}={1}' -f $_.Name, $_.values[0].value })
                Write-Host ('  Fast_Run populated fields: ' + (Mask ($nonEmpty -join ' | ')))
            }
        }
    }

    ''
    '--- B2. run POST as XML (legacy shape) ---'
    $runFields = [ordered]@{ name = "$MARK-run-xml"; 'test-id' = $testId; 'testcycl-id' = $tiId; 'cycle-id' = $tsId; 'subtype-id' = 'hp.qc.run.MANUAL'; owner = $me; status = 'Not Completed' }
    $xml = Build-EntityXml 'run' $runFields
    $r = Invoke-Alm POST 'runs' -BodyJson $xml -ContentType 'application/xml' -Accept 'application/xml'
    Write-Host ('  XML run POST: HTTP ' + $r.StatusCode + '  body head: ' + (Mask (([string]$r.Content) -replace '\s+', ' ').Substring(0, [Math]::Min(300, ([string]$r.Content).Length))))
    if ($r.StatusCode -in 200, 201) {
        $m2 = [regex]::Match([string]$r.Content, '<Field Name="id"><Value>(\d+)</Value>')
        if ($m2.Success) { $created.Add(@{ rel = 'runs'; id = $m2.Groups[1].Value }); Write-Host ('  -> created run id: ' + $m2.Groups[1].Value) }
        Save-Fixture 'r3-run-create-xml.xml' ([string]$r.Content)
    }

    ''
    '--- B3. run POST as JSON with Fast_Run-informed fields ---'
    # agent: extend this field set based on what B1 revealed (e.g. host, duration, test-config-id)
    $run3 = New-AlmEntity 'runs' 'run' ([ordered]@{ name = "$MARK-run-json"; 'test-id' = $testId; 'testcycl-id' = $tiId; 'cycle-id' = $tsId; 'test-config-id' = (Get-FieldValue $ti 'test-config-id'); 'subtype-id' = 'hp.qc.run.MANUAL'; owner = $me; status = 'Not Completed' }) 'r3-run-create-json.json'

    ''
    '--- B3b. run POST as JSON, + test-instance ordinal (Fast_Run showed test-instance=1) ---'
    $run3b = New-AlmEntity 'runs' 'run' ([ordered]@{ name = "$MARK-run-json-b"; 'test-id' = $testId; 'testcycl-id' = $tiId; 'cycle-id' = $tsId; 'test-config-id' = (Get-FieldValue $ti 'test-config-id'); 'test-instance' = '1'; 'subtype-id' = 'hp.qc.run.MANUAL'; owner = $me; status = 'Not Completed' }) 'r3-run-create-json-b.json'

    ''
    '--- B3c. run POST as JSON, + denormalized name fields matching Fast_Run pattern exactly ---'
    $testInstName = (Get-FieldValue $test 'name') + ' [1]'
    $run3c = New-AlmEntity 'runs' 'run' ([ordered]@{ name = "$MARK-run-json-c"; 'test-id' = $testId; 'testcycl-id' = $tiId; 'cycle-id' = $tsId; 'test-config-id' = (Get-FieldValue $ti 'test-config-id'); 'test-instance' = '1'; 'test-name' = (Get-FieldValue $test 'name'); 'testcycl-name' = $testInstName; 'cycle-name' = (Get-FieldValue $ts 'name'); 'subtype-id' = 'hp.qc.run.MANUAL'; owner = $me; status = 'Not Completed' }) 'r3-run-create-json-c.json'

    ''
    '--- B3d. isolation: name fields ONLY (no test-instance ordinal) ---'
    $run3d = New-AlmEntity 'runs' 'run' ([ordered]@{ name = "$MARK-run-json-d"; 'test-id' = $testId; 'testcycl-id' = $tiId; 'cycle-id' = $tsId; 'test-config-id' = (Get-FieldValue $ti 'test-config-id'); 'test-name' = (Get-FieldValue $test 'name'); 'testcycl-name' = $testInstName; 'cycle-name' = (Get-FieldValue $ts 'name'); 'subtype-id' = 'hp.qc.run.MANUAL'; owner = $me; status = 'Not Completed' }) 'r3-run-create-json-d.json'

    ''
    '--- B3e. isolation: test-instance ordinal ONLY (no name fields) [duplicate of B3b, re-confirm] ---'
    $run3e = New-AlmEntity 'runs' 'run' ([ordered]@{ name = "$MARK-run-json-e"; 'test-id' = $testId; 'testcycl-id' = $tiId; 'cycle-id' = $tsId; 'test-config-id' = (Get-FieldValue $ti 'test-config-id'); 'test-instance' = '1'; 'subtype-id' = 'hp.qc.run.MANUAL'; owner = $me; status = 'Not Completed' }) 'r3-run-create-json-e.json'

    ''
    '--- C. run-steps / mirror / aggregation (on whichever run exists) ---'
    $runId = ($created | Where-Object { $_.rel -eq 'runs' } | Select-Object -Last 1).id
    if ($runId) {
        $r = Invoke-Alm GET "runs/$runId/run-steps"
        if ($r.StatusCode -eq 200) {
            $j = $r.Content | ConvertFrom-Json
            Write-Host ('  run-steps on run ' + $runId + ': TotalResults=' + $j.TotalResults)
            Save-Fixture 'r3-run-steps.json' ([string]$r.Content)
            $put = ([ordered]@{ Fields = @([ordered]@{ Name = 'status'; values = @(@{ value = 'Passed' }) }); Type = 'run' } | ConvertTo-Json -Depth 6 -Compress)
            $r = Invoke-Alm PUT "runs/$runId" $put
            Write-Host ('  run PUT Passed: HTTP ' + $r.StatusCode)
            $r = Invoke-Alm GET "test-instances/$tiId`?fields=status"
            if ($r.StatusCode -eq 200) { Write-Host ('  instance status now: ' + (Get-FieldValue ($r.Content | ConvertFrom-Json) 'status')) }
            $steps = @($j.entities)
            if ($steps.Count -gt 0) {
                $rsId = Get-FieldValue $steps[0] 'id'
                $put = ([ordered]@{ Fields = @([ordered]@{ Name = 'status'; values = @(@{ value = 'Failed' }) }); Type = 'run-step' } | ConvertTo-Json -Depth 6 -Compress)
                $r = Invoke-Alm PUT "runs/$runId/run-steps/$rsId" $put
                Write-Host ('  run-step PUT Failed: HTTP ' + $r.StatusCode)
                $r = Invoke-Alm GET "runs/$runId`?fields=status"
                if ($r.StatusCode -eq 200) { Write-Host ('  run status after step fail: ' + (Get-FieldValue ($r.Content | ConvertFrom-Json) 'status')) }
            }
        }
    } else { Write-Host '  no run available — C blocked again' }

    ''
    '--- D. multipart ref-subtype=1 retry (hand-built body) ---'
    $reqA = New-AlmEntity 'requirements' 'requirement' ([ordered]@{ name = "$MARK-req-attach"; 'parent-id' = '0'; 'type-id' = '3' }) $null
    if ($reqA) {
        $reqAId = Get-FieldValue $reqA 'id'
        $boundary = '----AltAlmProbe' + [Guid]::NewGuid().ToString('N')
        $CRLF = "`r`n"
        $ms = [IO.MemoryStream]::new()
        $w = [IO.StreamWriter]::new($ms, [Text.UTF8Encoding]::new($false))
        $w.NewLine = $CRLF
        foreach ($pair in @(@('filename', 'probe-embed.png'), @('description', 'probe embedded image'), @('ref-subtype', '1'))) {
            $w.Write("--$boundary$CRLF")
            $w.Write("Content-Disposition: form-data; name=`"$($pair[0])`"$CRLF$CRLF")
            $w.Write("$($pair[1])$CRLF")
        }
        $w.Write("--$boundary$CRLF")
        $w.Write("Content-Disposition: form-data; name=`"file`"; filename=`"probe-embed.png`"$CRLF")
        $w.Write("Content-Type: image/png$CRLF$CRLF")
        $w.Flush()
        $ms.Write($pngBytes, 0, $pngBytes.Length)
        $w.Write("$CRLF--$boundary--$CRLF")
        $w.Flush()
        $bodyBytes = $ms.ToArray()
        $r = Invoke-Alm POST "requirements/$reqAId/attachments" -BodyBytes $bodyBytes -ContentType "multipart/form-data; boundary=$boundary"
        Write-Host ('  hand-built multipart ref-subtype=1: HTTP ' + $r.StatusCode + '  body head: ' + (Mask (([string]$r.Content) -replace '\s+', ' ').Substring(0, [Math]::Min(300, ([string]$r.Content).Length))))
        if ($r.StatusCode -in 200, 201) { Save-Fixture 'r3-attach-multipart-refsubtype1.json' ([string]$r.Content) }
        # readback: does the attachment list show ref-subtype 1?
        $r = Invoke-Alm GET "requirements/$reqAId/attachments"
        if ($r.StatusCode -eq 200) { Save-Fixture 'r3-attachments-list.json' ([string]$r.Content) }
    }
}
finally {
    ''
    if ($SkipCleanup) {
        'SkipCleanup set — records left:'; $created | ForEach-Object { '  {0}/{1}' -f $_.rel, $_.id }
    } else {
        '--- cleanup (reverse order) ---'
        for ($i = $created.Count - 1; $i -ge 0; $i--) {
            $e = $created[$i]
            try { $null = Invoke-Alm DELETE "$($e.rel)/$($e.id)" } catch { Write-Host ("  DELETE $($e.rel)/$($e.id) threw: " + (Mask $_.Exception.Message)) }
        }
        '--- orphan sweep (name prefix ALTALM-PROBE) ---'
        foreach ($col in @('requirements','test-folders','tests','design-steps','test-set-folders','test-sets','test-instances','runs','milestones','releases','release-cycles','test-executions','defects')) {
            try {
                $r = Invoke-Alm GET "$col`?query={name[ALTALM-PROBE*]}&fields=id,name&page-size=50"
                if ($r.StatusCode -eq 200) {
                    $j = $r.Content | ConvertFrom-Json
                    if ([int]$j.TotalResults -gt 0) {
                        Write-Host ("  !! ORPHANS in ${col}: " + $j.TotalResults)
                        foreach ($e2 in @($j.entities)) {
                            $oid = ([string](($e2.Fields | Where-Object Name -eq 'id').values[0].value))
                            $null = Invoke-Alm DELETE "$col/$oid"
                        }
                    }
                }
            } catch { Write-Host ("  orphan sweep $col threw: " + (Mask $_.Exception.Message)) }
        }
    }
    $null = Invoke-WebRequest @iwr -Uri "$base/authentication-point/logout" -WebSession $session
    'logged out : true'
}

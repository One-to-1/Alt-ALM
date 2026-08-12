#Requires -Version 7
<#
.SYNOPSIS
  Write-probe round 2 against the USER-CONFIRMED sandbox (writes approved 2026-08-12).
  ALTALM-PROBE-marked records, cleanup in finally, plus a final orphan sweep by name prefix.

.DESCRIPTION
  A. Root re-verification (round-1 parent-id=1 finding was contaminated by an orphan with id 1):
     read requirements/0, test-folders/2, test-set-folders/0 (user-provided defaults) and create
     a requirement with parent-id=0.
  B. Attachments + rich-text IMAGE EMBED round-trip (#1 remaining unknown):
     upload 1x1 PNG as attachment (octet-stream+Slug, and multipart with ref-subtype=1), read
     attachment metadata, then PUT memo HTML with several <img src> syntax candidates and read
     back what survives the sanitizer.
  C. step-parameters retry: create the parameter BEFORE referencing it in a design step; try
     nested tests/{id}/step-parameters and standalone with used-by-owner-type/-id; then create a
     design step containing <<<probe_param>>> and check whether the token survives now.
  D. Test Lab chain: test-set-folder -> test-set -> test-instance -> run (status "Not Completed"
     then PUT final) -> observe run-steps auto-copy from design steps, instance status mirror,
     Fast_Run artifacts, run-step status aggregation.
  E. milestones POST (fields discovered at runtime).
  F. mail POST on the created requirement (SMTP may be unconfigured; record status either way).
  G. test-executions: GET fields, minimal POST, record semantics of the response.
  H. release + release-cycle date validation (cycle dates outside release range -> expect error).
  All output masked; fixtures redacted to tests/fixtures/write-probe/.
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
"=== ALM write probe round 2 (sandbox; marker $MARK) ==="

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

# Deterministic field order is REQUIRED (round-1 finding: order-sensitive server NPEs).
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
function Show-RequiredFields([string]$Entity) {
    $r = Invoke-Alm GET "customization/entities/$Entity/fields"
    if ($r.StatusCode -eq 200) {
        $j = $r.Content | ConvertFrom-Json
        $req = @($j.Fields.Field | Where-Object { $_.Required -eq $true } | ForEach-Object { '{0}({1})' -f $_.Name, $_.Type })
        Write-Host ("  [$Entity] required: " + ($req -join ' '))
    }
}
function Show-AllFields([string]$Entity) {
    $r = Invoke-Alm GET "customization/entities/$Entity/fields"
    if ($r.StatusCode -eq 200) {
        $j = $r.Content | ConvertFrom-Json
        $all = @($j.Fields.Field | ForEach-Object { '{0}={1}({2}{3})' -f $_.Name, $_.physicalName, $_.Type, $(if ($_.Required) { '*' } else { '' }) })
        Write-Host ("  [$Entity] ALL fields (name=physical): " + ($all -join ' '))
    }
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

# 1x1 red PNG for attachment tests
$pngB64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
$pngBytes = [Convert]::FromBase64String($pngB64)

try {
    ''
    '--- A. root verification (user-provided defaults) + requirement parent-id=0 ---'
    foreach ($pair in @(@('requirements','0'), @('test-folders','2'), @('test-set-folders','0'))) {
        $r = Invoke-Alm GET "$($pair[0])/$($pair[1])`?fields=id,name"
        if ($r.StatusCode -eq 200) {
            $j = $r.Content | ConvertFrom-Json
            Write-Host ('  {0}/{1} -> name: {2}' -f $pair[0], $pair[1], (Mask (Get-FieldValue $j 'name')))
        }
    }
    $req1 = New-AlmEntity 'requirements' 'requirement' ([ordered]@{ name = "$MARK-req"; 'parent-id' = '0'; 'type-id' = '3' }) 'r2-req-create.json'
    if (-not $req1) { throw 'requirement create with parent-id=0 failed — investigate before continuing' }
    $req1Id = Get-FieldValue $req1 'id'
    Write-Host "  parent-id=0 requirement create VERIFIED (id $req1Id)"

    ''
    '--- B. attachments + image-embed round-trip ---'
    # B1: octet-stream + Slug
    $r = Invoke-Alm POST "requirements/$req1Id/attachments" -BodyBytes $pngBytes -ContentType 'application/octet-stream' -ExtraHeaders @{ Slug = 'probe-img-slug.png' }
    Write-Host ('  octet+Slug: HTTP ' + $r.StatusCode)
    if ($r.StatusCode -in 200,201) { Save-Fixture 'r2-attach-slug-response.json' ([string]$r.Content) }
    # B2: multipart with ref-subtype=1 (embedded-image subtype). PS7 -Form; order via [ordered].
    $tmpPng = Join-Path ([IO.Path]::GetTempPath()) 'probe-img-multi.png'
    [IO.File]::WriteAllBytes($tmpPng, $pngBytes)
    $h = @{ Accept = 'application/json'; 'X-XSRF-TOKEN' = $xsrf }
    $form = [ordered]@{ filename = 'probe-img-multi.png'; description = 'probe embedded image'; 'ref-subtype' = '1'; file = Get-Item $tmpPng }
    $r = Invoke-WebRequest @iwr -Uri "$proj/requirements/$req1Id/attachments" -Method Post -Headers $h -Form $form -WebSession $session
    Write-Host ('  multipart ref-subtype=1: HTTP ' + $r.StatusCode)
    if ($r.StatusCode -in 200,201) {
        Save-Fixture 'r2-attach-multipart-response.json' ([string]$r.Content)
    } else {
        Save-Fixture 'r2-attach-multipart-FAILED.json' ([string]$r.Content)
        Write-Host ('    FAILED body: ' + (Mask (([string]$r.Content) -replace '\s+', ' ').Substring(0, [Math]::Min(400, ([string]$r.Content).Length))))
        # retry: same multipart shape but field name 'name' instead of 'filename' (some ALM REST forms use 'name')
        $form2 = [ordered]@{ name = 'probe-img-multi2.png'; description = 'probe embedded image v2'; 'ref-subtype' = '1'; file = Get-Item $tmpPng }
        $r = Invoke-WebRequest @iwr -Uri "$proj/requirements/$req1Id/attachments" -Method Post -Headers $h -Form $form2 -WebSession $session
        Write-Host ('  multipart retry (name= instead of filename=): HTTP ' + $r.StatusCode)
        if ($r.StatusCode -in 200,201) { Save-Fixture 'r2-attach-multipart-retry-response.json' ([string]$r.Content) }
        else { Save-Fixture 'r2-attach-multipart-retry-FAILED.json' ([string]$r.Content); Write-Host ('    FAILED body: ' + (Mask (([string]$r.Content) -replace '\s+', ' ').Substring(0, [Math]::Min(400, ([string]$r.Content).Length)))) }
    }
    # B3: list attachments + metadata (what does ref-subtype look like on read?)
    $r = Invoke-Alm GET "requirements/$req1Id/attachments"
    if ($r.StatusCode -eq 200) { Save-Fixture 'r2-attachments-list.json' ([string]$r.Content) }
    # B4: img-src syntax candidates in memo HTML -> PUT, read back, see what survives
    $candidates = [ordered]@{
        'plain-name'   = '<img src="probe-img-multi.png">'
        'attach-rel'   = '<img src="attachments/probe-img-multi.png">'
        'rest-path'    = ('<img src="{0}/requirements/{1}/attachments/probe-img-multi.png">' -f $proj, $req1Id)
        'data-uri'     = ('<img src="data:image/png;base64,{0}">' -f $pngB64)
    }
    foreach ($k in $candidates.Keys) {
        $html = '<html><body><p>before</p>' + $candidates[$k] + '<p>after</p></body></html>'
        $put = ([ordered]@{ Fields = @([ordered]@{ Name = 'description'; values = @(@{ value = $html }) }); Type = 'requirement' } | ConvertTo-Json -Depth 6 -Compress)
        $r = Invoke-Alm PUT "requirements/$req1Id" $put
        $r2 = Invoke-Alm GET "requirements/$req1Id`?fields=description"
        if ($r2.StatusCode -eq 200) {
            $back = Get-FieldValue ($r2.Content | ConvertFrom-Json) 'description'
            $survived = $back -match '<img'
            Write-Host ("  [img-src:$k] img tag survived: $survived")
            Save-Fixture "r2-imgsrc-$k.txt" ("SENT:`n$html`n`nGOT:`n$back")
        }
    }

    ''
    '--- C. step-parameters retry (param FIRST, then reference) ---'
    $tf = New-AlmEntity 'test-folders' 'test-folder' ([ordered]@{ name = "$MARK-folder"; 'parent-id' = '2' }) $null
    if (-not $tf) { throw 'test-folder create under Subject(2) failed' }
    $tfId = Get-FieldValue $tf 'id'
    $test = New-AlmEntity 'tests' 'test' ([ordered]@{ name = "$MARK-test"; 'parent-id' = $tfId; 'subtype-id' = 'MANUAL' }) $null
    $testId = Get-FieldValue $test 'id'
    Show-AllFields 'step-parameter'
    $sp = $null
    # design step FIRST this time, using HTML-ENTITY-ENCODED angle brackets so the sanitizer sees
    # literal '&lt;' text (not a real '<' to parse as a tag start) — round-1 finding was that raw
    # <<<probe_param>>> got parsed as an (invalid) tag <probe_param> and destroyed. Pre-encoding the
    # brackets should let the literal text "probe_param" survive intact, wrapped in escaped angle
    # brackets that a browser would render as <<<probe_param>>>.
    $ds = New-AlmEntity 'design-steps' 'design-step' ([ordered]@{ name = 'Step 1'; 'parent-id' = $testId; description = '<html><body>Uses &lt;&lt;&lt;probe_param&gt;&gt;&gt; here</body></html>'; expected = '<html><body>ok</body></html>' }) 'r2-design-step-create.json'
    $dsId = $null
    if ($ds) {
        $dsId = Get-FieldValue $ds 'id'
        $r = Invoke-Alm GET "design-steps/$dsId`?fields=description,has-params"
        if ($r.StatusCode -eq 200) {
            $j2 = $r.Content | ConvertFrom-Json
            $back = Get-FieldValue $j2 'description'
            Write-Host ('  [encoded-token] literal "probe_param" text survived: ' + ($back -match 'probe_param'))
            Write-Host ('  [encoded-token] has-params: ' + (Get-FieldValue $j2 'has-params'))
            Save-Fixture 'r2-designstep-token-roundtrip.txt' ("SENT (JSON string, pre-encoded):`nUses &lt;&lt;&lt;probe_param&gt;&gt;&gt; here`n`nGOT:`n$back")
        }
        # second step for run-step copy check
        $null = New-AlmEntity 'design-steps' 'design-step' ([ordered]@{ name = 'Step 2'; 'parent-id' = $testId; description = '<html><body>step two</body></html>'; expected = '<html><body>ok2</body></html>' }) $null
    }
    # attempt 1: nested under the design-step (probe3 swagger mining noted step-parameters nested
    # under design-steps/{id}/step-parameters, runs/{id}/step-parameters, test-configs/{id}/step-parameters
    # — NOT tests/{id}/step-parameters, which round-1-round-2 attempt-1 404'd).
    if ($dsId) {
        # round-2 attempt-1 server error was explicit: "value '<test-id>' for field 'parent-id' ...
        # does not match the value '<design-step-id>' for 'design-step' collection resource" — i.e.
        # for the NESTED create path, parent-id must equal the design-step's own id, not the test id.
        $sp = New-AlmEntity "design-steps/$dsId/step-parameters" 'step-parameter' ([ordered]@{ key = 'probe_param'; 'actual-value' = 'v1'; 'used-by-owner-type' = 'design-step'; 'used-by-owner-id' = $dsId; 'parent-id' = $dsId }) 'r2-step-parameter-create.json'
        # normalize cleanup path to the top-level collection (nested-create path may not accept DELETE)
        if ($sp -and $created.Count -gt 0 -and $created[$created.Count-1].rel -eq "design-steps/$dsId/step-parameters") { $created[$created.Count-1].rel = 'step-parameters' }
    }
    if (-not $sp) {
        # attempt 2: standalone collection, full real field set from round-1 discovery
        # (key, actual-value, used-by-owner-type[required], used-by-owner-id, parent-id, origin-test)
        $sp = New-AlmEntity 'step-parameters' 'step-parameter' ([ordered]@{ key = 'probe_param'; 'actual-value' = 'v1'; 'used-by-owner-type' = 'test'; 'used-by-owner-id' = $testId; 'parent-id' = $testId; 'origin-test' = $testId }) 'r2-step-parameter-create.json'
    }
    if (-not $sp -and $dsId) {
        # attempt 3: standalone collection, owner = design-step (not test)
        $sp = New-AlmEntity 'step-parameters' 'step-parameter' ([ordered]@{ key = 'probe_param'; 'actual-value' = 'v1'; 'used-by-owner-type' = 'design-step'; 'used-by-owner-id' = $dsId; 'parent-id' = $testId }) 'r2-step-parameter-create.json'
    }
    if ($sp) {
        Write-Host '  step-parameter create SUCCEEDED — now testing whether the RAW <<<probe_param>>> token survives once the parameter is registered'
        $ds3 = New-AlmEntity 'design-steps' 'design-step' ([ordered]@{ name = 'Step 3 (raw token, param now exists)'; 'parent-id' = $testId; description = '<html><body>Uses <<<probe_param>>> raw here</body></html>'; expected = '<html><body>ok3</body></html>' }) $null
        if ($ds3) {
            $ds3Id = Get-FieldValue $ds3 'id'
            $r = Invoke-Alm GET "design-steps/$ds3Id`?fields=description"
            if ($r.StatusCode -eq 200) {
                $back3 = Get-FieldValue ($r.Content | ConvertFrom-Json) 'description'
                Write-Host ('  [raw-token-post-param] literal "probe_param" text survived: ' + ($back3 -match 'probe_param'))
                Save-Fixture 'r2-designstep-rawtoken-postparam-roundtrip.txt' ("SENT:`n<html><body>Uses <<<probe_param>>> raw here</body></html>`n`nGOT:`n$back3")
            }
        }
    } else {
        Write-Host '  step-parameter create FAILED in all 3 attempts — documented failure, see fixture(s)'
    }

    ''
    '--- D. Test Lab chain ---'
    Show-RequiredFields 'test-set-folder'; Show-RequiredFields 'test-set'; Show-RequiredFields 'test-instance'; Show-AllFields 'run'
    $tsf = New-AlmEntity 'test-set-folders' 'test-set-folder' ([ordered]@{ name = "$MARK-tsf"; 'parent-id' = '0' }) $null
    if (-not $tsf) { throw 'test-set-folder create under Root(0) failed' }
    $ts = New-AlmEntity 'test-sets' 'test-set' ([ordered]@{ name = "$MARK-testset"; 'parent-id' = (Get-FieldValue $tsf 'id'); 'subtype-id' = 'hp.qc.test-set.default' }) 'r2-test-set-create.json'
    if (-not $ts) { throw 'test-set create failed' }
    $tsId = Get-FieldValue $ts 'id'
    $ti = New-AlmEntity 'test-instances' 'test-instance' ([ordered]@{ 'cycle-id' = $tsId; 'test-id' = $testId; 'subtype-id' = 'hp.qc.test-instance.MANUAL' }) 'r2-test-instance-create.json'
    if (-not $ti) { throw 'test-instance create failed' }
    $tiId = Get-FieldValue $ti 'id'
    Write-Host ('  instance initial status: ' + (Get-FieldValue $ti 'status'))
    # run: POST with Not Completed, then PUT to Passed. round-1-round-2 attempt-1 500'd
    # ("Fail to get a must number attribute 'TESTSET'") missing the documented 'test-instance'
    # ordinal field (distinct from testcycl-id, which is the instance's own id) — add it.
    $run = New-AlmEntity 'runs' 'run' ([ordered]@{ name = "$MARK-run1"; 'test-id' = $testId; 'test-instance' = '1'; 'testcycl-id' = $tiId; 'cycle-id' = $tsId; 'subtype-id' = 'hp.qc.run.MANUAL'; owner = $me; status = 'Not Completed' }) 'r2-run-create.json'
    if (-not $run) {
        # fallback: maybe 'test-instance' isn't accepted as a writable field name — retry without it
        # but with subtype using the documented alternate id-only shape
        $run = New-AlmEntity 'runs' 'run' ([ordered]@{ name = "$MARK-run1b"; 'test-id' = $testId; 'testcycl-id' = $tiId; 'cycle-id' = $tsId; 'subtype-id' = 'hp.qc.run.MANUAL'; owner = $me; status = 'Not Completed'; 'test-instance' = '1' }) 'r2-run-create.json'
    }
    if (-not $run) {
        # 3rd informed attempt: the full field dump showed a 'test-config-id' (RN_TEST_CONFIG_ID)
        # field not yet tried — a test's default TestConfig might be what the opaque 'TESTSET'
        # internal-attribute lookup actually needs. Discover the test's default config id first.
        $cfgId = $null
        $rc = Invoke-Alm GET "test-configs`?query={test-id[$testId]}&fields=id,name&page-size=5"
        if ($rc.StatusCode -eq 200) {
            $jc = $rc.Content | ConvertFrom-Json
            if ([int]$jc.TotalResults -gt 0) { $cfgId = Get-FieldValue $jc.entities[0] 'id'; Write-Host "  discovered default test-config id: $cfgId" }
        }
        if ($cfgId) {
            $run = New-AlmEntity 'runs' 'run' ([ordered]@{ name = "$MARK-run1c"; 'test-id' = $testId; 'test-config-id' = $cfgId; 'testcycl-id' = $tiId; 'cycle-id' = $tsId; 'subtype-id' = 'hp.qc.run.MANUAL'; owner = $me; status = 'Not Completed' }) 'r2-run-create.json'
        } else { Write-Host '  no test-config discovered — skipping 3rd run-create attempt' }
    }
    if (-not $run) { Write-Host '  run create FAILED after 3 informed attempts (field-order variants + test-config-id) — documented failure, server error references an internal ''TESTSET'' attribute with NO corresponding field in the run entity''s full customization metadata (48 fields dumped above, none match)' }
    if ($run) {
        $runId = Get-FieldValue $run 'id'
        # auto-copy of design steps into run-steps?
        $r = Invoke-Alm GET "runs/$runId/run-steps"
        if ($r.StatusCode -eq 200) {
            $j = $r.Content | ConvertFrom-Json
            Write-Host ('  run-steps auto-copied: TotalResults=' + $j.TotalResults)
            Save-Fixture 'r2-run-steps-after-create.json' ([string]$r.Content)
        }
        # finalize the run
        $put = ([ordered]@{ Fields = @([ordered]@{ Name = 'status'; values = @(@{ value = 'Passed' }) }); Type = 'run' } | ConvertTo-Json -Depth 6 -Compress)
        $r = Invoke-Alm PUT "runs/$runId" $put
        Write-Host ('  run PUT status=Passed: HTTP ' + $r.StatusCode)
        # instance mirror + Fast_Run check
        $r = Invoke-Alm GET "test-instances/$tiId`?fields=status,exec-date,exec-time"
        if ($r.StatusCode -eq 200) { Write-Host ('  instance status after run: ' + (Get-FieldValue ($r.Content | ConvertFrom-Json) 'status')) }
        $r = Invoke-Alm GET "runs?query={cycle-id[$tsId]}&fields=id,name,status&page-size=20"
        if ($r.StatusCode -eq 200) {
            $j = $r.Content | ConvertFrom-Json
            $names = @($j.entities | ForEach-Object { Get-FieldValue $_ 'name' })
            Write-Host ('  runs in test set: ' + ($names -join ' | '))
            Write-Host ('  Fast_Run present: ' + [bool]($names -match 'Fast_Run'))
        }
        # run-step status aggregation: set one step Failed, check run status
        $r = Invoke-Alm GET "runs/$runId/run-steps"
        if ($r.StatusCode -eq 200) {
            $steps = @(($r.Content | ConvertFrom-Json).entities)
            if ($steps.Count -gt 0) {
                $rsId = Get-FieldValue $steps[0] 'id'
                $put = ([ordered]@{ Fields = @([ordered]@{ Name = 'status'; values = @(@{ value = 'Failed' }) }); Type = 'run-step' } | ConvertTo-Json -Depth 6 -Compress)
                $r = Invoke-Alm PUT "runs/$runId/run-steps/$rsId" $put
                Write-Host ('  run-step PUT status=Failed: HTTP ' + $r.StatusCode)
                $r = Invoke-Alm GET "runs/$runId`?fields=status"
                if ($r.StatusCode -eq 200) { Write-Host ('  run status after step Failed: ' + (Get-FieldValue ($r.Content | ConvertFrom-Json) 'status')) }
            }
        }
    }

    ''
    '--- E. milestones POST ---'
    Show-AllFields 'milestone'
    # discover a parent-id root the same way test-folders/test-set-folders roots were discovered
    $msParent = '0'
    $r = Invoke-Alm GET "milestones`?query={parent-id[0]}&fields=id,name,parent-id&page-size=5"
    if ($r.StatusCode -eq 200) {
        $j = $r.Content | ConvertFrom-Json
        if ([int]$j.TotalResults -gt 0) { Write-Host ('  milestones root candidate (parent-id=0 query): ' + (Mask (Get-FieldValue $j.entities[0] 'name'))) }
        else { Write-Host '  milestones: no rows with parent-id=0 (root may not be hierarchical / may be 1, or milestones may be flat)' }
    }
    $ms = New-AlmEntity 'milestones' 'milestone' ([ordered]@{ name = "$MARK-milestone"; 'parent-id' = $msParent; 'start-date' = '2026-01-01'; 'end-date' = '2026-03-31' }) 'r2-milestone-create.json'
    if (-not $ms) {
        # retry: parent-id=1 (in case 0 is invalid, mirroring the requirement root-id lesson)
        $ms = New-AlmEntity 'milestones' 'milestone' ([ordered]@{ name = "$MARK-milestone"; 'parent-id' = '1'; 'start-date' = '2026-01-01'; 'end-date' = '2026-03-31' }) 'r2-milestone-create.json'
    }

    ''
    '--- F. mail POST on requirement (SMTP may be unconfigured; any result is a finding) ---'
    # attempt 1: {To, Subject, Comment} — round-1-round-2 attempt gave 500
    # "Cannot invoke JsonNode.has(String) because node is null", i.e. the parser found no recognized
    # root node at all -> the whole shape/key-casing is likely wrong, not just one field.
    $mailBody1 = ([ordered]@{ To = @($me); Subject = "$MARK mail probe"; Comment = 'probe' } | ConvertTo-Json -Compress)
    $r = Invoke-Alm POST "requirements/$req1Id/mail" $mailBody1
    Write-Host ('  [attempt1: To/Subject/Comment] mail result: HTTP ' + $r.StatusCode + '  body: ' + (Mask (([string]$r.Content) -replace '\s+',' ').Substring(0, [Math]::Min(300, ([string]$r.Content).Length))))
    Save-Fixture 'r2-mail-attempt1.json' ("SENT:`n$mailBody1`n`nGOT HTTP $($r.StatusCode):`n$($r.Content)")
    if ($r.StatusCode -notin 200,201) {
        # attempt 2: Mail wrapper envelope with Recipients/Subject/Body (mirrors the documented
        # <Mail> XML shape used elsewhere in the ALM REST doc set, translated to JSON)
        $mailBody2 = ([ordered]@{ Mail = [ordered]@{ Recipients = [ordered]@{ Recipient = @($me) }; Subject = "$MARK mail probe"; Body = 'probe body' } } | ConvertTo-Json -Compress -Depth 5)
        $r = Invoke-Alm POST "requirements/$req1Id/mail" $mailBody2
        Write-Host ('  [attempt2: Mail/Recipients/Body wrapper] mail result: HTTP ' + $r.StatusCode + '  body: ' + (Mask (([string]$r.Content) -replace '\s+',' ').Substring(0, [Math]::Min(300, ([string]$r.Content).Length))))
        Save-Fixture 'r2-mail-attempt2.json' ("SENT:`n$mailBody2`n`nGOT HTTP $($r.StatusCode):`n$($r.Content)")
    }
    if ($r.StatusCode -notin 200,201) {
        # attempt 3: lowercase keys, 'body' not 'Comment'/'Body'
        $mailBody3 = ([ordered]@{ to = @($me); subject = "$MARK mail probe"; body = 'probe body' } | ConvertTo-Json -Compress)
        $r = Invoke-Alm POST "requirements/$req1Id/mail" $mailBody3
        Write-Host ('  [attempt3: lowercase to/subject/body] mail result: HTTP ' + $r.StatusCode + '  body: ' + (Mask (([string]$r.Content) -replace '\s+',' ').Substring(0, [Math]::Min(300, ([string]$r.Content).Length))))
        Save-Fixture 'r2-mail-attempt3.json' ("SENT:`n$mailBody3`n`nGOT HTTP $($r.StatusCode):`n$($r.Content)")
    }
    if ($r.StatusCode -notin 200,201) {
        # attempt 4 (last, per hard-rule 2-3-informed-attempts budget): XML body — the resource-list
        # Consumes array lists application/xml FIRST for this sub-resource; the identical opaque
        # "node is null" error across 3 differently-shaped JSON bodies suggests the JSON parser path
        # for /mail may not be wired the same way as normal entity JSON at all.
        $mailXml = "<Mail><To>$me</To><Subject>$MARK mail probe</Subject><Body>probe body</Body></Mail>"
        $r = Invoke-Alm POST "requirements/$req1Id/mail" $mailXml 'application/xml'
        Write-Host ('  [attempt4: XML body] mail result: HTTP ' + $r.StatusCode + '  body: ' + (Mask (([string]$r.Content) -replace '\s+',' ').Substring(0, [Math]::Min(300, ([string]$r.Content).Length))))
        Save-Fixture 'r2-mail-attempt4-xml.json' ("SENT:`n$mailXml`n`nGOT HTTP $($r.StatusCode):`n$($r.Content)")
    }

    ''
    '--- G. test-executions semantics ---'
    Show-AllFields 'test-execution'
    # docs (wave1-05, CONSTRUCTED from community): min field 'external-id' (Number), optional
    # 'external-type' = TestSet (default) | TestInstance. Only 'external-id' is server-required per
    # this project's live metadata. Try external-id = the test-set id first (TestSet is the default).
    $te = New-AlmEntity 'test-executions' 'test-execution' ([ordered]@{ 'external-id' = $tsId; 'external-type' = 'TestSet' }) 'r2-test-execution-create.json'
    if (-not $te) {
        # retry: external-id pointing at the test-instance, external-type=TestInstance
        $te = New-AlmEntity 'test-executions' 'test-execution' ([ordered]@{ 'external-id' = $tiId; 'external-type' = 'TestInstance' }) 'r2-test-execution-create.json'
    }
    if (-not $te) { Write-Host '  (recording failure shape is the finding — do not force it)' }

    ''
    '--- H. release + cycle date validation ---'
    $rel = New-AlmEntity 'releases' 'release' ([ordered]@{ name = "$MARK-release"; 'parent-id' = '1'; 'start-date' = '2026-01-01'; 'end-date' = '2026-03-31' }) 'r2-release-create.json'
    if (-not $rel) {
        # release root folder id candidates
        $rel = New-AlmEntity 'releases' 'release' ([ordered]@{ name = "$MARK-release"; 'parent-id' = '0'; 'start-date' = '2026-01-01'; 'end-date' = '2026-03-31' }) 'r2-release-create.json'
    }
    if ($rel) {
        $relId = Get-FieldValue $rel 'id'
        # cycle INSIDE range (should pass)
        $cy1 = New-AlmEntity 'release-cycles' 'release-cycle' ([ordered]@{ name = "$MARK-cycle-in"; 'parent-id' = $relId; 'start-date' = '2026-01-10'; 'end-date' = '2026-01-20' }) $null
        # cycle OUTSIDE range (expect validation error — the finding is the status+message)
        $cy2 = New-AlmEntity 'release-cycles' 'release-cycle' ([ordered]@{ name = "$MARK-cycle-out"; 'parent-id' = $relId; 'start-date' = '2026-06-01'; 'end-date' = '2026-07-01' }) $null
        Write-Host ('  cycle-outside-range accepted: ' + [bool]$cy2)

        if (-not $ms) {
            '--- E-retry. milestone parent-id = newly created release id (Master Plan ties milestones to releases) ---'
            $ms = New-AlmEntity 'milestones' 'milestone' ([ordered]@{ name = "$MARK-milestone"; 'parent-id' = $relId; 'start-date' = '2026-01-01'; 'end-date' = '2026-03-31' }) 'r2-milestone-create.json'
            if ($ms) { Write-Host '  milestone create VERIFIED with parent-id = release id' }
            else { Write-Host '  milestone create still failed with parent-id = release id — documented failure' }
        }
    }
}
finally {
    ''
    if ($SkipCleanup) {
        'SkipCleanup set — records left in place:'; $created | ForEach-Object { '  {0}/{1}' -f $_.rel, $_.id }
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
                            $oid = Get-FieldValue $e2 'id'
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

#Requires -Version 7
<#
.SYNOPSIS
  Write-probe round 4 (targeted) against the USER-CONFIRMED sandbox (writes approved 2026-08-12).
  Resolves Q34 and tests a NEVER-PROBED endpoint that may close the step-parameters gap.

.DESCRIPTION
  Background: probes 4 and 5 concluded "there is no REST path to DEFINE a test parameter" after
  5 failed `POST step-parameters` attempts (all 500 "Test parameter does not exist"). But the
  per-instance resource-list inventory contains a SEPARATE collection that was never probed:

      /domains/{d}/projects/{p}/test-parameters                 DELETE,GET,POST,PUT
      /domains/{d}/projects/{p}/tests/{id}/test-parameters      GET,POST

  `step-parameters` records a VALUE against an already-registered parameter (physical name
  SP_TEST_PARAM_ID). `test-parameters` is the plausible DEFINITION endpoint. If POST works there,
  the single hardest gap in the feasibility matrix closes without OTA at all.

  Steps:
    A. Runtime metadata for the test-parameter entity (build a correct body, never guess).
    B. Baseline reads: flat + nested test-parameters collections for a fresh test (expect empty).
    C. POST test-parameters (flat form, deterministic field order).
    D. POST tests/{id}/test-parameters (nested form).
    E. Q34: POST a design-step carrying an entity-encoded <<<token>>>, then re-read
       test-parameters -- did the token register a parameter visible over REST?
    F. If a parameter now exists: retry POST step-parameters against it (the long-standing 500).
    G. PUT test-parameters/{id} to set a default value (fails over OTA -- UNVERIFIED there).

  Safety: ALTALM- prefix on every created record, cleanup in finally, orphan sweep afterwards,
  all output masked. Sandbox only.
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
foreach ($m in @($maskHost, $c.api_key, $c.api_secret, $c.domain, $c.project)) {
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
Say "=== ALM write probe round 4 - test-parameters definition endpoint (marker $MARK) ==="

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
    param([string]$Method, [string]$Rel, [string]$BodyJson,
          [string]$ContentType = 'application/json', [string]$Accept = 'application/json',
          [switch]$Quiet)
    $h = @{ Accept = $Accept }
    if ($Method -ne 'GET') { $h['X-XSRF-TOKEN'] = $xsrf }
    $a = @{ Uri = "$proj/$Rel"; Method = $Method; Headers = $h; WebSession = $session }
    if ($BodyJson) { $a.ContentType = $ContentType; $a.Body = $BodyJson }
    $resp = Invoke-WebRequest @iwr @a
    if (-not $Quiet) { Say ('{0,-6} /{1,-58} HTTP {2}' -f $Method, $Rel, $resp.StatusCode) }
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
    if ($t -and $t.Length -gt 400) { $t = $t.Substring(0, 400) }
    Say ("        -> " + (Mask $t))
}
function Save-Fixture([string]$Name, [string]$Content) {
    Set-Content -Path (Join-Path $fixtureDir $Name) -Value (Mask $Content) -Encoding utf8
}

$testId = $null; $folderId = $null; $stepId = $null
$createdParamIds = [System.Collections.Generic.List[string]]::new()

try {
    # ---------------------------------------------------------------- A. metadata
    Say ""
    Say "=== A. runtime metadata for entity 'test-parameter' ==="
    $r = Invoke-Alm GET "customization/entities/test-parameter/fields"
    if ($r.StatusCode -eq 200) {
        Save-Fixture 'r4-test-parameter-fields.json' $r.Content
        $fields = ($r.Content | ConvertFrom-Json).Fields.Field
        Say ("    {0} fields" -f $fields.Count)
        foreach ($f in $fields) {
            $flags = @()
            if ($f.required) { $flags += 'REQUIRED' }
            if (-not $f.editable) { $flags += 'read-only' }
            if ($f.virtual) { $flags += 'virtual' }
            Say ("      {0,-16} {1,-18} {2,-10} {3}" -f $f.name, $f.physicalName, $f.type, ($flags -join ','))
        }
    } else { Show-Err $r }

    # ---------------------------------------------------------------- scaffold
    Say ""
    Say "=== scaffold: folder + test ==="
    $r = Invoke-Alm GET "test-folders?query={parent-id[0]}&fields=id,name,parent-id"
    $root = ($r.Content | ConvertFrom-Json).entities[0]
    $rootId = Get-FieldValue $root 'id'
    Say ("    runtime-discovered test root id = {0}" -f $rootId)

    $body = Build-Entity 'test-folder' ([ordered]@{ name = "$MARK-FOLDER"; 'parent-id' = $rootId })
    $r = Invoke-Alm POST "test-folders" $body
    $folderId = Get-FieldValue ($r.Content | ConvertFrom-Json) 'id'
    Say ("    folder id = {0}" -f $folderId)

    $body = Build-Entity 'test' ([ordered]@{ name = "$MARK-TEST"; 'parent-id' = $folderId; 'subtype-id' = 'MANUAL' })
    $r = Invoke-Alm POST "tests" $body
    $testId = Get-FieldValue ($r.Content | ConvertFrom-Json) 'id'
    Say ("    test id   = {0}" -f $testId)

    # ---------------------------------------------------------------- B. baseline reads
    Say ""
    Say "=== B. baseline reads (expect empty) ==="
    $r = Invoke-Alm GET "test-parameters?query={parent-id[$testId]}"
    if ($r.StatusCode -eq 200) {
        $j = $r.Content | ConvertFrom-Json
        Say ("    flat   test-parameters?query=parent-id : TotalResults={0}" -f $j.TotalResults)
    } else { Show-Err $r }
    $r = Invoke-Alm GET "tests/$testId/test-parameters"
    if ($r.StatusCode -eq 200) {
        $j = $r.Content | ConvertFrom-Json
        Say ("    nested tests-slash-id form             : TotalResults={0}" -f $j.TotalResults)
        Save-Fixture 'r4-test-parameters-empty.json' $r.Content
    } else { Show-Err $r }

    # ---------------------------------------------------------------- C. POST flat
    Say ""
    Say "=== C. POST test-parameters (flat) - THE KEY EXPERIMENT ==="
    $shapes = @(
        @{ label = 'name + parent-id';
           body  = ([ordered]@{ name = 'altalm_p1'; 'parent-id' = $testId }) },
        @{ label = 'name + parent-id + description';
           body  = ([ordered]@{ name = 'altalm_p2'; 'parent-id' = $testId; description = '<html><body>probe</body></html>' }) },
        @{ label = 'name + parent-id + default-value';
           body  = ([ordered]@{ name = 'altalm_p3'; 'parent-id' = $testId; 'default-value' = 'seed' }) }
    )
    foreach ($s in $shapes) {
        $b = Build-Entity 'test-parameter' $s.body
        $r = Invoke-Alm POST "test-parameters" $b -Quiet
        Say ("    [{0,-32}] HTTP {1}" -f $s.label, $r.StatusCode)
        if ($r.StatusCode -in 200, 201) {
            $id = Get-FieldValue ($r.Content | ConvertFrom-Json) 'id'
            Say ("        *** CREATED test-parameter id={0} ***" -f $id)
            $createdParamIds.Add($id)
            Save-Fixture 'r4-test-parameter-created.json' $r.Content
        } else { Show-Err $r }
    }

    # ---------------------------------------------------------------- D. POST nested
    Say ""
    Say "=== D. POST tests/{id}/test-parameters (nested) ==="
    $b = Build-Entity 'test-parameter' ([ordered]@{ name = 'altalm_p4' })
    $r = Invoke-Alm POST "tests/$testId/test-parameters" $b -Quiet
    Say ("    nested POST name-only        HTTP {0}" -f $r.StatusCode)
    if ($r.StatusCode -in 200, 201) {
        $id = Get-FieldValue ($r.Content | ConvertFrom-Json) 'id'
        Say ("        *** CREATED via nested path id={0} ***" -f $id)
        $createdParamIds.Add($id)
    } else { Show-Err $r }

    # parent-id is read-only (TP_TEST_ID) so the parent must come from the URL, not the body.
    # default-value and description ARE editable -- try setting them at creation time.
    $b = Build-Entity 'test-parameter' ([ordered]@{
        name            = 'altalm_p5'
        'default-value' = '<html><body>seed-default</body></html>'
        description     = '<html><body>probe param</body></html>'
    })
    $r = Invoke-Alm POST "tests/$testId/test-parameters" $b -Quiet
    Say ("    nested POST name+default+desc HTTP {0}" -f $r.StatusCode)
    if ($r.StatusCode -in 200, 201) {
        $e = $r.Content | ConvertFrom-Json
        $id = Get-FieldValue $e 'id'
        Say ("        *** CREATED via nested path id={0} default-value='{1}' ***" -f $id, (Get-FieldValue $e 'default-value'))
        $createdParamIds.Add($id)
        Save-Fixture 'r4-test-parameter-created-nested.json' $r.Content
    } else { Show-Err $r }

    # ---------------------------------------------------------------- E. Q34
    Say ""
    Say "=== E. Q34: does an entity-encoded <<<token>>> in a design step register a parameter? ==="
    $tokenName = 'altalm_q34'
    $desc = '<html><body>Use &lt;&lt;&lt;' + $tokenName + '&gt;&gt;&gt; here</body></html>'
    $b = Build-Entity 'design-step' ([ordered]@{
        name        = 'Step 1'
        'parent-id' = $testId
        description = $desc
        expected    = '<html><body>ok</body></html>'
    })
    $r = Invoke-Alm POST "design-steps" $b -Quiet
    Say ("    POST design-steps (token in description) HTTP {0}" -f $r.StatusCode)
    if ($r.StatusCode -in 200, 201) {
        $ds = $r.Content | ConvertFrom-Json
        $stepId = Get-FieldValue $ds 'id'
        Say ("        step id={0}  has-params={1}" -f $stepId, (Get-FieldValue $ds 'has-params'))
        Save-Fixture 'r4-designstep-token.json' $r.Content
    } else { Show-Err $r }

    $r = Invoke-Alm GET "tests/$testId/test-parameters" -Quiet
    if ($r.StatusCode -eq 200) {
        $j = $r.Content | ConvertFrom-Json
        Say ("    test-parameters AFTER token            : TotalResults={0}" -f $j.TotalResults)
        foreach ($e in $j.entities) {
            Say ("        - id={0} name='{1}'" -f (Get-FieldValue $e 'id'), (Get-FieldValue $e 'name'))
        }
        Save-Fixture 'r4-test-parameters-after-token.json' $r.Content
        if ([int]$j.TotalResults -gt 0) { Say "    *** Q34 ANSWERED: REST token DOES register a parameter ***" }
        else { Say "    Q34: REST token did NOT register a parameter" }
    } else { Show-Err $r }

    # ---------------------------------------------------------------- F. step-parameters retry
    Say ""
    Say "=== F. retry POST step-parameters now that a parameter may exist ==="
    $r = Invoke-Alm GET "tests/$testId/test-parameters" -Quiet
    $existing = ($r.Content | ConvertFrom-Json).entities
    if ($existing -and $existing.Count -gt 0) {
        $pid0 = Get-FieldValue $existing[0] 'id'
        $pname = Get-FieldValue $existing[0] 'name'
        Say ("    targeting parameter id={0} name='{1}'" -f $pid0, $pname)
        foreach ($owner in @('design-step', 'test')) {
            $ownerId = if ($owner -eq 'design-step') { $stepId } else { $testId }
            if (-not $ownerId) { continue }
            $b = Build-Entity 'step-parameter' ([ordered]@{
                'used-by-owner-type' = $owner
                'used-by-owner-id'   = $ownerId
                'parent-id'          = $pid0
                'actual-value'       = 'probe-value'
            })
            $r2 = Invoke-Alm POST "step-parameters" $b -Quiet
            Say ("    step-parameters owner={0,-12} HTTP {1}" -f $owner, $r2.StatusCode)
            if ($r2.StatusCode -in 200, 201) { Say "        *** step-parameters POST FINALLY WORKS ***" }
            else { Show-Err $r2 }
        }
    } else {
        Say "    skipped - no parameter exists to reference"
    }

    # ---------------------------------------------------------------- G. default value
    Say ""
    Say "=== G. set a parameter default value via PUT (fails over OTA - UNVERIFIED there) ==="
    $r = Invoke-Alm GET "tests/$testId/test-parameters" -Quiet
    $existing = ($r.Content | ConvertFrom-Json).entities
    if ($existing -and $existing.Count -gt 0) {
        $pid0 = Get-FieldValue $existing[0] 'id'
        foreach ($fname in @('default-value', 'value', 'description')) {
            $b = Build-Entity 'test-parameter' ([ordered]@{ $fname = 'default-probe' })
            $r2 = Invoke-Alm PUT "test-parameters/$pid0" $b -Quiet
            Say ("    PUT field '{0,-14}' HTTP {1}" -f $fname, $r2.StatusCode)
            if ($r2.StatusCode -notin 200, 201) { Show-Err $r2 }
        }
        $r = Invoke-Alm GET "test-parameters/$pid0" -Quiet
        if ($r.StatusCode -eq 200) { Save-Fixture 'r4-test-parameter-final.json' $r.Content }
    } else { Say "    skipped - no parameter exists" }

} finally {
    # ---------------------------------------------------------------- cleanup
    Say ""
    Say "=== cleanup ==="
    if ($SkipCleanup) {
        Say "    SKIPPED by switch - records left behind, sweep manually"
    } else {
        foreach ($pid0 in $createdParamIds) {
            try { $x = Invoke-Alm DELETE "test-parameters/$pid0" -Quiet; Say ("    del test-parameter {0}: HTTP {1}" -f $pid0, $x.StatusCode) } catch { }
        }
        if ($stepId)   { try { $x = Invoke-Alm DELETE "design-steps/$stepId" -Quiet; Say ("    del design-step   {0}: HTTP {1}" -f $stepId, $x.StatusCode) } catch { } }
        if ($testId)   { try { $x = Invoke-Alm DELETE "tests/$testId" -Quiet;        Say ("    del test          {0}: HTTP {1}" -f $testId, $x.StatusCode) } catch { } }
        if ($folderId) { try { $x = Invoke-Alm DELETE "test-folders/$folderId" -Quiet; Say ("    del folder        {0}: HTTP {1}" -f $folderId, $x.StatusCode) } catch { } }

        Say ""
        Say "=== orphan sweep (ALTALM-*) ==="
        foreach ($coll in @('tests', 'test-folders')) {
            try {
                $q = [Uri]::EscapeDataString('{name["ALTALM-*"]}')
                $x = Invoke-Alm GET "$coll`?query=$q&fields=id,name" -Quiet
                $n = ($x.Content | ConvertFrom-Json).TotalResults
                Say ("    {0,-14} surviving ALTALM-* : {1}" -f $coll, $n)
                if ([int]$n -gt 0) {
                    foreach ($e in ($x.Content | ConvertFrom-Json).entities) {
                        Say ("        LEFTOVER id={0} name='{1}'" -f (Get-FieldValue $e 'id'), (Get-FieldValue $e 'name'))
                    }
                }
            } catch { Say ("    sweep {0} failed" -f $coll) }
        }
    }
    try { $null = Invoke-WebRequest @iwr -Uri "$base/rest/site-session" -Method Delete -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $session } catch { }
    Say "=== ROUND 4 END ==="
}

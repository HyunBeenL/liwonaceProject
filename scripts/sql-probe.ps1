<#
.SYNOPSIS
    nl2sql 도구를 데이터셋의 예시 질문으로 검증한다.

.DESCRIPTION
    질문을 그대로 넘기고, 모델이 만든 SQL과 실행 결과를 함께 출력한다.
    SQL을 눈으로 확인할 수 있어야 결과가 맞는지 판단할 수 있다.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\sql-probe.ps1
    powershell -ExecutionPolicy Bypass -File .\scripts\sql-probe.ps1 -First 3
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://localhost:8080/mcp',
    [int]    $First   = 0,
    [int]    $TimeoutSec = 300
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$script:session = $null

function Send-Rpc([hashtable]$payload) {
    $json    = ($payload | ConvertTo-Json -Depth 10 -Compress)
    $bytes   = [Text.Encoding]::UTF8.GetBytes($json)
    $headers = @{ 'Accept' = 'application/json, text/event-stream' }
    if ($script:session) { $headers['Mcp-Session-Id'] = $script:session }

    $resp = Invoke-WebRequest -Uri $BaseUrl -Method Post -Body $bytes -UseBasicParsing `
                              -ContentType 'application/json' -Headers $headers -TimeoutSec $TimeoutSec
    if (-not $script:session -and $resp.Headers['Mcp-Session-Id']) {
        $script:session = [string]$resp.Headers['Mcp-Session-Id']
    }
    $body = [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    if ($body -match '(?m)^data:\s*(.+)$') { $body = $Matches[1] }
    if ([string]::IsNullOrWhiteSpace($body)) { return $null }
    return ($body | ConvertFrom-Json)
}

$qPath = Join-Path $PSScriptRoot '..\dataset\questions.json'
$questions = (Get-Content $qPath -Raw -Encoding UTF8 | ConvertFrom-Json) |
             Where-Object { $_.tool -eq 'nl2sql' }
if ($First -gt 0) { $questions = $questions | Select-Object -First $First }

Send-Rpc @{ jsonrpc='2.0'; id=1; method='initialize'; params=@{
    protocolVersion='2025-06-18'; capabilities=@{}; clientInfo=@{ name='sql-probe'; version='1' } } } | Out-Null
Send-Rpc @{ jsonrpc='2.0'; method='notifications/initialized' } | Out-Null

$id = 100
$ok = 0
foreach ($item in $questions) {
    $id++
    Write-Host ''
    Write-Host ('Q. ' + $item.q) -ForegroundColor Green
    Write-Host ('   기대: ' + $item.hint) -ForegroundColor DarkGray

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $call = Send-Rpc @{ jsonrpc='2.0'; id=$id; method='tools/call'; params=@{
        name='nl2sql'; arguments=@{ question=$item.q } } }
    $sw.Stop()

    foreach ($c in $call.result.content) {
        $r = $c.text | ConvertFrom-Json

        if ($r.sql) {
            Write-Host ('   SQL: ' + ($r.sql -replace '\s+', ' ')) -ForegroundColor Cyan
        }
        if ($r.error) {
            Write-Host ('   실패: ' + $r.error) -ForegroundColor Red
            continue
        }

        $ok++
        Write-Host ('   결과 ' + $r.rowCount + '행  (' + [int]$sw.Elapsed.TotalSeconds + '초)')
        $r.rows | Select-Object -First 3 | ForEach-Object {
            $line = ($_.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join '  '
            Write-Host ('     ' + $line)
        }
        if ($r.rowCount -gt 3) { Write-Host ('     ... 외 ' + ($r.rowCount - 3) + '행') -ForegroundColor DarkGray }
    }
}

Write-Host ''
Write-Host ("실행 성공 {0}/{1}" -f $ok, $questions.Count) -ForegroundColor Yellow

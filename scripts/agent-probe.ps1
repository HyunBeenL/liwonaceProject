<#
.SYNOPSIS
    에이전트에 질문을 던져 답변까지 전 구간을 확인한다.

.DESCRIPTION
    질문 -> 라우터 -> MCP 도구 호출 -> Ollama 답변 생성 흐름을 그대로 탄다.
    도구별로 한 문항씩 뽑아 던지는 것이 기본이고, -All을 주면 30문항 전부 실행한다.

    nl2sql은 질문당 20~45초가 걸리므로 -All은 시간이 오래 걸린다.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\agent-probe.ps1
    powershell -ExecutionPolicy Bypass -File .\scripts\agent-probe.ps1 -All
    powershell -ExecutionPolicy Bypass -File .\scripts\agent-probe.ps1 -Question "백업 정책은 어떻게 되어 있어?"
#>
[CmdletBinding()]
param(
    [string] $BaseUrl    = 'http://localhost:8080',
    [string] $Question,
    [switch] $All,
    [int]    $TimeoutSec = 300
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Ask([string]$q) {
    $body  = (@{ question = $q } | ConvertTo-Json -Compress)
    $bytes = [Text.Encoding]::UTF8.GetBytes($body)

    $resp = Invoke-WebRequest -Uri "$BaseUrl/api/ask" -Method Post -Body $bytes -UseBasicParsing `
                              -ContentType 'application/json' -TimeoutSec $TimeoutSec
    $r = [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json

    Write-Host ''
    Write-Host ('Q. ' + $r.question) -ForegroundColor Green
    Write-Host ('   도구 : ' + $r.tool + '  ' + ($r.arguments | ConvertTo-Json -Compress)) -ForegroundColor Cyan
    Write-Host ('   점수 : ' + ($r.routerScores | ConvertTo-Json -Compress)) -ForegroundColor DarkGray
    Write-Host ('   시간 : ' + [math]::Round($r.elapsedMs / 1000, 1) + '초') -ForegroundColor DarkGray
    Write-Host '   답변 :' -ForegroundColor Yellow
    foreach ($line in ($r.answer -split "`n")) {
        if ($line.Trim()) { Write-Host ('     ' + $line.Trim()) }
    }
    return $r
}

if ($Question) {
    Ask $Question | Out-Null
    return
}

$qPath = Join-Path $PSScriptRoot '..\dataset\questions.json'
$questions = Get-Content $qPath -Raw -Encoding UTF8 | ConvertFrom-Json

if ($All) {
    $targets = $questions
} else {
    # 도구별 대표 문항 하나씩
    $targets = @(
        ($questions | Where-Object { $_.tool -eq 'vector_search'   })[5],
        ($questions | Where-Object { $_.tool -eq 'knowledge_graph' })[0],
        ($questions | Where-Object { $_.tool -eq 'nl2sql'          })[1]
    )
}

$ok = 0
foreach ($t in $targets) {
    $r = Ask $t.q
    if ($r.tool -eq $t.tool) { $ok++ }
    else { Write-Host ('   [라우팅 불일치] 기대=' + $t.tool) -ForegroundColor Red }
}

Write-Host ''
Write-Host ("도구 선택 일치 {0}/{1}" -f $ok, $targets.Count) -ForegroundColor Yellow

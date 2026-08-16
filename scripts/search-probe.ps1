<#
.SYNOPSIS
    dataset/questions.json의 vector_search 질문 전체를 DB에 직접 던져 상위 문서를 출력한다.

.DESCRIPTION
    MCP 서버를 거치지 않고 Ollama 임베딩 + PostgreSQL 코사인 검색만 확인한다.
    앱이 떠 있지 않아도 되고, 컨테이너 2개만 살아 있으면 된다.
    검색 품질이 의심될 때 도구 계층과 검색 계층을 분리해 보기 위한 스크립트다.

.EXAMPLE
    .\scripts\search-probe.ps1
    .\scripts\search-probe.ps1 -Tool knowledge_graph -TopK 5

.NOTES
    이 파일 자체는 ASCII로 유지한다. 한글은 dataset/questions.json에서 읽는다.
#>
[CmdletBinding()]
param(
    [ValidateSet('vector_search', 'nl2sql', 'knowledge_graph')]
    [string] $Tool      = 'vector_search',
    [int]    $TopK      = 3,
    [string] $Model     = 'bge-m3',
    [string] $OllamaUrl = 'http://localhost:11434/api/embed',
    [string] $Container = 'reaone-postgres'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$qPath   = Join-Path $PSScriptRoot '..\dataset\questions.json'
$sqlFile = Join-Path $env:TEMP 'reaone-search-probe.sql'

$questions = (Get-Content $qPath -Raw -Encoding UTF8 | ConvertFrom-Json) |
             Where-Object { $_.tool -eq $Tool }

Write-Host ("{0} 질문 {1}개" -f $Tool, $questions.Count) -ForegroundColor Cyan

foreach ($item in $questions) {
    $body  = (@{ model = $Model; input = $item.q } | ConvertTo-Json -Compress)
    $bytes = [Text.Encoding]::UTF8.GetBytes($body)
    $r = Invoke-RestMethod -Uri $OllamaUrl -Method Post -Body $bytes -ContentType 'application/json'

    # InvariantCulture: 한국어 로캘에서도 소수점이 쉼표가 되지 않게 한다.
    $vec = '[' + (($r.embeddings[0] |
        ForEach-Object { $_.ToString([Globalization.CultureInfo]::InvariantCulture) }) -join ',') + ']'

    $sql = "SELECT doc_id, round((1-(embedding <=> '$vec'))::numeric,4), metadata->>'type', " +
           "left(metadata->>'title',40) FROM document_chunks ORDER BY embedding <=> '$vec' LIMIT $TopK;"
    Set-Content -Path $sqlFile -Value $sql -Encoding utf8

    Write-Host ''
    Write-Host ('Q: ' + $item.q) -ForegroundColor Green
    Write-Host ('   hint: ' + $item.hint) -ForegroundColor DarkGray
    Get-Content $sqlFile -Raw | docker exec -i $Container psql -U companyx -d companyx -tA -F '  |  '
}

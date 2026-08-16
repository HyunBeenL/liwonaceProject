<#
.SYNOPSIS
    실행 중인 MCP 서버에 붙어 tools/list를 찍고 vector_search를 호출한다.

.EXAMPLE
    .\scripts\mcp-call.ps1
    # dataset/questions.json의 vector_search 질문 중 하나로 호출

.EXAMPLE
    .\scripts\mcp-call.ps1 -Query "SSL 인증서 관련 장애가 있었어?" -Limit 3

.NOTES
    앱이 http://localhost:8080 에 떠 있어야 한다: ./mvnw spring-boot:run
    이 파일 자체는 ASCII로 유지한다. Windows PowerShell 5.1은 BOM 없는 .ps1을
    ANSI로 읽어서 한글 리터럴이 깨진다. 한글은 인자나 데이터 파일에서 받는다.
#>
[CmdletBinding()]
param(
    [string] $Query,
    [int]    $Limit   = 3,
    [string] $BaseUrl = 'http://localhost:8080/mcp'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$script:session = $null

function Send-Rpc([hashtable]$payload) {
    $json    = ($payload | ConvertTo-Json -Depth 10 -Compress)
    $bytes   = [Text.Encoding]::UTF8.GetBytes($json)
    $headers = @{ 'Accept' = 'application/json, text/event-stream' }
    if ($script:session) { $headers['Mcp-Session-Id'] = $script:session }

    # -UseBasicParsing: 없으면 PS 5.1이 IE 엔진을 초기화하려다 비대화형에서 실패한다.
    $resp = Invoke-WebRequest -Uri $BaseUrl -Method Post -Body $bytes -UseBasicParsing `
                              -ContentType 'application/json' -Headers $headers
    if (-not $script:session -and $resp.Headers['Mcp-Session-Id']) {
        $script:session = [string]$resp.Headers['Mcp-Session-Id']
    }
    # Streamable HTTP는 SSE로 답할 수 있다. "data: " 프레이밍을 벗겨낸다.
    $body = [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    if ($body -match '(?m)^data:\s*(.+)$') { $body = $Matches[1] }
    if ([string]::IsNullOrWhiteSpace($body)) { return $null }
    return ($body | ConvertFrom-Json)
}

# 질문을 안 주면 데이터셋에서 하나 꺼내 쓴다.
if (-not $Query) {
    $qPath = Join-Path $PSScriptRoot '..\dataset\questions.json'
    if (-not (Test-Path $qPath)) {
        throw "질문을 지정하거나 dataset/questions.json을 준비해야 한다: $qPath"
    }
    $Query = ((Get-Content $qPath -Raw -Encoding UTF8 | ConvertFrom-Json) |
              Where-Object { $_.tool -eq 'vector_search' })[1].q
}

# 1. 핸드셰이크
Send-Rpc @{ jsonrpc='2.0'; id=1; method='initialize'; params=@{
    protocolVersion='2025-06-18'; capabilities=@{}; clientInfo=@{ name='mcp-call'; version='1' } } } | Out-Null
Send-Rpc @{ jsonrpc='2.0'; method='notifications/initialized' } | Out-Null
Write-Host ("세션: " + $script:session) -ForegroundColor DarkGray

# 2. 등록된 도구 목록
$tools = Send-Rpc @{ jsonrpc='2.0'; id=2; method='tools/list'; params=@{} }
Write-Host ''
Write-Host '=== tools/list ===' -ForegroundColor Cyan
foreach ($t in $tools.result.tools) {
    Write-Host ("  " + $t.name) -ForegroundColor Green
    Write-Host ("    " + ($t.description -replace '\s+', ' ').Trim())
}

# 3. 실제 호출
Write-Host ''
Write-Host ("=== tools/call vector_search === " + $Query) -ForegroundColor Cyan
$call = Send-Rpc @{ jsonrpc='2.0'; id=3; method='tools/call'; params=@{
    name='vector_search'; arguments=@{ query=$Query; limit=$Limit } } }

if ($call.result.isError) {
    Write-Host '도구가 오류를 반환했다:' -ForegroundColor Red
    $call.result.content | ForEach-Object { Write-Host $_.text }
    exit 1
}

foreach ($c in $call.result.content) {
    foreach ($hit in ($c.text | ConvertFrom-Json)) {
        Write-Host ("  {0}  sim={1}  [{2}]  {3}" -f $hit.docId, $hit.similarity, $hit.type, $hit.title)
    }
}

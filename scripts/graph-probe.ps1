<#
.SYNOPSIS
    knowledge_graph 도구를 데이터셋의 예시 질문 10개로 검증한다.

.DESCRIPTION
    각 질문을 도구 파라미터로 손수 매핑해 호출한다. 이 매핑표가 곧 규칙 기반
    라우터가 나중에 자동으로 만들어야 할 결과이므로, 여기서 파라미터 표현력이
    충분한지 먼저 확인하는 셈이다.

    앱이 http://localhost:8080 에 떠 있어야 한다.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\graph-probe.ps1
#>
[CmdletBinding()]
param(
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

    $resp = Invoke-WebRequest -Uri $BaseUrl -Method Post -Body $bytes -UseBasicParsing `
                              -ContentType 'application/json' -Headers $headers
    if (-not $script:session -and $resp.Headers['Mcp-Session-Id']) {
        $script:session = [string]$resp.Headers['Mcp-Session-Id']
    }
    $body = [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    if ($body -match '(?m)^data:\s*(.+)$') { $body = $Matches[1] }
    if ([string]::IsNullOrWhiteSpace($body)) { return $null }
    return ($body | ConvertFrom-Json)
}

# 질문 -> 도구 파라미터 매핑. 라우터가 대신하게 될 부분이다.
$cases = @(
    @{ q = 'Client-A가 사용 중인 제품 목록은?';        args = @{ entity = 'Client-A';   relation = 'USES' } },
    @{ q = 'Product-C1을 사용하는 고객사는 어디야?';   args = @{ entity = 'Product-C1'; relation = 'USES' } },
    @{ q = '클라우드사업부 소속 직원들은 누구야?';     args = @{ entity = '클라우드사업부'; relation = 'BELONGS_TO' } },
    @{ q = '서울물산 담당 엔지니어는 누구야?';         args = @{ entity = '서울물산';   relation = 'MANAGES_ACCOUNT' } },
    @{ q = 'Product-D1 제품과 관련된 프로젝트는?';     args = @{ entity = 'Product-D1'; depth = 2; targetType = 'project' } },
    @{ q = '기술 지원 이슈가 가장 많은 제품은?';       args = @{ relation = 'REPORTED_ISSUE'; rank = $true } },
    @{ q = '경영지원팀 팀장은 누구야?';                args = @{ entity = '경영지원팀'; relation = 'HEAD_IS' } },
    @{ q = '진행 중인 프로젝트를 이끄는 직원 목록';    args = @{ relation = 'LEADS' } },
    @{ q = 'Product-S1 관련 고객 이슈 현황은?';        args = @{ entity = 'Product-S1'; relation = 'REPORTED_ISSUE' } },
    @{ q = '가장 많은 고객을 담당하는 직원은?';        args = @{ relation = 'MANAGES_ACCOUNT'; rank = $true } }
)

Send-Rpc @{ jsonrpc='2.0'; id=1; method='initialize'; params=@{
    protocolVersion='2025-06-18'; capabilities=@{}; clientInfo=@{ name='graph-probe'; version='1' } } } | Out-Null
Send-Rpc @{ jsonrpc='2.0'; method='notifications/initialized' } | Out-Null

$id = 10
foreach ($case in $cases) {
    $id++
    Write-Host ''
    Write-Host ('Q. ' + $case.q) -ForegroundColor Green
    Write-Host ('   args: ' + ($case.args | ConvertTo-Json -Compress)) -ForegroundColor DarkGray

    $call = Send-Rpc @{ jsonrpc='2.0'; id=$id; method='tools/call'; params=@{
        name='knowledge_graph'; arguments=$case.args } }

    if ($call.result.isError) {
        Write-Host '   도구 오류:' -ForegroundColor Red
        $call.result.content | ForEach-Object { Write-Host ('   ' + $_.text) }
        continue
    }

    foreach ($c in $call.result.content) {
        $r = $c.text | ConvertFrom-Json

        if ($r.mode -eq 'error') {
            Write-Host ('   [찾지 못함] ' + $r.error) -ForegroundColor Yellow
            continue
        }

        Write-Host ('   mode=' + $r.mode + ($(if ($r.start) { "  start=" + $r.start } else { '' }))) -ForegroundColor DarkGray

        if ($r.mode -eq 'ranking') {
            $r.ranking | Group-Object side | ForEach-Object {
                Write-Host ('   [' + $_.Name + ']')
                $_.Group | Select-Object -First 3 | ForEach-Object {
                    Write-Host ('     {0,-28} {1,-10} {2}건' -f $_.name, $_.type, $_.count)
                }
            }
        } else {
            $n = $r.neighbors
            Write-Host ('   결과 ' + $n.Count + '건')
            $n | Select-Object -First 4 | ForEach-Object {
                Write-Host ('     {0,-34} {1,-10} {2} {3}' -f $_.name, $_.type, $_.relation, $_.direction)
            }
            if ($n.Count -gt 4) { Write-Host ('     ... 외 ' + ($n.Count - 4) + '건') -ForegroundColor DarkGray }
        }
    }
}

<#
.SYNOPSIS
    Claude Desktop 설정에 이 프로젝트의 MCP 서버(companyx)를 등록한다.

.DESCRIPTION
    반드시 Claude Desktop이 완전히 종료된 상태에서, Desktop 바깥의 일반
    PowerShell 창에서 실행해야 한다.

    Claude Desktop은 claude_desktop_config.json을 자기가 관리한다. 실행 중에
    외부에서 이 파일을 고치면, 종료할 때 앱이 자기 메모리 상태를 다시 써서
    외부 편집분을 덮어써 버린다. 그래서 앱이 꺼진 상태에서만 안전하다.

.EXAMPLE
    # 1. Claude Desktop 완전 종료 (트레이 아이콘에서도 종료)
    # 2. 시작 메뉴 → PowerShell 실행 후:
    .\scripts\add-claude-connector.ps1
    # 3. Claude Desktop 실행

.PARAMETER Force
    Claude Desktop이 실행 중이어도 강행한다. 편집분이 유실될 수 있어 권장하지 않는다.
#>
[CmdletBinding()]
param(
    [string] $Name       = 'companyx',
    [string] $ServerUrl  = 'http://localhost:8080/mcp',
    [switch] $Force
)

$ErrorActionPreference = 'Stop'

$configPath = Join-Path $env:APPDATA 'Claude\claude_desktop_config.json'
if (-not (Test-Path $configPath)) {
    throw "Claude Desktop 설정 파일이 없다: $configPath"
}

# 실행 중이면 편집분이 종료 시 덮어써진다.
$running = Get-Process -Name 'claude' -ErrorAction SilentlyContinue
if ($running -and -not $Force) {
    Write-Host 'Claude Desktop이 실행 중이다.' -ForegroundColor Red
    Write-Host '완전히 종료한 뒤 다시 실행할 것. 창을 닫는 것만으로는 부족하고,'
    Write-Host '트레이 아이콘에서 종료하거나 다음을 실행한다:'
    Write-Host '    taskkill /IM claude.exe /F' -ForegroundColor Yellow
    exit 1
}

# 원본 백업. 이 파일에는 preferences 등 앱 상태가 함께 들어 있다.
$backup = "$configPath.bak-" + (Get-Date -Format 'yyyyMMdd-HHmmss')
Copy-Item $configPath $backup
Write-Host "백업: $backup" -ForegroundColor DarkGray

$json = Get-Content $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $json.mcpServers) {
    $json | Add-Member -NotePropertyName 'mcpServers' -NotePropertyValue ([pscustomobject]@{})
}

# 우리 서버는 HTTP(Streamable) 전송인데 Desktop 설정은 stdio 방식이라
# mcp-remote가 stdio <-> HTTP를 중계한다. --allow-http는 평문 localhost 허용용.
$entry = [pscustomobject]@{
    command = 'npx'
    args    = @('-y', 'mcp-remote', $ServerUrl, '--allow-http')
}

if ($json.mcpServers.PSObject.Properties.Name -contains $Name) {
    $json.mcpServers.$Name = $entry
    Write-Host "'$Name' 항목을 갱신했다." -ForegroundColor Yellow
} else {
    $json.mcpServers | Add-Member -NotePropertyName $Name -NotePropertyValue $entry
    Write-Host "'$Name' 항목을 추가했다." -ForegroundColor Green
}

# UTF-8 BOM 없이 저장한다. Desktop이 읽는 JSON에는 BOM이 없어야 안전하다.
$out = $json | ConvertTo-Json -Depth 100
[IO.File]::WriteAllText($configPath, $out, (New-Object Text.UTF8Encoding $false))

Write-Host ''
Write-Host '등록된 서버:' -ForegroundColor Cyan
($json.mcpServers.PSObject.Properties.Name) | ForEach-Object { Write-Host "  - $_" }
Write-Host ''
Write-Host "이제 Claude Desktop을 실행하면 '$Name'이 커넥터로 잡힌다." -ForegroundColor Green
Write-Host "서버가 떠 있어야 한다: ./mvnw spring-boot:run" -ForegroundColor DarkGray

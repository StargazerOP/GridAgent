param(
    [switch]$WithDocker
)

$ErrorActionPreference = "SilentlyContinue"
$Root = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $Root "logs"

function Stop-Port($Port, $Name) {
    $pids = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique

    if (-not $pids) {
        Write-Host "OK  $Name is not listening on $Port" -ForegroundColor Green
        return
    }

    foreach ($procId in $pids) {
        Stop-Process -Id $procId -Force
        Write-Host "OK  Stopped $Name pid=$procId on port $Port" -ForegroundColor Green
    }
}

Write-Host "Stopping local GridOpsAgent processes" -ForegroundColor Cyan
Stop-Port 9900 "Main app"
Stop-Port 9901 "MCP server"
Stop-Port 9910 "BGE-M3 embedding"

Remove-Item -Path (Join-Path $LogDir "*.pid") -Force -ErrorAction SilentlyContinue

if ($WithDocker) {
    Write-Host ""
    Write-Host "Stopping Docker infrastructure" -ForegroundColor Cyan

    docker stop mysql-gridops 2>$null | Out-Null
    docker stop redis-gridops 2>$null | Out-Null

    if (Test-Path (Join-Path $Root "vector-database.yml")) {
        Push-Location $Root
        docker compose -f vector-database.yml down
        if ($LASTEXITCODE -ne 0) {
            docker-compose -f vector-database.yml down
        }
        Pop-Location
    }
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green

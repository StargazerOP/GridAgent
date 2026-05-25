param(
    [switch]$SkipDocker,
    [switch]$SkipMilvus,
    [switch]$WithRedis,
    [switch]$NoBrowser,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $Root "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Write-Step($Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok($Message) {
    Write-Host "    OK  $Message" -ForegroundColor Green
}

function Write-Warn($Message) {
    Write-Host "    WARN $Message" -ForegroundColor Yellow
}

function Test-Port($Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync("127.0.0.1", $Port)
        if (-not $task.Wait(1000)) {
            return $false
        }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-Http($Url, $Name, $Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                Write-Ok "$Name is ready"
                return $true
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    Write-Warn "$Name did not become ready within $Seconds seconds"
    return $false
}

function Invoke-DockerCompose {
    param(
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]]$Arguments
    )

    $compose = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $compose) {
        throw "Docker is not installed or not in PATH."
    }

    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & docker compose version > $null 2>&1
    $composeExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($composeExitCode -eq 0) {
        & docker compose @Arguments
        return
    }

    $legacyCompose = Get-Command docker-compose -ErrorAction SilentlyContinue
    if (-not $legacyCompose) {
        throw "Neither 'docker compose' nor 'docker-compose' is available."
    }
    & docker-compose @Arguments
}

function Invoke-DockerComposeUpWithTimeout($Seconds) {
    $logFile = Join-Path $LogDir "docker-compose-milvus.log"
    $errFile = Join-Path $LogDir "docker-compose-milvus.err.log"
    $dockerArgs = @("compose", "-f", "vector-database.yml", "up", "-d")
    $proc = Start-Process docker -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $logFile `
        -RedirectStandardError $errFile `
        -ArgumentList $dockerArgs

    if ($proc.WaitForExit($Seconds * 1000)) {
        if ($proc.ExitCode -eq 0) {
            Write-Ok "Milvus stack startup command completed"
            return $true
        }
        Write-Warn "Milvus startup command exited with code $($proc.ExitCode). See logs\docker-compose-milvus.log and logs\docker-compose-milvus.err.log"
        return $false
    }

    try {
        $proc.Kill()
    } catch {
    }
    Write-Warn "Milvus startup did not finish within $Seconds seconds. Continuing with in-memory vector fallback. See logs\docker-compose-milvus.log and logs\docker-compose-milvus.err.log"
    return $false
}

function Ensure-DockerRunning() {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & docker info > $null 2>&1
    $dockerExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction

    if ($dockerExitCode -ne 0) {
        throw "Docker is not running. Please start Docker Desktop first."
    }
}

function Ensure-Container($Name, [scriptblock]$CreateCommand) {
    $exists = docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $Name }
    $running = docker ps --format "{{.Names}}" | Where-Object { $_ -eq $Name }

    if ($running) {
        Write-Ok "$Name is already running"
        return
    }

    if ($exists) {
        docker start $Name | Out-Null
        Write-Ok "$Name started"
        return
    }

    & $CreateCommand
    Write-Ok "$Name created and started"
}

function Start-BackgroundProcess($Name, $Command, $LogFile, $PidFile) {
    $fullLog = Join-Path $LogDir $LogFile
    $fullPid = Join-Path $LogDir $PidFile
    $escapedRoot = $Root -replace "'", "''"
    $escapedLog = $fullLog -replace "'", "''"
    $cmd = "Set-Location -LiteralPath '$escapedRoot'; $Command *> '$escapedLog'"
    $proc = Start-Process powershell -WindowStyle Hidden -PassThru -ArgumentList @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-Command", $cmd
    )
    Set-Content -Path $fullPid -Value $proc.Id -Encoding ASCII
    Write-Ok "$Name starting, pid=$($proc.Id), log=logs\$LogFile"
}

function Stop-Port($Port, $Name) {
    $pids = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique

    foreach ($procId in $pids) {
        Stop-Process -Id $procId -Force
        Write-Ok "Stopped stale $Name pid=$procId on port $Port"
    }
}

function Test-MainAppUsesDisabledMilvus() {
    if (-not (Test-Port 9900)) {
        return $false
    }

    try {
        $response = Invoke-WebRequest -Uri "http://localhost:9900/milvus/health" -UseBasicParsing -TimeoutSec 5
        return $response.Content -like "*Milvus client is disabled*"
    } catch {
        return $false
    }
}

Write-Host "GridOpsAgent one-command startup" -ForegroundColor Green
Write-Host "Project: $Root"

if (-not $env:DEEPSEEK_API_KEY) {
    Write-Warn "DEEPSEEK_API_KEY is empty. The page can start, but LLM chat/diagnosis calls will fail until it is set."
}

if (-not $SkipDocker) {
    Write-Step "Starting infrastructure"
    Ensure-DockerRunning

    Ensure-Container "mysql-gridops" {
        docker run -d --name mysql-gridops `
            -p 3307:3306 `
            -e MYSQL_ROOT_PASSWORD=root `
            -e MYSQL_DATABASE=power_aiops `
            mysql:8.0 `
            --character-set-server=utf8mb4 `
            --collation-server=utf8mb4_unicode_ci | Out-Null
    }

    if ($WithRedis) {
        Ensure-Container "redis-gridops" {
            docker run -d --name redis-gridops -p 6379:6379 redis:7-alpine | Out-Null
        }
    } else {
        Write-Warn "Redis is skipped by default; current core flows do not directly require it. Use -WithRedis if needed."
    }

    if (-not $SkipMilvus) {
        if (Test-Port 19530) {
            Write-Ok "Milvus port 19530 is already listening"
        } else {
            Invoke-DockerComposeUpWithTimeout 45 | Out-Null
        }
    } else {
        Write-Warn "Milvus skipped. RAG will use the in-memory fallback if Milvus is unavailable."
    }
}

Write-Step "Starting local BGE-M3 embedding service"
if (Test-Port 9910) {
    Write-Ok "BGE-M3 embedding service is already listening on 9910"
} else {
    Start-BackgroundProcess "BGE-M3" "python scripts\bge_m3_embedding_server.py" "bge-m3-embedding.log" "bge-m3-embedding.pid"
}
Wait-Http "http://127.0.0.1:9910/health" "BGE-M3" 60 | Out-Null

Write-Step "Starting MCP tool server"
if (Test-Port 9901) {
    Write-Ok "MCP server is already listening on 9901"
} else {
    Start-BackgroundProcess "MCP server" "mvn -pl power-tools-mcp-server spring-boot:run" "mcp-server.log" "mcp-server.pid"
}

Write-Step "Starting GridOpsAgent main app"
if ((Test-Port 9900) -and (Test-Port 19530) -and (Test-MainAppUsesDisabledMilvus)) {
    Write-Warn "Main app is running with Milvus client disabled while Milvus is now available. Restarting main app..."
    Stop-Port 9900 "Main app"
    Start-Sleep -Seconds 3
}

if (Test-Port 9900) {
    Write-Ok "Main app is already listening on 9900"
} else {
    Start-BackgroundProcess "Main app" "mvn -pl grid-ops-agent-app spring-boot:run" "server.log" "server.pid"
}

Write-Step "Waiting for application health"
Wait-Http "http://localhost:9900/actuator/health" "GridOpsAgent main app" $TimeoutSeconds | Out-Null

Write-Step "Current status"
& (Join-Path $PSScriptRoot "status-gridops.ps1")

if (-not $NoBrowser) {
    Start-Process "http://localhost:9900/"
}

Write-Host ""
Write-Host "Done. Open http://localhost:9900/" -ForegroundColor Green
Write-Host "Logs: $LogDir"

$ErrorActionPreference = "SilentlyContinue"

function Test-TcpPort($Port) {
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

function Get-PortStatus($Name, $Port, $Url) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    $listening = Test-TcpPort $Port
    $http = "not checked"

    if ($Url) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            $http = "HTTP $($response.StatusCode)"
        } catch {
            $http = "HTTP unavailable"
        }
    }

    [PSCustomObject]@{
        Service = $Name
        Port = $Port
        Listening = $listening
        Pid = if ($listening) { $conn.OwningProcess } else { "" }
        Health = $http
    }
}

$rows = @(
    Get-PortStatus "Main app" 9900 "http://localhost:9900/actuator/health"
    Get-PortStatus "MCP server" 9901 $null
    Get-PortStatus "BGE-M3 embedding" 9910 "http://127.0.0.1:9910/health"
    Get-PortStatus "MySQL" 3307 $null
    Get-PortStatus "Redis" 6379 $null
    Get-PortStatus "Milvus" 19530 $null
    Get-PortStatus "Attu" 8001 $null
)

$rows | Format-Table -AutoSize

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($docker) {
    Write-Host ""
    Write-Host "Docker containers:" -ForegroundColor Cyan
    docker ps -a --filter "name=mysql-gridops" --filter "name=redis-gridops" --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
}

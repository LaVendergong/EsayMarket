# 停止 mall 应用：1) 显式 PID 2) logs\<name>.pid 3) 命令行匹配 4) 端口匹配 5) 启动日志里的 PID 兜底
# 说明：Windows 上 start /b 起的进程 WMI CommandLine 可能为空；某些受限环境里
#       Get-CimInstance 会被策略拒绝、Get-NetTCPConnection 也拿不到监听项，
#       此时用启动日志里的「with PID xxxx」兜底（会校验进程名必须是 java/javaw）。
[CmdletBinding()]
param(
    # 显式指定要停的 PID（可选，优先级最高）
    [int[]]$ProcessId
)

$base = Split-Path -Parent $PSScriptRoot
$stopped = New-Object System.Collections.Generic.List[int]

function Stop-Mall([int]$id, [string]$why) {
    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    if (-not $proc) { return }
    Write-Output ("stopping pid $id ($why, " + $proc.ProcessName + ")")
    Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
    $stopped.Add($id)
}

# 1) 显式 PID
foreach ($id in $ProcessId) { Stop-Mall $id 'explicit' }

# 2) PID 文件（scripts\p2-verify.ps1 / run-with-pid 启动时写入 logs\<name>.pid）
foreach ($name in 'admin', 'portal') {
    $pidFile = Join-Path $base "logs\$name.pid"
    if (Test-Path $pidFile) {
        $id = 0
        if ([int]::TryParse((Get-Content $pidFile -Raw).Trim(), [ref]$id)) { Stop-Mall $id "logs\$name.pid" }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }
}

# 3) 命令行匹配
Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -match 'mall-(admin|portal)-1\.0\.0\.jar' } |
    ForEach-Object { Stop-Mall $_.ProcessId 'command line' }

# 4) 端口匹配
foreach ($p in 8090, 8081) {
    $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($c) {
        foreach ($procId in ($c.OwningProcess | Select-Object -Unique)) { Stop-Mall $procId "port $p" }
    } else {
        Write-Output ("port $p : no listener detected")
    }
}

# 5) 日志 PID 兜底
foreach ($name in 'admin', 'portal') {
    $log = Join-Path $base "logs\$name.log"
    if (-not (Test-Path $log)) { continue }
    $hits = Select-String -Path $log -Pattern 'with PID (\d+)' -AllMatches -ErrorAction SilentlyContinue
    if (-not $hits) { continue }
    $id = [int]$hits[-1].Matches[0].Groups[1].Value
    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($proc -and $proc.ProcessName -match '^(java|javaw)$') {
        Stop-Mall $id "logs\$name.log"
    }
}

Start-Sleep -Seconds 3
Write-Output ('stop complete, stopped pids: ' + (($stopped | Select-Object -Unique) -join ', '))

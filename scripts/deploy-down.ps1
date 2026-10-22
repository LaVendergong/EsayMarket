# ============================================================================
# 停掉本地 Docker 部署栈并释放内存
#
# 为什么还要 `wsl --shutdown`：容器停了但 WSL 虚拟机还占着几百 MB~1GB 不放。
# 在这台 16GB 的机器上，这点内存往往就是"IDEA 开始卡"的分界线。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\deploy-down.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\deploy-down.ps1 -Purge   # 连数据卷一起删（下次启动会重跑 initdb）
# ============================================================================
param(
    [switch]$Purge
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Get-FreeMb {
    $os = Get-CimInstance Win32_OperatingSystem
    return [int]($os.FreePhysicalMemory / 1KB)
}

$before = Get-FreeMb
Write-Host ("== 停止部署栈（当前可用 {0:N0} MB）==" -f $before) -ForegroundColor Cyan

$downArgs = if ($Purge) { 'down -v' } else { 'down' }
$body = @"
cd /root/mallbuild 2>/dev/null || { echo '  /root/mallbuild 不存在，跳过'; exit 0; }
docker compose -p malltest -f docker-compose.localtest.yml $downArgs 2>&1 | tail -4
"@

$path = Join-Path $root 'tmp\deploy-down.sh'
New-Item -ItemType Directory -Force -Path (Split-Path $path) | Out-Null
[System.IO.File]::WriteAllText($path, ($body -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))
& wsl -u root -e bash /mnt/d/projects/mall/tmp/deploy-down.sh 2>&1 |
    Where-Object { $_ -notmatch '检测到|妫€娴' } | ForEach-Object { '  ' + $_ }

Write-Host '== 关闭 WSL 虚拟机（把内存还回来）==' -ForegroundColor Cyan
& wsl --shutdown
Start-Sleep -Seconds 6

$after = Get-FreeMb
Write-Host ("== 完成：可用内存 {0:N0} MB（释放 {1:N0} MB）==" -f $after, ($after - $before)) -ForegroundColor Green
if ($Purge) {
    Write-Host '  数据卷已删除：下次启动会重新执行 V1..V13 迁移（initdb）' -ForegroundColor Yellow
} else {
    Write-Host '  数据卷保留：下次启动直接复用现有数据'
}
Write-Host '  想用本机开发模式（更省内存）： run.bat'

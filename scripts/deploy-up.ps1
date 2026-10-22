# ============================================================================
# 一键起本地 Docker 部署栈（5 个容器），并确认 Windows 侧真的能打开页面
#
# 为什么需要这个脚本：这套栈要吃掉约 3.5~4GB 内存。在 16GB 且开着 IDEA 的机器上，
# 内存不足会让 WSL 虚拟机被反复重启 —— 现象是**容器明明是 Up，浏览器却打不开**
# （WSL 内部的 localhost 是通的，Windows 侧的端口转发断了）。
# 这个脚本把「起栈 -> 等健康 -> 检查转发 -> 转发断了就自愈」串起来。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\deploy-up.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\deploy-up.ps1 -Build     # 代码改了要重建镜像
#   powershell -ExecutionPolicy Bypass -File scripts\deploy-up.ps1 -Force     # 跳过内存检查
# ============================================================================
param(
    [switch]$Build,
    [switch]$Force,
    [int]$MinFreeMb = 3000
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$composeArgs = '-p malltest -f docker-compose.localtest.yml'

function Get-FreeMb {
    $os = Get-CimInstance Win32_OperatingSystem
    return [int]($os.FreePhysicalMemory / 1KB)
}

function Test-PortOpen([int]$port) {
    return (Test-NetConnection -ComputerName localhost -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue)
}

function Wait-Healthy([int]$timeoutSec = 180) {
    # 必须**在 WSL 内部**探活：Windows 的 localhost 转发可能正好是坏的，
    # 从外面等会一直等不到（第一版就是这么误判成"后端没起来"的）。
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $out = & wsl -u root -e bash -c "curl -s -m 3 http://127.0.0.1:18090/actuator/health" 2>$null
        if ($out -match '"status"\s*:\s*"UP"') { return $true }
        Start-Sleep -Seconds 4
    }
    return $false
}

function Invoke-WslScript([string]$name, [string]$body) {
    $path = Join-Path $root "tmp\$name"
    New-Item -ItemType Directory -Force -Path (Split-Path $path) | Out-Null
    [System.IO.File]::WriteAllText($path, ($body -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))
    $unix = '/mnt/' + $path.Substring(0, 1).ToLower() + $path.Substring(2).Replace('\', '/')
    & wsl -u root -e bash $unix 2>&1 | Where-Object { $_ -notmatch '检测到|妫€娴|localhost' }
}

Write-Host '== 1) 内存检查 ==' -ForegroundColor Cyan
# 栈已经在跑时可用内存天然就低（vmmemWSL 占着），这时候不该拦 —— 后面只做健康检查与转发自愈。
$runningRaw = & wsl -u root -e bash -c "cd /root/mallbuild 2>/dev/null && docker compose $composeArgs ps -q 2>/dev/null | wc -l" 2>$null
$running = 0
if ("$runningRaw" -match '(\d+)') { $running = [int]$Matches[1] }

if ($running -ge 5) {
    Write-Host ("  栈已在运行（{0} 个容器），跳过内存检查与启动" -f $running) -ForegroundColor Green
} else {
    # 容器没在跑时，WSL 虚拟机本身还可能占着 2~3GB（vmmemWSL 不会自己退）。
    # 先关掉它，才能看到"真实可用内存"，否则会被自己的空占误判成内存不足。
    & wsl --shutdown 2>&1 | Out-Null
    Start-Sleep -Seconds 6

    $free = Get-FreeMb
    Write-Host ("  可用内存 {0:N0} MB（建议 >= {1:N0} MB）" -f $free, $MinFreeMb)
    if ($free -lt $MinFreeMb -and -not $Force) {
        Write-Host '  内存不足，继续起栈很可能导致 WSL 虚拟机被反复重启（表现为容器 Up 但页面打不开）。' -ForegroundColor Yellow
        Write-Host '  占用最高的进程：' -ForegroundColor Yellow
        Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 5 |
            ForEach-Object { '    {0,-20} {1,7:N0} MB' -f $_.ProcessName, ($_.WorkingSet64 / 1MB) }
        Write-Host '  可以先关掉一些程序，或者加 -Force 强行启动。' -ForegroundColor Yellow
        exit 1
    }

    Write-Host '== 2) 同步代码到 WSL（构建上下文在原生目录更快更稳）==' -ForegroundColor Cyan
    Invoke-WslScript 'deploy-sync.sh' @'
set -e
mkdir -p /root/mallbuild
tar -C /mnt/d/projects/mall --exclude=node_modules --exclude=target --exclude=.git \
    --exclude=logs --exclude=tmp --exclude=dist -cf - . | tar -C /root/mallbuild -xf -
echo "  synced, sql files: $(ls /root/mallbuild/sql/V*.sql | wc -l)"
'@

    Write-Host '== 2.5) 生成自签证书（前端容器用 https）==' -ForegroundColor Cyan
    # 幂等：已有证书就跳过。真实证书上线时替换 deploy/certs 即可。
    Invoke-WslScript 'deploy-certs.sh' "bash /mnt/d/projects/mall/scripts/gen-certs.sh"

    Write-Host '== 3) 启动容器 ==' -ForegroundColor Cyan
    $upArgs = if ($Build) { 'up -d --build' } else { 'up -d' }
    $out = & wsl -u root -e bash -c "cd /root/mallbuild && docker compose $composeArgs $upArgs 2>&1 | tail -6"
    $out | Where-Object { $_ -notmatch '检测到|妫€娴' } | ForEach-Object { '  ' + $_ }
}

Write-Host '== 4) 等后端健康 ==' -ForegroundColor Cyan
if (-not (Wait-Healthy)) {
    Write-Host '  后端 180 秒内没起来，看日志：' -ForegroundColor Red
    & wsl -u root -e bash -c "docker logs --tail 30 malltest-mall-portal-1 2>&1" |
        Where-Object { $_ -notmatch '检测到|妫€娴' } | Select-Object -Last 12 | ForEach-Object { '    ' + $_ }
    exit 1
}
Write-Host '  portal/admin 健康' -ForegroundColor Green

Write-Host '== 5) 检查 Windows 侧的页面端口 ==' -ForegroundColor Cyan
# 前端现在是 **https**（自签证书），所以检查必须用 https + -k；
# 用 http 去测会得到 nginx 的 400「plain HTTP to HTTPS port」，被误判成转发断了。
$httpOk = {
    param($url)
    try {
        $code = (curl.exe -s -k -o NUL -w '%{http_code}' -m 8 $url 2>$null)
        return $code -eq '200'
    } catch { return $false }
}

if ((& $httpOk 'https://localhost:15174/') -and (& $httpOk 'https://localhost:15173/')) {
    Write-Host '  端口转发正常（https）' -ForegroundColor Green
} else {
    # 这是本机真实踩过的坑：容器在跑，但 WSL 重启后 localhost 转发没接上
    Write-Host '  页面端口不通（容器在跑，但 Windows -> WSL 转发断了），重启 WSL 自愈…' -ForegroundColor Yellow
    & wsl --shutdown
    Start-Sleep -Seconds 8
    & wsl -u root -e bash -c "cd /root/mallbuild && docker compose $composeArgs up -d 2>&1 | tail -3" |
        Where-Object { $_ -notmatch '检测到|妫€娴' } | ForEach-Object { '  ' + $_ }
    $null = Wait-Healthy
    if ((& $httpOk 'https://localhost:15174/') -and (& $httpOk 'https://localhost:15173/')) {
        Write-Host '  转发已恢复' -ForegroundColor Green
    } else {
        Write-Host '  仍未恢复。可以在浏览器里直接用 WSL 的 IP：' -ForegroundColor Red
        $wslIp = ((& wsl -u root -e hostname -I) -split '\s+' | Where-Object { $_ -match '^\d' } | Select-Object -First 1)
        Write-Host ("    https://{0}:15174   https://{0}:15173" -f $wslIp) -ForegroundColor Red
        exit 1
    }
}

Write-Host ''
Write-Host '== 就绪 ==' -ForegroundColor Cyan
Write-Host '  H5 商城   http://localhost:15174'
Write-Host '  管理后台  http://localhost:15173   （malladmin / admin123456）'
Write-Host '  停止并释放内存： powershell -File scripts\deploy-down.ps1'

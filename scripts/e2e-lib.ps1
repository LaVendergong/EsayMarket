# ============================================================================
# mall E2E 验证公用函数库 —— 由 p2-verify.ps1 / p3-verify.ps1 dot-source
#
# 调用方需要先定义这些变量（在 dot-source 之前）：
#   $root $portal $admin $mysql $java $outDir $reqFile $MysqlPassword
#   并初始化 $script:results = New-Object System.Collections.Generic.List[object]
# 依赖：curl.exe（Win10+ 自带）、mysql.exe、scripts\stop-apps.ps1
# 注意：本文件含中文，必须保持 UTF-8 **带 BOM**（PowerShell 5.1 否则按 GBK 解析会报错）
# ============================================================================

function Read-Text([string]$path) {
    if (-not (Test-Path $path)) { return '' }
    try {
        $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try {
            $ms = New-Object System.IO.MemoryStream
            $fs.CopyTo($ms)
            return [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
        } finally { $fs.Dispose() }
    } catch { return '' }
}

function Write-Text([string]$path, [string]$text) {
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

# 统一走 curl 落盘：-o 响应体（UTF-8 字节原样保存），stdout 只回 http 状态码
function Http-Json {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Url,
        $Body,
        [string]$Token,
        [Parameter(Mandatory)][string]$Name
    )
    $cargs = @('-s', '-S', '-X', $Method, $Url, '-H', 'Content-Type: application/json; charset=utf-8', '-w', '%{http_code}')
    if ($Token) { $cargs += @('-H', "Authorization: Bearer $Token") }
    if ($null -ne $Body) {
        Write-Text $reqFile ($Body | ConvertTo-Json -Depth 12 -Compress)
        # 注意：写成 @('--data-binary', '@' + $reqFile) 会被拆成 3 个参数，curl 直接失败
        $bodyArg = '@' + $reqFile
        $cargs += @('--data-binary', $bodyArg)
    }
    $respFile = Join-Path $outDir ($Name + '.json')
    Remove-Item $respFile -Force -ErrorAction SilentlyContinue
    $cargs += @('-o', $respFile)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $code = (& curl.exe @cargs 2>$null | Out-String).Trim() } finally { $ErrorActionPreference = $prev }
    $text = Read-Text $respFile
    $parsed = $null
    if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { } }
    return [pscustomobject]@{ http = [int]$code; body = $parsed; raw = $text; file = $respFile }
}

function Check([string]$name, $ok, [string]$detail) {
    $ok = [bool]$ok
    $script:results.Add([pscustomobject]@{ name = $name; ok = $ok; detail = $detail })
    $tag = 'FAIL'; if ($ok) { $tag = 'PASS' }
    Write-Host ("[{0}] {1} :: {2}" -f $tag, $name, $detail)
}

function Code($resp) { if ($resp -and $resp.body) { return $resp.body.code } else { return $null } }
function Payload($resp) { if ($resp -and $resp.body) { return $resp.body.data } else { return $null } }
function Items($resp) {
    if (-not $resp.body -or -not $resp.body.data) { return @() }
    return @($resp.body.data.list)
}

function Wait-Log([string]$logPath, [string]$marker, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ((Read-Text $logPath) -match $marker) { return $true }
        Start-Sleep -Milliseconds 1000
    }
    return $false
}

function Invoke-Mysql([string]$sql) {
    # mysql.exe 的「Using a password ...」警告走 stderr，在 EAP=Stop 下会被当 NativeCommandError 中断脚本
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $mysql -uroot "-p$MysqlPassword" -N --default-character-set=utf8mb4 -e $sql 2>$null
        return ($out | Out-String)
    } finally { $ErrorActionPreference = $prev }
}

# 直接用 Start-Process 起 java 并把 stdout/stderr 落到文件：
# 不走 run.bat 的 start /b（子进程会继承本脚本的 stdout 管道，父 shell 等不到 EOF 会被超时杀掉），
# 同时写 logs\<name>.pid 供 stop-apps.ps1 精确停进程。
function Start-MallApp([string]$name, [string]$jar, [string]$log) {
    $errLog = $log -replace '\.log$', '.err.log'
    $p = Start-Process -FilePath $java -ArgumentList @('-jar', $jar) `
        -RedirectStandardOutput $log -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
    Write-Text (Join-Path $root "logs\$name.pid") ([string]$p.Id)
    Write-Host ("   started $name pid=" + $p.Id)
    return $p.Id
}

function Stop-MallApps {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\stop-apps.ps1') 2>&1 |
            ForEach-Object { Write-Host "   $_" }
    } finally { $ErrorActionPreference = $prev }
}

function Start-MallApps([string]$logPrefix) {
    Remove-Item (Join-Path $root 'logs\*.log') -Force -ErrorAction SilentlyContinue
    Start-MallApp 'admin'  (Join-Path $root 'backend\mall-admin\target\mall-admin-1.0.0.jar')   (Join-Path $root 'logs\admin.log')  | Out-Null
    Start-MallApp 'portal' (Join-Path $root 'backend\mall-portal\target\mall-portal-1.0.0.jar') (Join-Path $root 'logs\portal.log') | Out-Null
    $okAdmin  = Wait-Log (Join-Path $root 'logs\admin.log')  'Started MallAdminApplication'  120
    $okPortal = Wait-Log (Join-Path $root 'logs\portal.log') 'Started MallPortalApplication' 120
    Check "$logPrefix mall-admin 启动"  $okAdmin  'logs\admin.log'
    Check "$logPrefix mall-portal 启动" $okPortal 'logs\portal.log'
    if (-not ($okAdmin -and $okPortal)) {
        $tail = Read-Text (Join-Path $root 'logs\admin.log')
        Write-Host $tail.Substring(0, [Math]::Min(1500, $tail.Length))
        return $false
    }
    return $true
}

# 汇总并写 tmp\<阶段>\summary.txt，返回失败条数
function Save-Summary([string]$title) {
    $fail = @($script:results | Where-Object { -not $_.ok })
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add($title + ' ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
    $lines.Add("总计 $($script:results.Count) 项，通过 $($script:results.Count - $fail.Count) 项，失败 $($fail.Count) 项")
    $lines.Add('')
    foreach ($r in $script:results) {
        $tag = 'FAIL'; if ($r.ok) { $tag = 'PASS' }
        $lines.Add("[$tag] $($r.name) :: $($r.detail)")
    }
    $text = ($lines -join "`r`n")
    Write-Text (Join-Path $outDir 'summary.txt') $text
    Write-Host ''
    Write-Host $text
    return $fail.Count
}

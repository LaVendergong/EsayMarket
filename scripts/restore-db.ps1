# ============================================================================
# Database restore from a logical dump produced by scripts\backup-db.ps1.
#
# The dump contains no CREATE DATABASE / USE, so it can be replayed into any
# target name. That is what makes the rehearsal safe: we restore into
# mall_restore_test first, compare, and only then (if you really mean it) restore
# over the live database.
#
# Safety rules baked in:
#   - the target database is DROPPED and recreated, so restoring over 'mall'
#     requires -Force (a typo should not wipe production)
#   - refuses to run if the dump file does not look like a dump
#   - prints per-table row counts afterwards, so "restored" is not just a word
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore-db.ps1 -File tmp\backup\mall-20260101-030000.sql
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore-db.ps1 -File ... -TargetDatabase mall -Force
# ============================================================================
param(
    [Parameter(Mandatory = $true)][string]$File,
    [string]$MysqlPassword = '521131',
    [string]$TargetDatabase = 'mall_restore',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path $File)) { throw "dump file not found: $File" }
$File = (Resolve-Path $File).Path

if ($TargetDatabase -eq 'mall' -and -not $Force) {
    throw "Refusing to overwrite the live database 'mall'. Re-run with -Force if that is really what you want."
}
if ($TargetDatabase -notmatch '^[A-Za-z0-9_]+$') {
    throw "invalid target database name: $TargetDatabase"
}

function Find-MysqlTool([string]$name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($p in @(
            "C:\Program Files\MySQL\MySQL Server 8.0\bin\$name.exe",
            "C:\Program Files\MySQL\MySQL Server 8.4\bin\$name.exe"
        )) {
        if (Test-Path $p) { return $p }
    }
    throw "$name.exe not found. Install MySQL client or add it to PATH."
}

$mysqlExe = Find-MysqlTool 'mysql'

# 先确认这文件确实像一份 dump，别把空文件或日志文件灌进库里
$probe = Get-Content $File -TotalCount 400 -Encoding UTF8
if (($probe -join "`n") -notmatch 'CREATE TABLE') {
    throw "$File does not look like a mysqldump output (no CREATE TABLE found in the first 400 lines)"
}

$sizeKb = [math]::Round((Get-Item $File).Length / 1KB, 1)
Write-Host "restoring $File ($sizeKb KB) into database '$TargetDatabase'"

# mysql 的「Using a password ...」警告走 stderr，EAP=Stop 下会被当致命错误中断脚本
$prev = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & $mysqlExe -uroot "-p$MysqlPassword" --default-character-set=utf8mb4 -e `
        "DROP DATABASE IF EXISTS ``$TargetDatabase``; CREATE DATABASE ``$TargetDatabase`` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>&1 |
        Where-Object { $_ -notmatch 'Using a password on the command line' } | ForEach-Object { Write-Host "  $_" }
} finally { $ErrorActionPreference = $prev }
if ($LASTEXITCODE -ne 0) { throw 'failed to recreate the target database' }

# 用 mysql 自己的 `source` 命令导入文件，**不要**走 PowerShell 管道或 cmd 的 `<` 重定向：
#   - 管道：PowerShell 会把文本按 UTF-16/BOM 处理，中文和转义容易被打坏
#   - cmd "<"：引号在 PowerShell → cmd 的传递过程中会被吃掉（实测报 "/c 不是内部或外部命令"）
$sourcePath = $File -replace '\\', '/'
$prev = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $out = & $mysqlExe -uroot "-p$MysqlPassword" --default-character-set=utf8mb4 $TargetDatabase -e "source $sourcePath" 2>&1 |
        Where-Object { $_ -notmatch 'Using a password on the command line' }
} finally { $ErrorActionPreference = $prev }
if ($LASTEXITCODE -ne 0) {
    $out | ForEach-Object { Write-Host "  $_" }
    throw "mysql import failed with exit code $LASTEXITCODE"
}

function Query([string]$q) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $mysqlExe -uroot "-p$MysqlPassword" -N --default-character-set=utf8mb4 -e $q 2>$null
        return ($out | Out-String).Trim()
    } finally { $ErrorActionPreference = $prev }
}

$tables = Query "SELECT table_name FROM information_schema.tables WHERE table_schema = '$TargetDatabase' ORDER BY table_name;"
$rows = @()
foreach ($t in ($tables -split "`r?`n" | Where-Object { $_ })) {
    $n = Query "SELECT COUNT(*) FROM ``$TargetDatabase``.``$t``;"
    $rows += "{0}={1}" -f $t, $n
}
Write-Host ("restored {0} tables: {1}" -f @($tables).Count, ($rows -join ' '))

Write-Output $TargetDatabase

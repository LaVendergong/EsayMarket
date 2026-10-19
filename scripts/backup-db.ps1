# ============================================================================
# Database backup (logical dump).
#
# Why: there is no backup at all today. A bad migration / accidental DELETE is
# unrecoverable, which is the one failure mode you cannot fix after the fact.
#
# Design notes:
#   - --single-transaction : consistent snapshot of InnoDB without locking writers
#                            (the shop keeps taking orders while we dump)
#   - --routines --triggers --events : schema objects people forget until restore day
#   - --set-gtid-purged=OFF : otherwise the dump carries GTID statements that fail
#                             to import on a server without GTID enabled
#   - no --databases : the dump then contains NO "CREATE DATABASE"/"USE", so it can be
#                      restored into any target database name (that is what makes the
#                      restore rehearsal possible)
#
# Prints the absolute path of the created dump on stdout.
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup-db.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup-db.ps1 -Database mall -KeepLast 3
# ============================================================================
param(
    [string]$MysqlPassword = '521131',
    [string]$Database = 'mall',
    [string]$OutDir,
    [int]$KeepLast = 7
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $root 'tmp\backup' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

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

$dumpExe = Find-MysqlTool 'mysqldump'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$target = Join-Path $OutDir "$Database-$stamp.sql"

$args = @(
    "-uroot", "-p$MysqlPassword",
    '--single-transaction', '--routines', '--triggers', '--events',
    '--set-gtid-purged=OFF',
    '--default-character-set=utf8mb4',
    '--hex-blob',
    '--result-file', $target,
    $Database
)

Write-Host "dumping '$Database' -> $target"
# mysqldump 的「Using a password ...」警告走 stderr，EAP=Stop 下会被当致命错误中断脚本
$prev = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & $dumpExe @args 2>&1 |
        Where-Object { $_ -notmatch 'Using a password on the command line' } |
        ForEach-Object { Write-Host "  $_" }
} finally { $ErrorActionPreference = $prev }
if ($LASTEXITCODE -ne 0) { throw "mysqldump failed with exit code $LASTEXITCODE" }

if (-not (Test-Path $target)) { throw 'mysqldump reported success but no file was written' }
$info = Get-Item $target
if ($info.Length -lt 1024) { throw "dump is suspiciously small ($($info.Length) bytes)" }

# 一份「看起来成功但其实是空库」的备份比没有备份更危险，所以至少要看到建表语句和数据
$head = Get-Content $target -TotalCount 400 -Encoding UTF8
$hasSchema = ($head -join "`n") -match 'CREATE TABLE'
if (-not $hasSchema) { throw 'dump does not contain any CREATE TABLE statement' }

$hash = (Get-FileHash $target -Algorithm SHA256).Hash
Write-Host ("done: {0:N1} KB, sha256={1}" -f ($info.Length / 1KB), $hash.Substring(0, 16))

# 只保留最近 N 份，避免备份目录自己把磁盘吃满
$old = Get-ChildItem $OutDir -File -Filter "$Database-*.sql" | Sort-Object LastWriteTime -Descending | Select-Object -Skip $KeepLast
foreach ($f in $old) {
    Remove-Item $f.FullName -Force -ErrorAction SilentlyContinue
    Write-Host "pruned old backup: $($f.Name)"
}

Write-Output $target

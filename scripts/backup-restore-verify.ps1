# ============================================================================
# Backup / restore rehearsal.
#
# A backup you have never restored is not a backup. This script proves the whole
# loop for real, on the live dev database:
#
#   1. snapshot row counts of the live database
#   2. take a dump (scripts\backup-db.ps1)
#   3. restore that dump into a scratch database (scripts\restore-db.ps1)
#   4. compare per-table row counts, and a few spot values, live vs restored
#   5. drop the scratch database
#   6. assert the live database was not touched by any of it
#
# Costs nothing and is the only evidence that "we can restore" is true.
# ASCII-only on purpose (see docs/AI-HANDOFF.md section 8, item 28).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\backup-restore-verify.ps1
# ============================================================================
param(
    [string]$MysqlPassword = '521131',
    [string]$Source = 'mall',
    [string]$Scratch = 'mall_restore_test'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
# e2e-lib 依赖调用方作用域里的这些变量，缺一个就会报「& 后面的表达式无效」之类的怪错
$portal = 'http://localhost:8090'
$admin = 'http://localhost:8081'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$java = 'D:\jdks\jdk-17.0.12\bin\java.exe'
$outDir = Join-Path $root 'tmp\backup'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$script:results = New-Object System.Collections.Generic.List[object]
$reqFile = Join-Path $outDir '_request.json'
. (Join-Path $PSScriptRoot 'e2e-lib.ps1')

function Sql([string]$q) {
    # mysql.exe 的「Using a password ...」警告走 stderr，EAP=Stop 下会被当致命错误中断脚本
    # （e2e-lib 的 Invoke-Mysql 也是这么处理的）
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $mysql -uroot "-p$MysqlPassword" -N --default-character-set=utf8mb4 -e $q 2>$null
        return ($out | Out-String).Trim()
    } finally { $ErrorActionPreference = $prev }
}

function Tables([string]$db) {
    $t = Sql "SELECT table_name FROM information_schema.tables WHERE table_schema = '$db' AND table_type='BASE TABLE' ORDER BY table_name;"
    return @($t -split "`r?`n" | Where-Object { $_ })
}

function RowCounts([string]$db) {
    $map = [ordered]@{}
    foreach ($t in (Tables $db)) {
        $map[$t] = [long](Sql "SELECT COUNT(*) FROM ``$db``.``$t``;")
    }
    return $map
}

Write-Host '== 0) sanity: mysql reachable =='
$live = RowCounts $Source
Check "0.1 source database '$Source' reachable" ($live.Count -ge 8) ("tables=" + ($live.Keys -join ','))
if ($live.Count -lt 8) {
    $fail0 = Save-Summary 'Backup / restore rehearsal'
    exit $fail0
}

# --------------------------------------------------------------------------- backup
Write-Host ''
Write-Host '== 1) take a backup =='
$dump = & (Join-Path $PSScriptRoot 'backup-db.ps1') -MysqlPassword $MysqlPassword -Database $Source -OutDir $outDir -KeepLast 5
$dumpPath = @($dump | Where-Object { $_ -and (Test-Path $_) })[0]
Check '1.1 dump file created' ([bool]$dumpPath) "path=$dumpPath"
if (-not $dumpPath) {
    $fail1 = Save-Summary 'Backup / restore rehearsal'
    exit $fail1
}

$dumpText = [System.IO.File]::ReadAllText($dumpPath, [System.Text.Encoding]::UTF8)
$dumpMb = [math]::Round((Get-Item $dumpPath).Length / 1KB, 1)
Check '1.2 dump contains schema (CREATE TABLE)' ($dumpText -match 'CREATE TABLE') ("{0} KB" -f $dumpMb)
Check '1.3 dump contains data (INSERT INTO)' ($dumpText -match 'INSERT INTO') ''
Check '1.4 dump encodes utf8mb4 (no mojibake marker)' ($dumpText -match 'SET NAMES utf8mb4') ''

# --------------------------------------------------------------------------- restore
Write-Host ''
Write-Host '== 2) restore into a scratch database =='
$null = & (Join-Path $PSScriptRoot 'restore-db.ps1') -File $dumpPath -MysqlPassword $MysqlPassword -TargetDatabase $Scratch
$scratchTables = Tables $Scratch
Check "2.1 scratch database '$Scratch' created with all tables" ($scratchTables.Count -eq $live.Count) ("live={0} restored={1}" -f $live.Count, $scratchTables.Count)

# --------------------------------------------------------------------------- compare
Write-Host ''
Write-Host '== 3) compare live vs restored =='
$restored = RowCounts $Scratch
$mismatch = @()
foreach ($t in $live.Keys) {
    $a = $live[$t]
    $b = if ($restored.Contains($t)) { $restored[$t] } else { -1 }
    if ($a -ne $b) { $mismatch += "$t(live=$a restored=$b)" }
}
Check '3.1 every table has the same row count' ($mismatch.Count -eq 0) ($(if ($mismatch.Count) { $mismatch -join ' ' } else { ($live.Keys | ForEach-Object { "$_=$($live[$_])" }) -join ' ' }))

# 抽样看具体值，而不只是行数：行数对上但内容错位是最隐蔽的一类失败
$spotLive = Sql "SELECT CONCAT(COUNT(*),'|',IFNULL(SUM(pay_amount),0),'|',IFNULL(MAX(id),0)) FROM $Source.t_order;"
$spotCopy = Sql "SELECT CONCAT(COUNT(*),'|',IFNULL(SUM(pay_amount),0),'|',IFNULL(MAX(id),0)) FROM $Scratch.t_order;"
Check '3.2 order rows/amount/maxId identical' ($spotLive -eq $spotCopy) "live=$spotLive restored=$spotCopy"

# 注意：断言里必须同时要求「非空」——否则查询写错（比如列不存在）时两边都返回空串，
# 会被判成「相等」而假通过（这里就踩过一次：t_product 没有 stock 列）
$prodLive = Sql "SELECT CONCAT(COUNT(*),'|',IFNULL(SUM(price),0),'|',IFNULL(SUM(sales),0)) FROM $Source.t_product;"
$prodCopy = Sql "SELECT CONCAT(COUNT(*),'|',IFNULL(SUM(price),0),'|',IFNULL(SUM(sales),0)) FROM $Scratch.t_product;"
Check '3.3 product rows/price/sales identical' ($prodLive.Length -gt 0 -and $prodLive -eq $prodCopy) "live=$prodLive restored=$prodCopy"

$skuLive = Sql "SELECT CONCAT(COUNT(*),'|',IFNULL(SUM(stock),0),'|',IFNULL(SUM(locked_stock),0)) FROM $Source.t_sku;"
$skuCopy = Sql "SELECT CONCAT(COUNT(*),'|',IFNULL(SUM(stock),0),'|',IFNULL(SUM(locked_stock),0)) FROM $Scratch.t_sku;"
Check '3.5 sku rows/stock/locked identical' ($skuLive.Length -gt 0 -and $skuLive -eq $skuCopy) "live=$skuLive restored=$skuCopy"

# 中文内容没被破坏（编码问题在备份恢复里非常常见）
$cnLive = Sql "SELECT IFNULL(MAX(name),'') FROM $Source.t_product;"
$cnCopy = Sql "SELECT IFNULL(MAX(name),'') FROM $Scratch.t_product;"
Check '3.4 chinese text survived the round trip' ($cnLive -eq $cnCopy -and $cnLive.Length -gt 0) "live=$cnLive restored=$cnCopy"

# --------------------------------------------------------------------------- cleanup
Write-Host ''
Write-Host '== 4) drop scratch database and confirm the live one is untouched =='
Sql "DROP DATABASE IF EXISTS ``$Scratch``;" | Out-Null
Check '4.1 scratch database dropped' (-not (Tables $Scratch).Count) "scratch tables=$((Tables $Scratch).Count)"

$after = RowCounts $Source
$drift = @()
foreach ($t in $live.Keys) { if ($live[$t] -ne $after[$t]) { $drift += $t } }
Check '4.2 live database untouched by the rehearsal' ($drift.Count -eq 0) ($(if ($drift.Count) { 'changed: ' + ($drift -join ',') } else { 'no drift' }))

$fail = Save-Summary 'Backup / restore rehearsal'
exit $fail

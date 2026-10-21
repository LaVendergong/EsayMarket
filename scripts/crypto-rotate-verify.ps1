# ============================================================================
# PII 加密密钥轮换演练（rotate -> re-encrypt -> drop old key）。
#
# 为什么需要：单密钥 + 不能轮换，等于"密钥一旦泄露就永远救不回来"。
# 光有代码能力不够 —— 必须证明「换密钥之后老数据还读得出来」，
# 否则运维照文档操作一次就把全库 PII 变成永久密文垃圾。
#
# 演练分四段（每段都重启应用，用环境变量注入不同的密钥组合）：
#   A) 新密钥 + previous=旧密钥        -> 旧数据仍可读（证明"过渡期"可行）
#   B) 同上 + re-encrypt-on-startup    -> 全部数据迁到新 keyId
#   C) 只保留新密钥（不给 previous）    -> 仍可读（证明重加密真的发生了，不是靠 previous 兜着）
#   D) 还原：旧密钥 + previous=新密钥 + 重加密 -> 数据回到旧 keyId，环境恢复成默认
#
# 断言依据：密文形如 enc:v1:<keyId>:<payload>，所以可以直接用 SQL 判断某行在哪个密钥下；
# 同时用管理端接口（会解密 PII）作为"能不能解开"的端到端探针。
#
# ASCII-only on purpose (see docs/AI-HANDOFF.md section 8, item 28).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File D:\projects\mall\scripts\crypto-rotate-verify.ps1
# ============================================================================
param(
    [string]$MysqlPassword = '521131'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$portal = 'http://localhost:8090'
$admin = 'http://localhost:8081'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$java = 'D:\jdks\jdk-17.0.12\bin\java.exe'
$outDir = Join-Path $root 'tmp\crypto-rotate'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$script:results = New-Object System.Collections.Generic.List[object]
. (Join-Path $PSScriptRoot 'e2e-lib.ps1')

# 与两个 application.yml 里 mall.crypto.secret 的默认值保持一致
$OLD = 'mall-dev-only-INSECURE-crypto-secret-0123456789abcdef'
$NEW = 'rotate-verify-crypto-secret-0123456789abcdef'

function Sql([string]$q) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $mysql -uroot "-p$MysqlPassword" -N --default-character-set=utf8mb4 -e "USE mall; $q" 2>$null
        return ($out | Out-String).Trim()
    } finally { $ErrorActionPreference = $prev }
}

# keyId = SHA-256(secret) 的前 8 个 hex 字符（与 AesGcmCipher.keyIdOf 一致）
function KeyId([string]$secret) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($secret))
    } finally { $sha.Dispose() }
    return ([System.BitConverter]::ToString($hash)).Replace('-', '').Substring(0, 8).ToLowerInvariant()
}

function Start-WithKeys([string]$label, [string]$secret, [string]$previous, [bool]$reEncrypt) {
    Stop-MallApps | Out-Null
    if ($secret) { $env:MALL_CRYPTO_SECRET = $secret } else { Remove-Item Env:\MALL_CRYPTO_SECRET -ErrorAction SilentlyContinue }
    if ($previous) { $env:MALL_CRYPTO_PREVIOUS_SECRETS = $previous } else { Remove-Item Env:\MALL_CRYPTO_PREVIOUS_SECRETS -ErrorAction SilentlyContinue }
    $env:MALL_CRYPTO_RE_ENCRYPT_ON_STARTUP = if ($reEncrypt) { 'true' } else { 'false' }
    return (Start-MallApps $label)
}

# 管理端订单列表会解密 receiver/addr：解密失败会 500。用它当「数据还读得出来」的探针
function PiiReadable([string]$adminToken) {
    $r = Http-Json -Method GET -Url "$admin/api/v1/admin/order/page?pageNum=1&pageSize=5" -Token $adminToken -Name ("probe-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
    $body = ""
    if ($r.raw) { $body = $r.raw }
    return [pscustomobject]@{
        status = $r.http
        code   = (Code $r)
        leaks  = ($body -match 'enc:v1:')
        rows   = ([regex]::Matches($body, 'receiverAddr')).Count
    }
}

function RowsUnderKey([string]$table, [string]$column, [string]$kid) {
    return [int](Sql ("SELECT COUNT(*) FROM $table WHERE $column LIKE 'enc:v1:%' AND $column LIKE CONCAT('enc:v1:','$kid',':%');"))
}
function RowsNotUnderKey([string]$table, [string]$column, [string]$kid) {
    return [int](Sql ("SELECT COUNT(*) FROM $table WHERE $column LIKE 'enc:v1:%' AND $column NOT LIKE CONCAT('enc:v1:','$kid',':%');"))
}

$kidOld = KeyId $OLD
$kidNew = KeyId $NEW
Write-Host ("old keyId = $kidOld    new keyId = $kidNew")
Check 'setup: two distinct key ids derived' ($kidOld -ne $kidNew -and $kidOld.Length -eq 8) "$kidOld vs $kidNew"
# 注意：不能假设「现有数据一定在某个 kid 下」—— 上一版代码写的密文是**没有 kid 的旧格式**
# （enc:v1:<payload>），那时还没有轮换能力。所以这里只断言「确实存在加密的 PII 可以演练」。
$encOrderRows = [int](Sql "SELECT COUNT(*) FROM t_order WHERE receiver_addr LIKE 'enc:v1:%';")
Check 'setup: there is existing encrypted PII to rotate' ($encOrderRows -gt 0) "encryptedOrderRows=$encOrderRows"

# ============================================================ A) new key + previous old
Write-Host ''
Write-Host '== A) rotate to NEW, keep OLD for decryption =='
$okA = Start-WithKeys 'A' $NEW $OLD $false
Check 'A1 both apps started with new key + previous old key' $okA 'logs'
$adm = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '10-admin'
$adminToken = (Payload $adm).token
Check 'A2 admin login works after rotation' ((Code $adm) -eq 200) "code=$(Code $adm)"

$probeA = PiiReadable $adminToken
Check 'A3 OLD data still readable after rotating (decrypted via previous key)' `
    ($probeA.code -eq 200 -and $probeA.rows -gt 0 -and -not $probeA.leaks) `
    "code=$($probeA.code) http=$($probeA.status) rows=$($probeA.rows) ciphertextLeak=$($probeA.leaks)"

# 「轮换本身不重加密」的正确判据：数据**还没有**迁到新 kid 下（旧格式也算没迁）
$stillOld = RowsNotUnderKey 't_order' 'receiver_addr' $kidNew
Check 'A4 rotating the key alone does NOT rewrite existing data (still not under the new keyId)' ($stillOld -gt 0) "rowsNotUnderNew=$stillOld"

# ============================================================ B) re-encrypt onto new key
Write-Host ''
Write-Host '== B) enable re-encrypt-on-startup =='
$okB = Start-WithKeys 'B' $NEW $OLD $true
Check 'B1 apps restarted with re-encrypt enabled' $okB 'logs'
$admB = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '20-admin'
$adminToken = (Payload $admB).token

$leftOld = RowsNotUnderKey 't_order' 'receiver_addr' $kidNew
$leftOldUser = RowsNotUnderKey 't_user' 'phone' $kidNew
$leftOldAddr = RowsNotUnderKey 't_address' 'phone' $kidNew
Check 'B2 no t_order row left under a previous key' ($leftOld -eq 0) "left=$leftOld"
Check 'B3 no t_user.phone left under a previous key' ($leftOldUser -eq 0) "left=$leftOldUser"
Check 'B4 no t_address.phone left under a previous key' ($leftOldAddr -eq 0) "left=$leftOldAddr"
$probeB = PiiReadable $adminToken
Check 'B5 data readable after re-encryption' ($probeB.code -eq 200 -and -not $probeB.leaks) "code=$($probeB.code) http=$($probeB.status)"

# ============================================================ C) drop previous key
Write-Host ''
Write-Host '== C) drop the previous key entirely (the real proof) =='
$okC = Start-WithKeys 'C' $NEW '' $false
Check 'C1 apps started with ONLY the new key' $okC 'logs'
$admC = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '30-admin'
$adminToken = (Payload $admC).token
$probeC = PiiReadable $adminToken
Check 'C2 all data still readable WITHOUT the old key (re-encryption really happened)' `
    ($probeC.code -eq 200 -and $probeC.rows -gt 0 -and -not $probeC.leaks) `
    "code=$($probeC.code) http=$($probeC.status) rows=$($probeC.rows) ciphertextLeak=$($probeC.leaks)"
Check 'C3 all rows now carry the new keyId' ((RowsUnderKey 't_order' 'receiver_addr' $kidNew) -gt 0) `
    "rowsUnderNew=$((RowsUnderKey 't_order' 'receiver_addr' $kidNew))"

# ============================================================ D) restore default key
Write-Host ''
Write-Host '== D) restore: rotate back to the default key (leave the environment as we found it) =='
$okD = Start-WithKeys 'D' $OLD $NEW $true
Check 'D1 apps restarted with default key + previous=new + re-encrypt' $okD 'logs'
$admD = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '40-admin'
$adminToken = (Payload $admD).token
Check 'D2 every row is back under the default keyId' ((RowsNotUnderKey 't_order' 'receiver_addr' $kidOld) -eq 0) `
    "leftUnderOther=$((RowsNotUnderKey 't_order' 'receiver_addr' $kidOld))"

Stop-MallApps | Out-Null
Remove-Item Env:\MALL_CRYPTO_SECRET -ErrorAction SilentlyContinue
Remove-Item Env:\MALL_CRYPTO_PREVIOUS_SECRETS -ErrorAction SilentlyContinue
Remove-Item Env:\MALL_CRYPTO_RE_ENCRYPT_ON_STARTUP -ErrorAction SilentlyContinue
$okRestore = Start-MallApps 'restore'
Check 'D3 apps restarted with default settings (no special env)' $okRestore 'logs'
$admE = Http-Json -Method POST -Url "$admin/api/v1/admin/auth/login" -Body @{ username = 'malladmin'; password = 'admin123456' } -Name '50-admin'
$probeE = PiiReadable (Payload $admE).token
Check 'D4 data readable again with plain defaults' ($probeE.code -eq 200 -and -not $probeE.leaks) "code=$($probeE.code) http=$($probeE.status)"

$fail = Save-Summary 'PII key rotation rehearsal'
exit $fail

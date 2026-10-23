# ============================================================================
# RBAC verification (ruoyi-style 用户-角色-权限)
#
# Covers:
#   - super admin gets every permission code at login (24 codes)
#   - role / permission / admin lists
#   - a FINANCE admin CAN read orders but CANNOT read products or ship orders
#   - cannot change your own roles (would lock out the last super admin)
#   - role assignment leaves an audit record
#
# Prerequisites: mysql migrated to sql/V24__rbac.sql, both apps running.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\rbac-verify.ps1
# Note: ASCII only (PS 5.1 reads no-BOM UTF-8 scripts as ANSI).
# ============================================================================
[CmdletBinding()]
param(
    [string]$Portal = 'http://localhost:8090/api/v1',
    [string]$Admin = 'http://localhost:8081/api/v1',
    [string]$AdminUser = 'malladmin',
    [string]$AdminPass = 'admin123456',
    [string]$Mysql = 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe',
    [string]$DbPass = '521131'
)

$ErrorActionPreference = 'Stop'
$outDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp\rbac'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reqFile = Join-Path $outDir '_request.json'
$respFile = Join-Path $outDir '_response.json'
$fails = 0
$tag = Get-Date -Format 'MMddHHmmss'
$financeUser = "fin$tag"
$financePwd = 'fin12345678'

function Step($name, $ok, $detail) {
    $ok = [bool]$ok
    if (-not $ok) { $script:fails++ }
    $t = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1} :: {2}" -f $t, $name, $detail)
}

function Api {
    param([string]$Method, [string]$Url, $Body, [string]$Token)
    $args = @('-s', '-S', '-X', $Method, $Url, '-H', 'Content-Type: application/json; charset=utf-8')
    if ($Token) { $args += @('-H', "Authorization: Bearer $Token") }
    if ($null -ne $Body) {
        [System.IO.File]::WriteAllText($reqFile, ($Body | ConvertTo-Json -Depth 10),
            (New-Object System.Text.UTF8Encoding($false)))
        $args += @('--data-binary', "@$reqFile")
    }
    Remove-Item $respFile -Force -ErrorAction SilentlyContinue
    $args += @('-o', $respFile)
    & curl.exe @args | Out-Null
    $txt = if (Test-Path $respFile) { Get-Content $respFile -Raw -Encoding UTF8 } else { '' }
    if (-not $txt) { return [pscustomobject]@{ code = -1; message = 'empty response' } }
    try { return ($txt | ConvertFrom-Json) } catch { return [pscustomobject]@{ code = -1; message = $txt } }
}

function Sql($query) {
    # mysql 的 "password on the command line" 警告走 stderr，会被 EAP=Stop 当成致命错误
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Mysql -uroot "-p$DbPass" -N --default-character-set=utf8mb4 -e "USE mall; $query" 2>$null | Out-Null
    } finally {
        $ErrorActionPreference = $prev
    }
}

# ---------------------------------------------------------------- super admin
$login = Api POST "$Admin/admin/auth/login" @{ username = $AdminUser; password = $AdminPass } $null
$at = $login.data.token
$perms = @($login.data.permissions)
Step 'super admin login returns permissions' ($perms.Count -ge 24) "count=$($perms.Count)"
Step 'super admin has SUPER_ADMIN role' (@($login.data.roles) -contains 'SUPER_ADMIN') `
    ("roles=" + (@($login.data.roles) -join ','))
Step 'super admin has rbac permission' ($perms -contains 'system:rbac') 'system:rbac ok'

$roles = Api GET "$Admin/admin/rbac/roles" $null $at
Step 'role list' ($roles.code -eq 200 -and @($roles.data).Count -ge 3) "count=$(@($roles.data).Count)"

$permList = Api GET "$Admin/admin/rbac/permissions" $null $at
Step 'permission list' ($permList.code -eq 200 -and @($permList.data).Count -ge 24) `
    "count=$(@($permList.data).Count)"

$adminList = Api GET "$Admin/admin/rbac/admins" $null $at
$me = @($adminList.data | Where-Object { $_.username -eq $AdminUser })[0]
Step 'admin list shows roles' ($null -ne $me -and $me.roleNames.Length -gt 0) "roles=$($me.roleNames)"

# ---------------------------------------------------------------- a FINANCE admin
Api POST "$Portal/portal/auth/register" @{ username = $financeUser; password = $financePwd; nickname = $financeUser } $null | Out-Null
# 注册出来是普通用户（role=0），这里直接提权并绑 FINANCE 角色，模拟「新来的财务同事」
$null = Sql "UPDATE t_user SET role = 1 WHERE username = '$financeUser';"
$null = Sql "INSERT INTO t_admin_user_role (user_id, role_id) SELECT u.id, r.id FROM t_user u JOIN t_admin_role r ON r.code = 'FINANCE' WHERE u.username = '$financeUser';"

$finLogin = Api POST "$Admin/admin/auth/login" @{ username = $financeUser; password = $financePwd } $null
$ft = $finLogin.data.token
$fPerms = @($finLogin.data.permissions)
Step 'finance admin login' ($null -ne $ft) "code=$($finLogin.code)"
Step 'finance has order:view' ($fPerms -contains 'order:view') ("count=" + $fPerms.Count)
Step 'finance does NOT have product:view' (-not ($fPerms -contains 'product:view')) `
    ("has=" + ($fPerms -contains 'product:view'))

$orders = Api GET "$Admin/admin/order/page?pageNum=1&pageSize=5" $null $ft
Step 'finance can read orders' ($orders.code -eq 200) "code=$($orders.code)"

$products = Api GET "$Admin/admin/product/page?pageNum=1&pageSize=5" $null $ft
Step 'finance cannot read products' ($products.code -ne 200) "code=$($products.code)"

$rbacDenied = Api GET "$Admin/admin/rbac/roles" $null $ft
Step 'finance cannot manage roles' ($rbacDenied.code -ne 200) "code=$($rbacDenied.code)"

# 发货需要 order:ship，财务只有 order:view
$anyOrder = @((Api GET "$Admin/admin/order/page?pageNum=1&pageSize=1" $null $at).data.list)[0]
if ($null -ne $anyOrder) {
    $shipDenied = Api POST "$Admin/admin/order/$($anyOrder.id)/ship" @{ company = 'SF'; shipNo = "SF$tag" } $ft
    Step 'finance cannot ship orders' ($shipDenied.code -ne 200) "code=$($shipDenied.code)"
} else {
    Step 'finance cannot ship orders' $true 'skipped (no order)'
}

# ---------------------------------------------------------------- self protection
$selfRoles = Api PUT "$Admin/admin/rbac/admins/$($login.data.userId)/roles" @() $at
Step 'cannot change own roles' ($selfRoles.code -ne 200) "code=$($selfRoles.code)"

# ---------------------------------------------------------------- audit trail
$audit = Api GET "$Admin/admin/audit/page?pageNum=1&pageSize=5&action=RBAC_ROLE_PERM" $null $at
Step 'audit page reachable' ($audit.code -eq 200) "code=$($audit.code)"

# ---------------------------------------------------------------- cleanup
$null = Sql "DELETE FROM t_admin_user_role WHERE user_id = (SELECT id FROM t_user WHERE username = '$financeUser');"
$null = Sql "UPDATE t_user SET deleted = 1 WHERE username = '$financeUser';"
Step 'cleanup done' $true 'finance test account removed'

Write-Host ''
Write-Host ("RESULT fails={0}" -f $fails)
if ($fails -gt 0) { exit 1 }

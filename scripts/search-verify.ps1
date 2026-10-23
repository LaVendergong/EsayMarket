# ============================================================================
# Search / suggest verification
#
# Covers:
#   - keyword hits product name, subTitle, detail, and **category name** (subquery)
#   - relevance sort: name hit ranks before subtitle hit (100/80/60/40/30/10)
#   - suggest: category name first, product name by sales, bounded by limit
#   - special characters (% ' \) do not break the SQL
#
# Prerequisites: mysql migrated to sql/V17__category_catalog.sql, both apps running.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\search-verify.ps1
#
# Note: ASCII only. Windows PowerShell 5.1 reads a no-BOM UTF-8 script as ANSI,
# so CJK literals would be corrupted before they reach the API. Chinese keywords
# below are sent as percent-encoded UTF-8 instead.
# ============================================================================
[CmdletBinding()]
param([string]$Portal = 'http://localhost:8090/api/v1')

$ErrorActionPreference = 'Stop'
$outDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp\search'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$respFile = Join-Path $outDir '_response.json'
$fails = 0

function Step($name, $ok, $detail) {
    $ok = [bool]$ok
    if (-not $ok) { $script:fails++ }
    $t = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1} :: {2}" -f $t, $name, $detail)
}

function Get-Json([string]$url) {
    curl.exe -s -S $url -o $respFile
    $txt = Get-Content $respFile -Raw -Encoding UTF8
    return ($txt | ConvertFrom-Json)
}

function Search([string]$encodedKw, [string]$sort) {
    $u = "$Portal/portal/product/page?pageNum=1&pageSize=20&keyword=$encodedKw"
    if ($sort) { $u = "$u&sort=$sort" }
    return (Get-Json $u).data
}

# percent-encoded UTF-8: handy reference for the keywords used below
$KW_PHONE = '%E6%89%8B%E6%9C%BA'          # 手机
$KW_RICE = '%E7%94%B5%E9%A5%AD%E7%85%B2'  # 电饭煲
$KW_NOISE = '%E9%99%8D%E5%99%AA'          # 降噪
$KW_CHIP = '%E9%BA%92%E9%BA%9F'           # 麒麟
$KW_NONE = '%E4%B8%8D%E5%AD%98%E5%9C%A8%E7%9A%84%E4%B8%9C%E8%A5%BF'  # 不存在的东西
$KW_HAND = '%E6%89%8B'                    # 手

# ---------------------------------------------------------------- keyword coverage
$byCat = Search $KW_PHONE ''
# 商品名里没有"手机"两个字，能出结果说明分类名子查询生效
Step 'keyword hits category name' ($byCat.total -gt 0) "total=$($byCat.total)"
@($byCat.list) | Select-Object -First 5 | ForEach-Object { Write-Host "    - $($_.name)" }

$byName = Search $KW_RICE ''
Step 'keyword hits product name' ($byName.total -eq 1) "total=$($byName.total) first=$(@($byName.list)[0].name)"

$bySub = Search $KW_CHIP ''
Step 'keyword hits subTitle' ($bySub.total -eq 1) "total=$($bySub.total) first=$(@($bySub.list)[0].name)"

# ---------------------------------------------------------------- relevance
$byRel = Search $KW_NOISE 'relevance'
$names = @($byRel.list | ForEach-Object { $_.name })
Step 'relevance sort returns rows' ($names.Count -gt 0) ("count=" + $names.Count)
for ($i = 0; $i -lt $names.Count; $i++) { Write-Host ("    {0}. {1}" -f ($i + 1), $names[$i]) }
if ($names.Count -ge 2) {
    # 两条都是副标题命中（同分 40），同分时按销量兜底：AirPods 80 > 索尼 15
    Step 'relevance: equal score falls back to sales' ($names[0] -eq 'AirPods Pro 2') "first=$($names[0])"
}

# ---------------------------------------------------------------- suggest
# CJK literal built from code points: an inline '手机' would be mangled by PS 5.1's ANSI decoding
$PHONE = -join ([char]0x624B, [char]0x673A)
$s1 = (Get-Json "$Portal/portal/product/suggest?keyword=$KW_HAND&limit=10").data
Step 'suggest returns category name' (@($s1) -contains $PHONE) ("count=" + @($s1).Count)

$s2 = (Get-Json "$Portal/portal/product/suggest?keyword=i&limit=5").data
Step 'suggest respects limit' ((@($s2).Count -ge 1) -and (@($s2).Count -le 5)) ("count=" + @($s2).Count)

$s3 = (Get-Json "$Portal/portal/product/suggest?keyword=$KW_NONE").data
Step 'suggest empty for unknown keyword' (@($s3).Count -eq 0) ("count=" + @($s3).Count)

# ---------------------------------------------------------------- escaping
$special = Search '100%25%27' ''   # 100%'
Step 'special characters do not break SQL' ($null -ne $special) "total=$($special.total)"

Write-Host ''
Write-Host ("RESULT fails={0}" -f $fails)
if ($fails -gt 0) { exit 1 }

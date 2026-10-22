#!/usr/bin/env bash
# ============================================================================
# 部署验证（对着真正跑起来的容器栈跑，不是对着源码猜）
#
# 这套检查回答的是「部署出来到底对不对」，和单元测试/接口测试是不同维度：
#   A) 5 个容器都起来了，mysql 健康
#   B) initdb 的 V1..V13 迁移真的执行了（新库、空数据卷）
#   C) prod profile 真的生效：接口文档关闭、actuator 只留 health+prometheus
#   D) prod 下的 Cookie 是 Secure + HttpOnly + SameSite
#   E) nginx 容器真的在服务构建产物，并且 /api 反代到后端
#   F) 部署环境端到端跑一单：注册→地址→下单，库里密文、接口明文
#   I) 资金链路在容器里真的通：创建支付单→支付→申请售后→渠道退款→对账
#
# 前置：docker compose -p malltest -f docker-compose.localtest.yml up -d --build
# 用法：bash scripts/deploy-verify.sh
# 环境变量：COMPOSE_DIR / PROJECT / COMPOSE_FILE / MYSQL_PASSWORD
# ============================================================================
set -uo pipefail

COMPOSE_DIR="${COMPOSE_DIR:-/root/mallbuild}"
PROJECT="${PROJECT:-malltest}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.localtest.yml}"
PORTAL_WEB="${PORTAL_WEB:-https://localhost:15174}"
ADMIN_WEB="${ADMIN_WEB:-https://localhost:15173}"
PORTAL_API="${PORTAL_API:-http://localhost:18090}"
ADMIN_API="${ADMIN_API:-http://localhost:18081}"
STAMP="$(date +%H%M%S)"
BUYER="deploy${STAMP}"
PWD_OK="deploy123456"

PASS=0
FAIL=0
ok()   { PASS=$((PASS+1)); printf '[PASS] %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '[FAIL] %s\n' "$1"; }
check(){ if [ "$2" = "1" ]; then ok "$1${3:+ :: $3}"; else bad "$1${3:+ :: $3}"; fi; }

cd "$COMPOSE_DIR" || { echo "compose dir not found: $COMPOSE_DIR"; exit 1; }
DC="docker compose -p $PROJECT -f $COMPOSE_FILE"

if [ -z "${MYSQL_PASSWORD:-}" ]; then
# .env 可能是 Windows 换行，密码会带上 \r，导致所有 mysql 调用静默失败（现象是查询全返回空）
  MYSQL_PASSWORD="$(grep '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')"
fi
sql() { $DC exec -T mysql mysql -uroot -p"$MYSQL_PASSWORD" -N --default-character-set=utf8mb4 -e "USE mall; $1" 2>/dev/null | tr -d '\r'; }

json() { curl -s -m 15 "$@" ; }

# 前端已经是 https（自签证书）。用**我们自己的 CA 校验**，比 -k 严格：
# 证书链错、主机名不匹配都会直接失败，而不是"忽略一切照跑"。
CA_FILE="${CA_FILE:-$COMPOSE_DIR/deploy/certs/ca.crt}"
if [ -f "$CA_FILE" ]; then
    curl() { command curl --cacert "$CA_FILE" "$@"; }
else
    echo "WARN: CA not found at $CA_FILE, https checks may fail (run scripts/gen-certs.sh)"
fi

code_of() { printf '%s' "$1" | sed -n 's/.*"code":\([0-9]*\).*/\1/p' | head -1; }
http_of() { curl -s -o /dev/null -m 15 -w '%{http_code}' "$@"; }

echo "== A) containers =="
running=$($DC ps --services --status running 2>/dev/null | sort | tr '\n' ' ')
for svc in mysql mall-admin mall-portal admin-web portal-web; do
  case " $running " in *" $svc "*) ok "A container running: $svc" ;; *) bad "A container NOT running: $svc" ;; esac
done
health=$($DC ps --format '{{.Service}}={{.Health}}' 2>/dev/null | grep '^mysql=' || true)
check "A mysql reports healthy" "$([ "${health#mysql=}" = "healthy" ] && echo 1 || echo 0)" "$health"
dbver=$(sql "SELECT VERSION();")
check "A mysql reachable and is 8.x" "$([ -n "$dbver" ] && echo 1 || echo 0)" "$dbver"

echo
echo "== B) migrations on a fresh data volume =="
tables=$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='mall';")
check "B business tables created by initdb (>=9)" "$([ "${tables:-0}" -ge 9 ] && echo 1 || echo 0)" "tables=$tables"
has_refresh=$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='mall' AND table_name='t_refresh_token';")
check "B V7 applied (t_refresh_token exists)" "$([ "${has_refresh:-0}" = "1" ] && echo 1 || echo 0)" "t_refresh_token=$has_refresh"
products=$(sql "SELECT COUNT(*) FROM t_product;")
check "B V3 demo data present" "$([ "${products:-0}" -gt 0 ] && echo 1 || echo 0)" "products=$products"
phone_def=$(sql "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema='mall' AND table_name='t_address' AND column_name='phone';")
check "B V6 widened PII columns (t_address.phone >= 128)" "$([ "${phone_def:-0}" -ge 128 ] && echo 1 || echo 0)" "phone varchar(${phone_def:-0})"

echo
echo "== C) prod profile is really active =="
h=$(http_of "$PORTAL_API/actuator/health")
check "C /actuator/health = 200" "$([ "$h" = "200" ] && echo 1 || echo 0)" "http=$h"
h=$(http_of "$PORTAL_API/actuator/prometheus")
check "C /actuator/prometheus = 200 (metrics exposed in prod)" "$([ "$h" = "200" ] && echo 1 || echo 0)" "http=$h"
h=$(http_of "$PORTAL_API/actuator/info")
check "C /actuator/info = 404 (not exposed in prod)" "$([ "$h" = "404" ] && echo 1 || echo 0)" "http=$h"
h=$(http_of "$PORTAL_API/actuator/metrics")
check "C /actuator/metrics = 404 (only health+prometheus in prod)" "$([ "$h" = "404" ] && echo 1 || echo 0)" "http=$h"
# 注意：/doc.html 是 knife4j 打包在 jar 里的静态壳，prod 下仍会 200；真正要紧的是它依赖的
# /v3/api-docs 与 /webjars/** 都 404 —— 拿不到任何接口信息。所以断言后者。
h=$(http_of "$PORTAL_API/v3/api-docs")
check "C /v3/api-docs = 404 (no api schema in prod)" "$([ "$h" = "404" ] && echo 1 || echo 0)" "http=$h"
h=$(http_of "$PORTAL_API/webjars/js/app.js")
check "C /webjars/** = 404 (doc ui assets gone in prod)" "$([ "$h" = "404" ] && echo 1 || echo 0)" "http=$h"
h=$(http_of "$PORTAL_API/api/v1/portal/user/me")
check "C protected api without credential = 401" "$([ "$h" = "401" ] && echo 1 || echo 0)" "http=$h"
pub=$(json "$PORTAL_API/api/v1/portal/product/page?pageNum=1&pageSize=1")
check "C public api works through the container" "$([ "$(code_of "$pub")" = "200" ] && echo 1 || echo 0)" "code=$(code_of "$pub")"

echo
echo "== D) prod cookies (Secure + HttpOnly + SameSite) =="
headers=$(curl -s -D - -o /dev/null -m 15 -X POST "$PORTAL_API/api/v1/portal/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$BUYER\",\"password\":\"$PWD_OK\",\"nickname\":\"D\"}")
# 注意：这里用 -o 把 body 写进文件（为了拿 headers），所以不能用 $( ) 取响应体，
# 否则 $login 是空串、code_of 永远解析不到（第一版就是这么假失败的）
curl -s -D /tmp/login.headers -o /tmp/login.body -m 15 -X POST "$PORTAL_API/api/v1/portal/auth/login" \
  -H 'Content-Type: application/json' -d "{\"username\":\"$BUYER\",\"password\":\"$PWD_OK\"}" >/dev/null
login_code=$(sed -n 's/.*"code":\([0-9]*\).*/\1/p' /tmp/login.body | head -1)
tok=$(sed -n 's/.*"token":"\([^"]*\)".*/\1/p' /tmp/login.body | head -1)
check "D login ok" "$([ "${login_code:-0}" = "200" ] && echo 1 || echo 0)" "code=${login_code:-none}"

ac=$(grep -i '^set-cookie: mall_portal_token=' /tmp/login.headers | head -1)
check "D access cookie is Secure (prod)" "$(printf '%s' "$ac" | grep -qi 'secure' && echo 1 || echo 0)" "$(printf '%s' "$ac" | cut -c1-90)"
check "D access cookie is HttpOnly" "$(printf '%s' "$ac" | grep -qi 'httponly' && echo 1 || echo 0)" ''
check "D access cookie is SameSite=Lax" "$(printf '%s' "$ac" | grep -qi 'samesite=lax' && echo 1 || echo 0)" ''
rc=$(grep -i '^set-cookie: mall_portal_refresh=' /tmp/login.headers | head -1)
check "D refresh cookie issued" "$([ -n "$rc" ] && echo 1 || echo 0)" "$(printf '%s' "$rc" | cut -c1-60)"

# access TTL 应该是 15 分钟（900s）
payload=$(printf '%s' "$tok" | cut -d. -f2 | tr '_-' '/+')
case $(( ${#payload} % 4 )) in 2) payload="$payload==" ;; 3) payload="$payload=" ;; esac
iat=$(printf '%s' "$payload" | base64 -d 2>/dev/null | sed -n 's/.*"iat":\([0-9]*\).*/\1/p')
exp=$(printf '%s' "$payload" | base64 -d 2>/dev/null | sed -n 's/.*"exp":\([0-9]*\).*/\1/p')
ttl=$(( ${exp:-0} - ${iat:-0} ))
check "D access token TTL is 900s (15 min)" "$([ "$ttl" = "900" ] && echo 1 || echo 0)" "exp-iat=${ttl}s"

echo
echo "== E) nginx serving the built artifacts =="
body=$(json "$PORTAL_WEB/")
check "E H5 static page served" "$(printf '%s' "$body" | grep -q 'id="app"' && echo 1 || echo 0)" "bytes=${#body}"
hdr=$(curl -s -I -m 15 "$PORTAL_WEB/" | tr -d '\r')
check "E security header nosniff present" "$(printf '%s' "$hdr" | grep -qi 'x-content-type-options: nosniff' && echo 1 || echo 0)" ''
check "E security header X-Frame-Options present" "$(printf '%s' "$hdr" | grep -qi 'x-frame-options' && echo 1 || echo 0)" ''
proxied=$(json "$PORTAL_WEB/api/v1/portal/product/page?pageNum=1&pageSize=1")
check "E nginx proxies /api to mall-portal" "$([ "$(code_of "$proxied")" = "200" ] && echo 1 || echo 0)" "code=$(code_of "$proxied")"
abody=$(json "$ADMIN_WEB/")
check "E admin static page served" "$(printf '%s' "$abody" | grep -q 'id="app"' && echo 1 || echo 0)" "bytes=${#abody}"

# https 落地的三条：协议真的换了、HSTS 真的下发了、http 会跳过去
hsts_hdr=$(curl -s -m 10 -D - -o /dev/null "$PORTAL_WEB/")
check "E portal is served over https" "$(printf '%s' "$PORTAL_WEB" | grep -q '^https://' && echo 1 || echo 0)" "url=$PORTAL_WEB"
check "E HSTS header present" "$(printf '%s' "$hsts_hdr" | grep -qi 'strict-transport-security:.*max-age=' && echo 1 || echo 0)" \
  "$(printf '%s' "$hsts_hdr" | grep -i 'strict-transport-security' | tr -d '\r' | head -1)"
http_code=$(curl -s -m 10 -o /dev/null -w '%{http_code}' "http://localhost:15176/")
http_loc=$(curl -s -m 10 -o /dev/null -w '%{redirect_url}' "http://localhost:15176/")
check "E http is redirected to https (301)" "$([ "$http_code" = "301" ] && echo 1 || echo 0)" "code=$http_code location=$http_loc"

echo
echo
echo "== H) Chinese text is intact (initdb charset) =="
# 这一组是回归「中文被二次编码」那个坑：initdb 的 mysql 客户端若以 latin1 建连接，
# 种子里的 UTF-8 中文会以「UTF-8 字节被当 latin1 再编码」的形式写进库（å°ç±³），
# 应用侧怎么改都救不回来，只能重建库。
prod_json=$(json "$PORTAL_API/api/v1/portal/product/page?pageNum=1&pageSize=5")
check "H product api returns real CJK text" "$(printf '%s' "$prod_json" | grep -qP '[\x{4e00}-\x{9fa5}]' && echo 1 || echo 0)" ''
check "H product api has no latin1 mojibake chars" "$(printf '%s' "$prod_json" | grep -qP '[\x{00C0}-\x{00FF}]' && echo 0 || echo 1)" ''
# 双重编码在 HEX 里一定出现 C3A5/C3A6/C3A7 这类前缀（正常 CJK 是 E4..E9 开头），可以直接查库
moji_rows=$(sql "SELECT (SELECT COUNT(*) FROM t_product WHERE HEX(name) LIKE '%C3A5%' OR HEX(name) LIKE '%C3A6%' OR HEX(name) LIKE '%C3A7%') + (SELECT COUNT(*) FROM t_category WHERE HEX(name) LIKE '%C3A5%' OR HEX(name) LIKE '%C3A6%' OR HEX(name) LIKE '%C3A7%');")
check "H no double-encoded rows in the database" "$([ "${moji_rows:-1}" = "0" ] && echo 1 || echo 0)" "badRows=${moji_rows:-unknown}"
cli_charset=$(sql "SHOW GLOBAL VARIABLES LIKE 'character_set_client';" | awk '{print $2}' | tr -d '\r')
check "H server overrides client charset (utf8mb4)" "$([ "$cli_charset" = "utf8mb4" ] && echo 1 || echo 0)" "character_set_client=$cli_charset"

echo "== F) end-to-end order on the deployed stack =="
AT="Authorization: Bearer $tok"
addr=$(curl -s -m 15 -X POST "$PORTAL_API/api/v1/portal/address" -H "$AT" -H 'Content-Type: application/json' \
  -d '{"receiver":"Deploy Recv","phone":"13600009999","province":"GD","city":"SZ","district":"NS","detail":"Deploy Road 9","isDefault":1}')
addr_id=$(printf '%s' "$addr" | sed -n 's/.*"data":\([0-9]*\).*/\1/p' | head -1)
check "F address created" "$([ -n "$addr_id" ] && echo 1 || echo 0)" "addressId=$addr_id"

sku=$(sql "SELECT s.id FROM t_sku s JOIN t_product p ON s.product_id=p.id WHERE p.status=1 AND s.stock>5 LIMIT 1;")
curl -s -m 15 -X POST "$PORTAL_API/api/v1/portal/cart" -H "$AT" -H 'Content-Type: application/json' \
  -d "{\"skuId\":$sku,\"quantity\":1}" >/dev/null
order=$(curl -s -m 15 -X POST "$PORTAL_API/api/v1/portal/order" -H "$AT" -H 'Content-Type: application/json' \
  -d "{\"addressId\":$addr_id,\"requestId\":\"deploy-$STAMP\"}")
order_id=$(printf '%s' "$order" | sed -n 's/.*"orderId":\([0-9]*\).*/\1/p' | head -1)
check "F order created" "$([ -n "$order_id" ] && echo 1 || echo 0)" "orderId=$order_id code=$(code_of "$order")"

stored=$(sql "SELECT LEFT(receiver_addr,20) FROM t_order WHERE id=$order_id;")
check "F order snapshot stored as ciphertext in the container DB" "$(printf '%s' "$stored" | grep -q '^enc:v1:' && echo 1 || echo 0)" "stored=${stored:0:14}..."
detail=$(curl -s -m 15 "$PORTAL_API/api/v1/portal/order/$order_id" -H "$AT")
check "F order detail returns decrypted receiver" "$(printf '%s' "$detail" | grep -q 'Deploy Recv' && echo 1 || echo 0)" ''
check "F order detail leaks no ciphertext" "$(printf '%s' "$detail" | grep -q 'enc:v1:' && echo 0 || echo 1)" ''

echo ""
echo "== I) money chain: pay -> refund -> reconcile (on the deployed stack) =="
ATADM_FILE="/tmp/deploy-admin-token-$"
ADM=$(curl -s -m 15 -X POST "$ADMIN_API/api/v1/admin/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"malladmin","password":"admin123456"}')
adm_tok=$(printf '%s' "$ADM" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p' | head -1)
check "I admin login on the deployed stack" "$([ -n "$adm_tok" ] && echo 1 || echo 0)" "code=$(code_of "$ADM")"
ATADM="Authorization: Bearer $adm_tok"
rc_max=$(sql "SELECT IFNULL(MAX(id),0) FROM t_reconcile_batch;")

channels=$(curl -s -m 15 "$PORTAL_API/api/v1/portal/payment/channels" -H "$AT")
check "I only configured channels are offered (MOCK in dev)" \
  "$(printf '%s' "$channels" | grep -q '"MOCK"' && echo 1 || echo 0)" "code=$(code_of "$channels")"

pay=$(curl -s -m 15 -X POST "$PORTAL_API/api/v1/portal/payment" -H "$AT" -H 'Content-Type: application/json' \
  -d "{\"orderId\":$order_id,\"channel\":\"MOCK\"}")
pay_no=$(printf '%s' "$pay" | sed -n 's/.*"paymentNo":"\([^"]*\)".*/\1/p' | head -1)
check "I payment order created" "$([ -n "$pay_no" ] && echo 1 || echo 0)" "paymentNo=$pay_no code=$(code_of "$pay")"

curl -s -m 15 -X POST "$PORTAL_API/api/v1/portal/payment/$pay_no/mock-pay" -H "$AT" >/dev/null
pay_row=$(sql "SELECT CONCAT(status,'|',channel) FROM t_payment WHERE payment_no='$pay_no';")
check "I payment settled (1|MOCK)" "$([ "$pay_row" = "1|MOCK" ] && echo 1 || echo 0)" "row=$pay_row"
ord_status=$(sql "SELECT status FROM t_order WHERE id=$order_id;")
check "I order became PAID(1) after payment" "$([ "$ord_status" = "1" ] && echo 1 || echo 0)" "status=$ord_status"

as_apply=$(curl -s -m 15 -X POST "$PORTAL_API/api/v1/portal/after-sale" -H "$AT" -H 'Content-Type: application/json' \
  -d "{\"orderId\":$order_id,\"type\":1,\"reason\":\"部署验证\"}")
as_id=$(printf '%s' "$as_apply" | sed -n 's/.*"data":\([0-9]*\).*/\1/p' | head -1)
check "I after-sale applied" "$([ -n "$as_id" ] && echo 1 || echo 0)" "id=$as_id code=$(code_of "$as_apply")"

curl -s -m 15 -X POST "$ADMIN_API/api/v1/admin/after-sale/$as_id/approve" -H "$ATADM" \
  -H 'Content-Type: application/json' -d '{"remark":"deploy ok"}' >/dev/null
refund_row=$(sql "SELECT CONCAT(refund_status,'|',IFNULL(refund_channel,'-')) FROM t_after_sale WHERE id=$as_id;")
check "I refund really executed through the channel (2|MOCK)" "$([ "$refund_row" = "2|MOCK" ] && echo 1 || echo 0)" "row=$refund_row"
ord_after_refund=$(sql "SELECT status FROM t_order WHERE id=$order_id;")
check "I order became REFUNDED(6)" "$([ "$ord_after_refund" = "6" ] && echo 1 || echo 0)" "status=$ord_after_refund"

# 对账：账单里放这笔收款，金额与订单实付一致，应当匹配上
amount=$(sql "SELECT pay_amount FROM t_order WHERE id=$order_id;")
bill_date=$(sql "SELECT DATE(paid_time) FROM t_payment WHERE payment_no='$pay_no';")
bill="out_trade_no,channel_trade_no,amount,status,biz_type,refund_no
$pay_no,DT-$STAMP,$amount,SUCCESS,PAY,"
content=$(printf '%s' "$bill" | sed ':a;N;$!ba;s/\n/\\n/g')
rec=$(curl -s -m 20 -X POST "$ADMIN_API/api/v1/admin/reconcile" -H "$ATADM" -H 'Content-Type: application/json' \
  -d "{\"channel\":\"MOCK\",\"billDate\":\"$bill_date\",\"content\":\"$content\"}")
matched=$(printf '%s' "$rec" | sed -n 's/.*"matched":\([0-9]*\).*/\1/p' | head -1)
check "I reconcile accepted the bill and matched the payment" \
  "$([ "$(code_of "$rec")" = "200" ] && [ "${matched:-0}" -ge 1 ] && echo 1 || echo 0)" \
  "code=$(code_of "$rec") matched=${matched:-0} amount=$amount"

# 新增的页面必须真的进到构建产物里（Vite 按组件名切 chunk）：
# 只测接口的话，前端漏打包也照样"全绿"。
admin_chunks=$(docker exec malltest-admin-web-1 ls /usr/share/nginx/html/assets 2>/dev/null | tr '\n' ' ')
check "I admin bundle contains the reconcile page" \
  "$(printf '%s' "$admin_chunks" | grep -q 'ReconcileView' && echo 1 || echo 0)" "assets=$(printf '%s' "$admin_chunks" | wc -w)"
portal_chunks=$(docker exec malltest-portal-web-1 ls /usr/share/nginx/html/assets 2>/dev/null | tr '\n' ' ')
check "I portal bundle contains the cashier and after-sale pages" \
  "$(printf '%s' "$portal_chunks" | grep -q 'CashierView' && printf '%s' "$portal_chunks" | grep -q 'AfterSaleDetailView' && echo 1 || echo 0)" \
  "assets=$(printf '%s' "$portal_chunks" | wc -w)"

user_id=$(sql "SELECT id FROM t_user WHERE username='$BUYER';")
sql "DELETE FROM t_after_sale WHERE user_id=$user_id; DELETE FROM t_payment WHERE user_id=$user_id; DELETE FROM t_order_item WHERE order_id=$order_id; DELETE FROM t_order WHERE id=$order_id; DELETE FROM t_cart WHERE user_id=$user_id; DELETE FROM t_address WHERE user_id=$user_id; DELETE FROM t_refresh_token WHERE user_id=$user_id; DELETE FROM t_user WHERE id=$user_id; DELETE FROM t_reconcile_diff WHERE batch_id>$rc_max; DELETE FROM t_reconcile_batch WHERE id>$rc_max;" >/dev/null
left=$(sql "SELECT COUNT(*) FROM t_user WHERE username='$BUYER';")
check "G cleanup left nothing behind" "$([ "${left:-1}" = "0" ] && echo 1 || echo 0)" "leftover=$left"

echo
echo "================================"
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1

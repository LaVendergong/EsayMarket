#!/usr/bin/env bash
# ============================================================================
# HTTPS 落地验证（自签 CA + nginx 终止 TLS + 真实握手）
#
# 为什么要在 WSL 里跑：本项目生产用 nginx 反代，但开发机上没有 nginx。
# WSL 里装一个，就能**真的把配置跑起来**，而不是只交付一份没人验证过的模板。
#
# 验证内容：
#   1. 证书链（自签 CA -> 服务器证书，SAN 覆盖 localhost/127.0.0.1）
#   2. nginx 配置语法 + 启动后 HTTP -> HTTPS 301
#   3. TLS 握手：只允许 1.2/1.3，1.1 必须被拒
#   4. 只有信任我们的 CA 才能校验通过（-k 才能连 = 证书确实是我们签的）
#   5. 安全响应头：HSTS / nosniff / DENY / Referrer-Policy
#   6. 通过 HTTPS 调真实业务接口（admin 登录）能通
#
# 用法：wsl -u root -e bash /mnt/d/projects/mall/scripts/https-verify.sh
# ============================================================================
set -uo pipefail

ROOT=/mnt/d/projects/mall
TLS=$ROOT/tmp/tls
PREFIX=/tmp/nginx-tls
HTTP_PORT=18080
HTTPS_PORT=18443
GW=$(ip route | awk '/^default/{print $3; exit}')

PORTS_DIR=$TLS
result_total=0
result_pass=0
result_fail=0

check() {
    local name="$1" ok="$2" detail="${3:-}"
    result_total=$((result_total + 1))
    if [ "$ok" = "1" ]; then
        result_pass=$((result_pass + 1))
        printf '[PASS] %s%s\n' "$name" "${detail:+ :: $detail}"
    else
        result_fail=$((result_fail + 1))
        printf '[FAIL] %s%s\n' "$name" "${detail:+ :: $detail}"
    fi
}

cleanup() {
    if [ -f "$PREFIX/nginx.pid" ]; then
        nginx -p "$PREFIX" -c "$PREFIX/nginx.conf" -s stop 2>/dev/null || true
        sleep 1
    fi
}
trap cleanup EXIT

echo "== 0) 准备目录与证书 =="
rm -rf "$TLS" "$PREFIX"
mkdir -p "$TLS" "$PREFIX"
mkdir -p "$PREFIX"/{client_body,proxy,fastcgi,uwsgi,scgi}

# --- 自签 CA
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout "$TLS/ca.key" -out "$TLS/ca.crt" \
    -subj "/C=CN/O=Mall Test/CN=Mall Test CA" >/dev/null 2>&1

# --- 服务器证书（SAN 必须覆盖访问用的主机名，否则 curl 会报 hostname mismatch）
cat > "$TLS/ext.cnf" <<'EOF'
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost, DNS:mall.local, IP:127.0.0.1
EOF

openssl req -newkey rsa:2048 -nodes -keyout "$TLS/server.key" -out "$TLS/server.csr" \
    -subj "/C=CN/O=Mall Test/CN=mall.local" >/dev/null 2>&1
openssl x509 -req -in "$TLS/server.csr" -CA "$TLS/ca.crt" -CAkey "$TLS/ca.key" \
    -CAcreateserial -days 365 -sha256 -extfile "$TLS/ext.cnf" -out "$TLS/server.crt" >/dev/null 2>&1

check "0.1 CA 与服务器证书已生成" \
    "$([ -f "$TLS/ca.crt" ] && [ -f "$TLS/server.crt" ] && [ -f "$TLS/server.key" ] && echo 1 || echo 0)" \
    "$(ls -1 "$TLS" | tr '\n' ' ')"

verify_out=$(openssl verify -CAfile "$TLS/ca.crt" "$TLS/server.crt" 2>&1)
check "0.2 证书链校验通过（自签 CA 签发）" "$(echo "$verify_out" | grep -q ': OK' && echo 1 || echo 0)" "$verify_out"

san=$(openssl x509 -in "$TLS/server.crt" -noout -ext subjectAltName 2>/dev/null | tr -d '\n')
check "0.3 SAN 覆盖 localhost / 127.0.0.1" \
    "$(echo "$san" | grep -q 'DNS:localhost' && echo "$san" | grep -q 'IP Address:127.0.0.1' && echo 1 || echo 0)" "$san"

echo ""
echo "== 1) nginx 配置（TLS 终止 + 反代）=="
cat > "$PREFIX/nginx.conf" <<EOF
worker_processes 1;
error_log $PREFIX/error.log warn;
pid $PREFIX/nginx.pid;

events { worker_connections 64; }

http {
    access_log $PREFIX/access.log;
    client_body_temp_path $PREFIX/client_body;
    proxy_temp_path $PREFIX/proxy;
    fastcgi_temp_path $PREFIX/fastcgi;
    uwsgi_temp_path $PREFIX/uwsgi;
    scgi_temp_path $PREFIX/scgi;
    server_tokens off;
    default_type application/json;

    # HTTP 只做一件事：跳到 HTTPS
    server {
        listen $HTTP_PORT;
        server_name _;
        return 301 https://\$host:$HTTPS_PORT\$request_uri;
    }

    server {
        listen $HTTPS_PORT ssl;
        http2 on;
        server_name localhost mall.local;

        ssl_certificate     $TLS/server.crt;
        ssl_certificate_key $TLS/server.key;
        ssl_protocols       TLSv1.2 TLSv1.3;
        ssl_ciphers         ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384;
        ssl_prefer_server_ciphers off;
        ssl_session_cache   shared:SSL:10m;
        ssl_session_timeout 10m;

        add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header X-Frame-Options "DENY" always;
        add_header Referrer-Policy "no-referrer" always;

        location = /health { return 200 '{"status":"UP"}'; }

        location /api/v1/portal/ {
            proxy_pass http://$GW:8090;
            proxy_set_header Host \$host;
            proxy_set_header X-Real-IP \$remote_addr;
            proxy_set_header X-Forwarded-Proto https;
            proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
            proxy_set_header Origin "";
        }

        location /api/v1/admin/ {
            proxy_pass http://$GW:8081;
            proxy_set_header Host \$host;
            proxy_set_header X-Real-IP \$remote_addr;
            proxy_set_header X-Forwarded-Proto https;
            proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
            proxy_set_header Origin "";
        }
    }
}
EOF

conf_test=$(nginx -p "$PREFIX" -c "$PREFIX/nginx.conf" -t 2>&1)
check "1.1 nginx -t 语法检查通过" "$(echo "$conf_test" | grep -q 'syntax is ok' && echo 1 || echo 0)" \
    "$(echo "$conf_test" | tail -1)"

nginx -p "$PREFIX" -c "$PREFIX/nginx.conf" >/dev/null 2>&1
started=0
for _ in $(seq 1 20); do
    if curl -s -m 2 -o /dev/null "http://127.0.0.1:$HTTP_PORT/health" 2>/dev/null; then
        started=1
        break
    fi
    sleep 0.5
done
check "1.2 nginx 启动成功" "$started" "http=$HTTP_PORT https=$HTTPS_PORT"

echo ""
echo "== 2) HTTP -> HTTPS 跳转 =="
redirect_code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$HTTP_PORT/health")
redirect_loc=$(curl -s -m 5 -o /dev/null -w '%{redirect_url}' "http://127.0.0.1:$HTTP_PORT/health")
check "2.1 HTTP 返回 301" "$([ "$redirect_code" = "301" ] && echo 1 || echo 0)" "code=$redirect_code"
check "2.2 跳转目标是 https 地址" "$(echo "$redirect_loc" | grep -q "^https://" && echo 1 || echo 0)" "location=$redirect_loc"

echo ""
echo "== 3) TLS 握手与协议策略 =="
code_trusted=$(curl -s -m 8 --cacert "$TLS/ca.crt" -o /dev/null -w '%{http_code}' "https://localhost:$HTTPS_PORT/health")
check "3.1 信任 CA 时握手成功（200）" "$([ "$code_trusted" = "200" ] && echo 1 || echo 0)" "code=$code_trusted"

untrusted=$(curl -s -m 8 -o /dev/null -w '%{http_code}' "https://localhost:$HTTPS_PORT/health" 2>&1)
check "3.2 不信任该 CA 时校验失败（说明证书确实是我们签的）" \
    "$(echo "$untrusted" | grep -q '200' && echo 0 || echo 1)" "code=$untrusted"

tls11=$(curl -s -m 8 --tlsv1.1 --tls-max 1.1 -k -o /dev/null -w '%{http_code}' "https://localhost:$HTTPS_PORT/health" 2>&1)
check "3.3 TLS 1.1 被拒绝" "$(echo "$tls11" | grep -q '200' && echo 0 || echo 1)" "$(echo "$tls11" | head -c 80)"

tls13=$(openssl s_client -connect 127.0.0.1:$HTTPS_PORT -tls1_3 -CAfile "$TLS/ca.crt" </dev/null 2>/dev/null | grep -c 'Verify return code: 0')
check "3.4 TLS 1.3 可用且链校验通过" "$([ "${tls13:-0}" -ge 1 ] && echo 1 || echo 0)" "verify_ok=$tls13"

echo ""
echo "== 4) 安全响应头 =="
headers=$(curl -s -m 8 -D - --cacert "$TLS/ca.crt" -o /dev/null "https://localhost:$HTTPS_PORT/health")
check "4.1 HSTS 已下发（含 max-age 且不短于一年）" \
    "$(echo "$headers" | grep -qi 'strict-transport-security:.*max-age=31536000' && echo 1 || echo 0)" \
    "$(echo "$headers" | grep -i 'strict-transport-security' | tr -d '\r' | head -1)"
check "4.2 X-Content-Type-Options: nosniff" \
    "$(echo "$headers" | grep -qi 'x-content-type-options: *nosniff' && echo 1 || echo 0)" ""
check "4.3 X-Frame-Options: DENY" \
    "$(echo "$headers" | grep -qi 'x-frame-options: *DENY' && echo 1 || echo 0)" ""
check "4.4 Referrer-Policy 已下发" \
    "$(echo "$headers" | grep -qi 'referrer-policy:' && echo 1 || echo 0)" \
    "$(echo "$headers" | grep -i 'referrer-policy' | tr -d '\r' | head -1)"
check "4.5 不暴露 nginx 版本（server_tokens off）" \
    "$(echo "$headers" | grep -qi '^server: *nginx/' && echo 0 || echo 1)" \
    "$(echo "$headers" | grep -i '^server:' | tr -d '\r' | head -1)"

echo ""
echo "== 5) 通过 HTTPS 调真实业务接口 =="
login=$(curl -s -m 10 --cacert "$TLS/ca.crt" -X POST \
    -H 'Content-Type: application/json' \
    -d '{"username":"malladmin","password":"admin123456"}' \
    "https://localhost:$HTTPS_PORT/api/v1/admin/auth/login")
login_code=$(echo "$login" | sed -n 's/.*"code":\([0-9]*\).*/\1/p')
check "5.1 HTTPS -> nginx -> 后端：管理员登录成功" "$([ "$login_code" = "200" ] && echo 1 || echo 0)" "code=${login_code:-none}"

token=$(echo "$login" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -n "$token" ]; then
    api_code=$(curl -s -m 10 --cacert "$TLS/ca.crt" -o /dev/null -w '%{http_code}' \
        -H "Authorization: Bearer $token" \
        "https://localhost:$HTTPS_PORT/api/v1/admin/product/page?pageNum=1&pageSize=1")
    check "5.2 带 token 的业务接口经 HTTPS 可用（200）" "$([ "$api_code" = "200" ] && echo 1 || echo 0)" "code=$api_code"
else
    check "5.2 带 token 的业务接口经 HTTPS 可用（200）" 0 "拿不到 token"
fi

echo ""
echo "== 汇总 =="
printf '总计 %d 项，通过 %d 项，失败 %d 项\n' "$result_total" "$result_pass" "$result_fail"
echo "$(date '+%Y-%m-%d %H:%M:%S') total=$result_total pass=$result_pass fail=$result_fail" > "$ROOT/tmp/tls/summary.txt"
exit "$result_fail"

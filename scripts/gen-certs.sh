#!/usr/bin/env bash
# ============================================================================
# 生成本地部署栈用的自签证书（CA + 服务器证书，SAN 覆盖 localhost）
#
# 为什么自签：本地/自动化要能完整验证 TLS（握手、协议版本、HSTS、Secure Cookie），
# 但公司/个人域名与真实证书在这里拿不到。真实证书上线时替换 deploy/certs 即可。
#
# 用法：wsl -u root -e bash /mnt/d/projects/mall/scripts/gen-certs.sh
#      （幂等：已有 server.crt 就跳过；加 --force 强制重生成）
# 产物：deploy/certs/{ca.crt,ca.key,server.crt,server.key}   ← 已 gitignore
# ============================================================================
set -euo pipefail

DIR="${1:-/mnt/d/projects/mall/deploy/certs}"
FORCE="${2:-}"

if [ -f "$DIR/server.crt" ] && [ "$FORCE" != "--force" ]; then
    echo "  certs already exist: $DIR"
    exit 0
fi

mkdir -p "$DIR"
cd "$DIR"

# CA
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout ca.key -out ca.crt \
    -subj "/C=CN/O=Mall Local/CN=Mall Local CA" >/dev/null 2>&1

# 服务器证书：SAN 必须覆盖访问用的主机名，只写 CN 浏览器/curl 都不认
cat > ext.cnf <<'EOF'
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost, DNS:mall.local, IP:127.0.0.1
EOF

openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr \
    -subj "/C=CN/O=Mall Local/CN=localhost" >/dev/null 2>&1
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -days 365 -sha256 -extfile ext.cnf -out server.crt >/dev/null 2>&1

rm -f server.csr ext.cnf ca.srl
# nginx worker 以 nginx 用户运行，私钥要可读（仅本地测试；线上应 600 + 专用用户）
chmod 644 server.key ca.key server.crt ca.crt

echo "  generated:"
openssl x509 -in server.crt -noout -subject -ext subjectAltName | sed 's/^/    /'

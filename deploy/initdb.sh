#!/bin/bash
# ============================================================================
# 按**版本号数字顺序**执行 sql/V*.sql
#
# 为什么不用 docker-entrypoint-initdb.d 的默认行为：
#   官方镜像只是按 glob 的**字典序**执行 *.sql，于是
#       V1, V10, V11, V12, V13, V2, V3, ...
#   V10 会在 V2 之前跑，直接报 "Table 'mall.t_after_sale' doesn't exist"。
#   V1..V9 时字典序恰好等于数字序，所以这个问题一直到加了 V10 才暴露
#   （正是部署测试抓到的：容器里 t_order 都不存在）。
#
# 用法：本文件被挂到 /docker-entrypoint-initdb.d/ 下（只挂这一个，不挂整个 sql 目录），
#       由 mysql 镜像的 entrypoint 在初始化时执行。
# ============================================================================
set -u

SQL_DIR="${SQL_DIR:-/opt/mall-sql}"
MYSQL_PWD_ARG="${MYSQL_ROOT_PASSWORD:-}"

# sort -V：把 V2 排在 V10 前面
files=$(ls "$SQL_DIR"/V*.sql 2>/dev/null | sort -V)
if [ -z "$files" ]; then
    echo "[initdb] no migration files under $SQL_DIR"
    return 0 2>/dev/null || exit 0
fi

for f in $files; do
    echo "[initdb] applying $(basename "$f")"
    if ! mysql -uroot -p"$MYSQL_PWD_ARG" --default-character-set=utf8mb4 < "$f"; then
        echo "[initdb] FAILED: $(basename "$f")"
        return 1 2>/dev/null || exit 1
    fi
done

echo "[initdb] all migrations applied ($(echo "$files" | wc -l) files)"

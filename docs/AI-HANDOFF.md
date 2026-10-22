# mall 商城项目 · AI 交接文档

> 目的：让**任何接手的 AI**（或人）在不问任何问题的情况下，能继续开发、构建、运行、验证这个项目。
> 最后更新：2026-10-16

---

## 1. 项目是什么

一个**自研的电商商城**（不是套壳开源项目）。范围是 MVP 完整购物闭环：
用户 → 商品 → 购物车 → 下单 → 模拟支付，外加管理后台。

- **代码位置**：`D:\projects\mall`（Windows 路径） = `/mnt/d/projects/mall`（WSL 路径）
- 技术栈：Spring Boot 3.2.5 / Java 17 / MyBatis-Plus 3.5.7 / MySQL 8 / Knife4j 4.5.0 / jjwt 0.12.5
  （**没有 Redis**：原先引了 starter 但零使用点，P6 已把依赖删掉，见 §7「P1 超时关单 / Redis」）
- 前端（尚未开始）：Vue3 + Vite + TypeScript + pnpm，管理后台用 Element Plus，H5 用 Vant

---

## 2. 运行环境（**先读这节，否则会白折腾**）

### 2.1 拓扑

```
Windows 11（主机名 Administrator）
├── IntelliJ IDEA 2026.2.1      <- 人在用，别乱杀进程
├── 本地 MySQL 8 (3306)；**不需要 Redis**（原先只引了 starter、零使用点，P6 已把依赖移除；本地也从来没装过）
├── Windows 工具链（JDK / Maven / Node）
└── WSL2 (Ubuntu 26.04)          <- OpenClaw Gateway 跑在这里
    └── 通过 OpenClaw Windows 节点 "Windows Node (ADINISTRATOR)" 操作 Windows 侧
```

**要点**：代码在 WSL 里可以直接用 `/mnt/d/...` 读写；但**构建和运行必须在 Windows 侧执行**（WSL 里没有 java/maven/node）。

### 2.2 Windows 工具链绝对路径

| 工具 | 路径 |
|---|---|
| JDK 17 | `D:\jdks\jdk-17.0.12` |
| Maven 3.9.16 | `D:\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd` |
| Node 26.7.0 | `C:\nvm4w\nodejs\node.exe`（nvm4w） |
| pnpm 11.9.0 | 经 npx 调用 |
| IntelliJ IDEA | `D:\jetbrains\IntelliJ IDEA 2026.2.1` |

**已知坑**：Windows 的 `JAVA_HOME` 环境变量指向 `D:\jdks`（缺了 `\bin` 层级），直接跑 `mvn` 会报 “JAVA_HOME is not defined correctly”。
→ **解决办法**：所有脚本里显式 `set "JAVA_HOME=D:\jdks\jdk-17.0.12"`。项目里的 `build.bat` 已经这么做了。

### 2.3 从 WSL 调用 Windows 命令

WSL interop 是**开启**的，但 `cmd.exe` / `powershell.exe` **不在 PATH 上**，必须用绝对路径：

```bash
cd /mnt/c/Windows/System32 && ./cmd.exe /c "D:\projects\mall\build.bat"
/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\path\to\script.ps1"
```

**三条铁律**（都是踩过的坑，**仅对 WSL 侧调用有效**；Windows 侧 pwsh/PS 脚本不受影响）：

1. **不要内联 Windows 路径当参数** —— bash 会把反斜杠吃掉，`D:\jdks\bin\java.exe` 会变成 `D:jdksbinjava.exe`。
2. **不要用 `powershell -Command "..."` 内联** —— 引号会被 cmd 吞掉。**一律写成 `.ps1` 文件再 `-File` 执行。**
3. 输出里的中文可能是 `????`（控制台管道编码）——**判断接口返回要落盘成文件再读**，不要信控制台。

### 2.4 执行超时

`exec` 工具有约 **10 秒** 的墙钟预算（**WSL 侧工具的限制**；Windows 侧 pwsh 没有这个限制）。耗时命令（构建、启动服务）要 **后台运行 + 轮询**，或用 `background: true` 起、再 `process poll` 收。

> Windows 侧还有一个同类现象：**任何留下长驻子进程（java）的命令都不会「返回」**——子进程继承了 stdout 管道，父 shell 等不到 EOF，最终被上层超时杀掉（连服务一起杀）。所以自动化脚本要用 `Start-Process -RedirectStandardOutput/-RedirectStandardError` 起服务，别用 `cmd /c run.bat`（见 §8.14）。

---

## 3. 目录与模块

```
D:\projects\mall\
├── build.bat               构建（内含 JAVA_HOME）
├── run.bat                 启动前后台两个服务
├── stop.bat                停止（调用 scripts\stop-apps.ps1）
├── build.log               最近一次构建日志（**由调用方重定向写入**；直接跑 build.bat 不会更新它）
├── README.md               项目总览 / 两种启动方式 / 默认账号 / 关键坑
├── docker-compose.yml      一键起全栈（mysql + 两个后端 + 两个前端）
├── frontend\
│   ├── admin-web\          管理后台（Vue3 + Vite + TS + Element Plus），dev :5173，/api 代理到 mall-admin:8081
│   └── portal-web\         H5 商城（Vue3 + Vite + TS + Vant 4），dev :5174，/api 代理到 mall-portal:8090
├── backend\                 Maven 多模块
│   ├── pom.xml                   父 POM（Spring Boot 3.2.5 / Java 17）
│   ├── mall-common\              通用：统一返回 R / 错误码 / 业务异常 / 全局异常 / JWT 工具
│   ├── mall-core\                领域：实体 / Mapper / Service / 安全配置
│   ├── mall-admin\               后台管理接口   :8081
│   └── mall-portal\              前台商城接口   :8090
├── sql\
│   ├── V1__schema.sql            建库建表（8 张表）
│   ├── V2__add_user_role.sql     t_user 加 role 列
│   ├── V3__seed_demo.sql         演示数据（分类/商品/SKU + 后台账号），幂等可重复执行
│   ├── V4__order_index.sql       t_order 加 idx_status_expire（超时关单扫描用），幂等
│   └── V5__order_request_id.sql  t_order 加 request_id 列与 uk_user_request（下单幂等），幂等
├── sqlschema.sql            ⚠️ 初版建表脚本（无 role 列），已被 sql\ 取代，勿用
├── scripts\
│   ├── e2e-lib.ps1               验证公用库（Http-Json / Check / Invoke-Mysql / Start-MallApps / Save-Summary），被 p2/p3 脚本 dot-source
│   ├── stop-apps.ps1             停止脚本（PID 文件 → 命令行 → 端口 → 日志 PID，四重兜底）
│   ├── p2-verify.ps1             P2 商品域端到端验证（36 项断言）
│   ├── p3-verify.ps1             P3 购物车+订单端到端验证（61 项断言，含并发防超卖与超时关单）
│   ├── idempotency-verify.ps1    下单幂等的真并发验证（22 项断言：同/异 requestId 并发、重试、畸形体）
│   ├── timeout-job-verify.ps1    超时关单验证（18 项断言：单应用能关 + 两应用只关一次）
│   ├── security-verify.ps1       生产加固验证（17 项断言：traceId / CORS 白名单 / 登录限流）
│   ├── security-data-verify.ps1  敏感数据安全验证（34 项断言：密码策略 / PII 静态加密 / 往返 / 日志 / 指标）
│   ├── auth-cookie-verify.ps1    登录态验证（27 项断言：HttpOnly / SameSite / CSRF 双提交 / 退出清理 / 两应用隔离）
│   ├── crypto-rotate-verify.ps1  密钥轮换演练（28 项断言：换密钥→旧数据仍可读→重加密→去掉旧密钥仍可读→还原）
│   ├── token-lifecycle-verify.ps1 令牌生命周期（26 项断言：access 真过期 / refresh 轮换 / 重放失败 / 登出吊销）
│   ├── deploy-verify.sh          部署验证（36 项：容器/迁移/prod 行为/Cookie/nginx/端到端；在容器栈上跑）
│   ├── deploy-browser-check.mjs  部署后浏览器冒烟（8 项：UI 登录 + Secure Cookie 是否被浏览器接受）
│   ├── backup-db.ps1             数据库逻辑备份（mysqldump 一致性快照，保留最近 N 份）
│   ├── restore-db.ps1            从备份恢复（默认恢复到临时库，覆盖 mall 必须 -Force）
│   ├── backup-restore-verify.ps1 备份恢复**演练**（13 项断言：备份→恢复→逐表比对→清理）
│   ├── metrics-verify.ps1        监控验证（16 项断言：prometheus 端点 + 业务计数器真的会动）
│   ├── admin-crud-verify.mjs     后台分类/商品的 **UI** CRUD 验证（16 项断言）
│   ├── after-sale-verify.ps1     售后 + 退款（57 项断言：状态机/渠道退款/重试幂等/失败路径/状态收敛/越权）
│   ├── payment-verify.ps1        支付链路（39 项断言：支付单/渠道/幂等/回调验签/查单兜底/越权）
│   ├── reconcile-verify.ps1      对账（35 项断言：支付+退款差异归类 / 差异处理审计 / 越权）
│   ├── https-verify.sh           HTTPS（18 项断言：证书链 / 301 / TLS 策略 / 安全头 / 真实业务）
│   ├── cdp.mjs                   前端检查的公用 CDP 驱动（起无头 Chrome / 求值 / 点击 / 填表 / 抓告警）
│   ├── web-check.mjs             前端「页面渲染」检查（15 项断言 + 控制台告警零容忍）
│   └── web-flow.mjs              前端「全流程」检查（真点按钮跑完整购物闭环，29 项断言）
├── logs\                    admin.log / portal.log / *.pid / *.err.log
├── tmp\                     临时验证产物（tmp\p2\ / tmp\p3\ / tmp\p5\ 是阶段证据，tmp\p3fix\ 与 tmp\admin-web\ 是补做验证，tmp\web\ 是前端渲染检查报告）
└── docs\                    本文档
```

**端口**：
- `mall-admin` → **8081**
- `mall-portal` → **8090**（原定 8080，但被使用者从 IDEA 启动的进程占用，故让开。**不要改回 8080**，除非先确认那个进程已停。）

---

## 4. 构建 / 运行 / 停止

```bash
# 构建（在 WSL 里发起，实际在 Windows 上跑）
cd /mnt/c/Windows/System32 && ./cmd.exe /c "D:\projects\mall\build.bat" clean install

# 运行（两个服务一起起）
cd /mnt/c/Windows/System32 && ./cmd.exe /c "D:\projects\mall\run.bat"

# 停止
cd /mnt/c/Windows/System32 && ./cmd.exe /c "D:\projects\mall\stop.bat"
```

**顺序铁律**：**先 `stop.bat`，再 `build.bat`**。
原因：Windows 下正在运行的 java 进程会锁住 `mall-*.jar`，`mvn clean` 删不掉会直接 **BUILD FAILURE**（报错是 `Failed to delete ...\mall-admin-1.0.0.jar`）。

**验证服务是否起来**（看日志文件，别猜）：
```bash
grep -hE "Started Mall(Admin|Portal)Application" /mnt/d/projects/mall/logs/*.log
```

### 4.1 前端（admin-web 管理后台 / portal-web H5 商城）

两个前端同构：Vue3 + Vite + TS + pnpm，命令与坑完全一样，只有 UI 库、端口和代理目标不同。

| 目录 | 用途 | dev 端口 | UI 库 | `/api` 代理到 |
|---|---|---|---|---|
| `frontend\admin-web` | 管理后台（分类/商品/订单） | **5173** | Element Plus | `mall-admin:8081` |
| `frontend\portal-web` | H5 商城（浏览/购物车/下单/我的） | **5174** | Vant 4 | `mall-portal:8090` |

```bash
cd D:\projects\mall\frontend\admin-web    # 或 frontend\portal-web
pnpm install      # 首次
pnpm dev          # 见上表端口
pnpm build        # 产物在 dist\
pnpm typecheck    # vue-tsc 类型检查（vite build 本身不做类型检查）
```

要点：

- **不需要动后端 CORS**：走 vite 代理，浏览器只访问 5173 / 5174。
- **pnpm 11 默认拦截依赖的安装脚本**，两个工程都在自己的 `pnpm-workspace.yaml` 里写了 `allowBuilds: esbuild: true`；
  少了它 `pnpm install` 会以 `ERR_PNPM_IGNORED_BUILDS` 失败（`pnpm` 字段写在 package.json 里**已不生效**，pnpm 11 起只读 pnpm-workspace.yaml）。
- 后台账号 `malladmin / admin123456`（登录页已预填）；H5 没有预置账号，**先注册**即可。
- 登录态在 **HttpOnly Cookie**（admin 用 `mall_admin_token`，H5 用 `mall_portal_token`）；localStorage 只存展示用的用户信息；都是 hash 路由。
- 两个 dev server 都只监听本机；生产部署 `pnpm build` 产物丢任意静态服务器即可（hash 路由，不用配 rewrite）。
- **端口被别的项目占着时**：`node scripts\web-check.mjs` / `web-flow.mjs` 的地址可用环境变量覆盖
  （`ADMIN_WEB_URL` / `PORTAL_WEB_URL`），例如本机 5173 曾被另一项目占用，就把我们的后台起在 5175
  并 `$env:ADMIN_WEB_URL='http://localhost:5175'` 再跑脚本。dev 代理已经会去掉 `Origin`（见 §8 第 39 条），
  所以换端口不需要动后端 CORS 白名单。
- **UI 库是按需注册的**：两个 `src/main.ts` 里各有一份显式组件清单。新页面用到**新**组件时必须往清单里补一行，
  否则组件不注册（症状是页面少一块 + 控制台一条 `Failed to resolve component`）。`node scripts\web-check.mjs`
  的告警零容忍会抓住这类漏注册。

### 4.2 构建、单元测试与 CI

`build.bat` **默认会跑单元测试**（纯内存，不需要数据库、大概 3 秒）。要跳过就显式传参：

```bash
cmd.exe /c "D:\projects\mall\build.bat" clean install              # 构建 + 单元测试
cmd.exe /c "D:\projects\mall\build.bat" clean install -DskipTests  # 跳过测试
```

单跑测试（服务在跑也不影响，它不碰 jar）：

```bash
cd D:\projects\mall\backend
mvn -B test
```

现有单元测试（**62 项**，覆盖「错了会造成钱/库存问题」的分支）：

| 测试类 | 覆盖 |
|---|---|
| `mall-common` `JwtUtilTest`（5） | 签发/解析往返、换密钥解不开、过期与篡改被拒、过短密钥直接报错 |
| `mall-common` `GlobalExceptionHandlerTest`（5） | 业务异常保留自己的码；**畸形请求体/缺参数 → 400 而不是 500**；方法不支持 → 真 405；未知异常 → 500 |
| `mall-core` `ProductionConfigGuardTest`（12） | prod 下拒绝开发默认密钥 / 未解析占位符 / 空值 / 长度不足 / **CORS 通配**；dev 不受影响 |
| `mall-core` `LoginThrottleTest`（7） | 未达上限放行、达上限拒绝（带剩余秒数）、成功清零、不同用户名互不影响、窗口过期自动解锁且计数从 1 重新开始 |
| `mall-core` `UserServiceImplTest`（5） | 被限流时**不查库不跑 BCrypt**、用户名不存在与密码错误返回同样的码、失败计数、成功清零、禁用账号不清计数 |
| `mall-core` `TraceIdFilterTest`（4） | 生成 16 位 hex 并写入 MDC/响应头、请求结束清理 MDC、沿用上游值、可疑/超长值被替换 |
| `mall-core` `OrderServiceImplTest`（24） | 下单：空车 2004、无地址 2010、下架 2002、锁库存失败 2003、正常下单的金额/地址快照/清购物车且**不计销量**、**幂等键命中直接返回原单且不动库存**、requestId 过长 400；**立即购买：缺 skuId/数量非法 400、SKU 不存在 2007、下架 2002、锁库存失败 2003、正常下单且断言购物车零交互、幂等键命中**；支付：超时拒付、状态被抢先流转时幂等拒绝、成功扣库存且**同商品多 SKU 销量合并**、扣库存失败 2003；取消释放锁定库存、重复取消被拒、越权看到 3001、超时关单跳过已被别人处理的单 |

CI：`.github/workflows/ci.yml`，三个 job —— 后端编译+单测、**在全新 MySQL 上按顺序跑 V1..V4**、两个前端 typecheck+build。
⚠️ **本机没有 GitHub Actions 环境，这个工作流没在 CI 上实际跑过**，但其中每条命令都在本机验证过。
端到端四套（p2/p3/web-check/web-flow）目前依赖 Windows 专用路径与本地已启动的服务，**还没搬进 CI**，属于待办。

### 4.3 配置与上线（prod profile）

敏感配置一律走环境变量，**代码和 compose 里不留真实值**：

| 环境变量 | 说明 | 开发默认值 |
|---|---|---|
| `MALL_JWT_SECRET` | JWT 签名密钥，**两个后端必须一致** | `mall-dev-only-INSECURE-…`（明确标了不安全） |
| `MALL_CRYPTO_SECRET` | PII 字段加密主密钥（AES-256-GCM）。**两个后端必须一致、且必须与 `MALL_JWT_SECRET` 不同**；⚠️ 上线后不可随意更换 | `mall-dev-only-INSECURE-crypto-secret-…`（prod 自检会拦） |
| `MALL_COOKIE_SECURE` | 登录态 Cookie 是否只在 HTTPS 下发送。**prod 必须 true**（自检会拦 false），dev 必须 false（http 下 Secure Cookie 不会下发） | false |
| `MALL_JWT_ACCESS_EXPIRE_MILLIS` | access token（JWT）有效期。默认 15 分钟；无状态不可吊销，所以别调大 | 900000 |
| `MALL_JWT_REFRESH_EXPIRE_MILLIS` | refresh token 有效期。默认 7 天，可吊销 | 604800000 |
| `MALL_CRYPTO_PREVIOUS_SECRETS` | 轮换过渡期**仅用于解密**的历史密钥（逗号分隔）。重加密完成后必须清空 | 空 |
| `MALL_CRYPTO_RE_ENCRYPT_ON_STARTUP` | 一次性开关：启动时把仍在历史密钥下的数据重加密成当前密钥 | false |
| `MALL_DB_URL` / `MALL_DB_USERNAME` / `MALL_DB_PASSWORD` | 数据库连接 | localhost / root / `521131` |
| `MALL_ORDER_EXPIRE_MINUTES` | 未支付订单有效期 | 30 |
| `MALL_ORDER_TIMEOUT_JOB_ENABLED` / `_INTERVAL_MILLIS` | 超时关单任务开关与间隔 | **两个应用都是 true**；10 秒 |
| `MALL_CORS_ALLOWED_ORIGINS` | 允许跨域的来源（逗号分隔）。**绝不能含 `*`**（prod 自检会拦） | 两个前端的 dev 地址 |
| `MALL_LOGIN_MAX_FAILURES` / `MALL_LOGIN_LOCK_SECONDS` | 登录失败限流：窗口内失败几次后锁多久 | 5 次 / 300 秒 |
| `SERVER_PORT` | 服务端口 | 8081 / 8090 |

上线方式：`--spring.profiles.active=prod`（或 `SPRING_PROFILES_ACTIVE=prod`）。prod 下：

- 关闭 springdoc / knife4j 接口文档与 `/actuator/info`，只留 `health`
- 启动时执行 `com.mall.core.config.ProductionConfigGuard` 自检：JWT 密钥必须**非空、不是未解析的占位符、不是开发默认值、长度 ≥ 32**，
  数据库口令必须非空且不是未解析占位符；不满足**直接启动失败**（不会带着弱密钥跑起来）

> ⚠️ 这个自检不是多余的：Spring Boot 在 `@ConfigurationProperties` 绑定路径上会把**解析不了的占位符原样保留**（不报错），
> 光靠 YAML 写 `${MALL_JWT_SECRET}` 是拦不住的 —— 详见 §8 第 26 条。

---

## 5. 数据库

- MySQL 8，服务名 **MySQL80**，监听 **3306**
- 库名 **`mall`**
- 账号 **`root`**，口令走环境变量 `MALL_DB_PASSWORD`（开发默认值 `521131`，见 §4.3）；
  **两个应用必须用同一个口令**，生产由 `ProductionConfigGuard` 在启动时兜底校验
- 客户端：`C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe`

表（8 张）：`t_user`、`t_address`、`t_category`、`t_product`、`t_sku`、`t_cart`、`t_order`、`t_order_item`

所有表统一带：`create_time` / `update_time` / `deleted`（逻辑删除，0 正常 1 删除）。

**`t_user.role`**：0 = 普通用户，1 = 管理员。后台登录要求 role=1。

**P3 相关列**（`V1__schema.sql` 里已包含，无需额外迁移）：

- `t_sku.stock`（总库存）/ `t_sku.locked_stock`（下单锁定，支付或关单后释放）→ **可售 = stock - locked_stock**
- `t_order.pay_time`（支付时间）、`t_order.expire_time`（未支付订单的关单时间点 = 下单时间 + `mall.order.expire-minutes`）
- `t_product.sales`：**只有支付成功才累加**（下单锁库存不算成交），前台 `sales_desc` / 综合排序依赖它
- `t_order.order_no` 有唯一键 `uk_order_no`，插入时撞号会换号重试（最多 3 次）

**演示数据 / 后台账号**：`sql\V3__seed_demo.sql`（幂等，可反复执行）

- 分类：1 数码（顶级）、2 手机、3 笔记本（挂在 1 下）、4 服饰（顶级）、5 男装（挂在 4 下）
- 商品：1 iPhone 15 Pro、2 小米 14、3 ThinkPad X1 Carbon、4 男士休闲夹克（均上架）、5 下架测试机（status=0）
- SKU：每个商品 1~2 个；**库存只记在 SKU 上**，商品列表的 `stock` 是 SKU 合计
- 后台账号：**malladmin / admin123456**（role=1，BCrypt 哈希写在 SQL 里）

```bash
mysql -uroot -p*** -N --default-character-set=utf8mb4 -e "source D:/projects/mall/sql/V3__seed_demo.sql"
```

> 换库重建的顺序：`V1__schema.sql` → `V2__add_user_role.sql` → `V3__seed_demo.sql` → `V4__order_index.sql` → `V5__order_request_id.sql`。
> 文件名统一 `V<N>__` 前缀就是为了这个顺序——`docker-entrypoint-initdb.d` 按**文件名**排序执行，
> 而 ASCII 里大写 `V` 排在小写 `s` 前面，所以叫 `schema.sql` 会被排到最后、建表顺序就反了。
> 根目录的 `sqlschema.sql` 是初版（缺 role 列），**不要再用**。

---

## 6. 代码约定（改代码前必读）

### 6.1 统一返回

所有接口返回 `R<T>`：

```json
{ "code": 200, "message": "操作成功", "data": ... }
```

- **成功**：`code = 200`
- **业务失败**：`code` 为业务码（见 `ResultCode`），**HTTP 状态码仍是 200**，错误码在 body 里
- **未认证**：真正的 **HTTP 401**（由 Spring Security 抛出，**body 为空**——断言时别只看 body）
- **路径不存在 / 方法不支持**：真正的 **HTTP 404 / 405**（`GlobalExceptionHandler` 里已单独处理，body 里同样带 code）

> ⚠️ 写断言/测试时：**只看 HTTP 状态码会误判**。业务失败要读 body 里的 `code`。

常用业务码：1001 用户名已存在 / 1003 密码错误 / 2003 库存不足 / 3001 订单不存在。

### 6.2 分层

- **实体**继承 `com.mall.core.base.BaseEntity`（id / createTime / updateTime / deleted）
- **Mapper** 继承 MyBatis-Plus `BaseMapper<T>`，放在 `com.mall.core.mapper`（该包被 `@MapperScan` 扫描）
- **Service** 接口 + `impl` 放 `com.mall.core.service`（**共用**，前台后台都能调）
- **Controller** 按应用分：`com.mall.portal.controller` / `com.mall.admin.controller`
- **DTO / VO** 放 `com.mall.core.model.dto` / `.vo`
- 启动类用 `@SpringBootApplication(scanBasePackages = "com.mall")` —— **少了这个扫描不到 common/core 的 Bean**

### 6.3 鉴权

- JWT：配置项 `mall.jwt.secret` / `mall.jwt.expire-millis`
- 请求头 `Authorization: Bearer <token>`
- Claims：`sub` = userId，`role`，`username`
- 取当前用户：`UserContext.requireUserId()`（未登录抛 401）
- 路径规则（见 `com.mall.core.config.SecurityConfig`）：
  - 白名单：`/api/v1/portal/auth/**`、`/api/v1/admin/auth/**`、swagger/knife4j/actuator
  - `/api/v1/admin/**` → 需要 `ROLE_ADMIN`
  - `/api/**` → 需要登录
- 密码用 BCrypt 存储

### 6.4 接口文档

Knife4j：服务起来后访问 `http://localhost:<port>/doc.html`

### 6.5 商品域约定（P2）

- **模型**：`t_product` 是 SPU，`t_sku` 是 SKU，**库存只在 SKU 上**（`stock` 可售 / `locked_stock` 预留给 P3 下单锁定）。
  商品列表返回的 `stock` 是「该商品所有 SKU 可售库存合计」。
- **图集**：`t_product.images` 落库是**英文逗号分隔的 URL 串**，接口收发都是 `List<String>`。
- **分类**：`parent_id = 0` 为顶级；`status` 1 正常 / 0 禁用。前台分类树只返回 `status=1`。
  按分类筛商品会**自动带上所有后代分类**（选「数码」能搜到「手机」「笔记本」下的商品）。
- **前台列表排序** `sort`：`default`(综合=销量↓) / `price_asc` / `price_desc` / `sales_desc` / `new`。
- **SKU 更新语义**（`PUT /api/v1/admin/product/{id}`）：按 id 对齐 —— 传了 id 就更新、没 id 就新增、原有但没出现的**逻辑删除**。
- **删除语义**：商品删除 = 商品 + 其 SKU 逻辑删除；分类只有在「没有子分类且没有商品」时才允许删除（否则 2006）。
- **浏览类接口匿名开放**：`SecurityConfig` 里 `GET /api/v1/portal/category/**`、`GET /api/v1/portal/product/**` 是 `permitAll`，
  其余 `/api/**` 仍需登录，`/api/v1/admin/**` 仍需 `ROLE_ADMIN`。POST/PUT/DELETE 到这些路径**依然要登录**。
- **分页**：`pageSize` 上限 100（服务端 clamp），MyBatis-Plus 分页拦截器 `maxLimit=500`。

### 6.6 购物车 / 订单域约定（P3）

- **购物车**：`t_cart` 上有唯一键 `uk_user_sku (user_id, sku_id, deleted)`，**加购同一 SKU 是累加数量而不是新增行**（累加后超过可售库存 → 2003）。
  `checked` 0/1 控制选中；**下单只结算选中项**，下单成功后把已结算的选中条目删掉（未选中的保留）。
- **库存三态流转**（`SkuMapper` 里的条件 UPDATE，全部在数据库层原子完成）：
  1. 下单 `lockStock`：`locked_stock += qty`，条件 `stock - locked_stock >= qty`，返回 0 → 2003 并**回滚整单**（防超卖的关键）
  2. 支付 `deductStock`：`stock -= qty` 且 `locked_stock -= qty`（真正扣减）
  3. 取消 / 超时关单 / 后台关闭 `releaseStock`：只减 `locked_stock`（`GREATEST(...,0)` 兜底），总库存不动
- **状态机**：`0 待支付 →(pay) 1 已支付 →(后台 ship) 2 已发货 →(confirm) 3 已完成`；`0 →(用户 cancel / 超时任务 / 后台 close) 4 已关闭`。
  流转一律用**「带 from 状态的 UPDATE」**实现（`OrderServiceImpl.transition`），影响行数为 0 即返回 **3002**，并发下不会重复流转。
- **超时关单**：`OrderTimeoutTask` 用 `@ConditionalOnProperty` 挂在 `mall.order.timeout-job.enabled=true` 的应用上（**只有 mall-admin 开**，避免两个应用重复扫），间隔 `mall.order.timeout-job.interval-millis`。
  只扫 `status=0 AND expire_time <= NOW()`，走 `idx_status_expire` 索引。
- **支付前置校验**：`pay()` 会先看 `expire_time`，已过期的订单直接 **3002「订单已超时，无法支付」**，不让定时任务没扫到的空档里支付成功。
- **销量**：只有**支付成功**才 `t_product.sales += qty`（同一商品多个 SKU 先合并数量再累加）；创建订单只是锁库存，不计销量。
- **订单号**：`yyyyMMddHHmmss` + 6 位随机数；撞 `uk_order_no` 时换号重试（最多 3 次），失败才报 500（`FAILED`）。
- **地址**：`CreateOrderDTO.addressId` 可不传 → 用默认地址；**没有默认地址 → 2010**。下单时把收货信息**快照**进 `t_order`。
- **常用 P3 业务码**：2004 购物车为空 / 2009 购物车条目不存在 / 2010 请先设置收货地址 / 3001 订单不存在 / 3002 当前状态不允许该操作。

---

## 7. 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 脚手架：多模块 / 父 POM / 公共组件 / 建表 / 服务能启动 | ✅ 完成并验证 |
| P1 | 用户域：注册 / 登录 / JWT / 当前用户 / 收货地址 CRUD / 后台登录角色门禁 | ✅ 完成并验证 |
| P2 | 商品域：分类树 / SPU / SKU / 列表 / 详情 / 搜索 / 后台商品管理 | ✅ 完成并验证（35/35） |
| P3 | 购物车 + 订单：下单 / 库存扣减（防超卖）/ 订单状态机 / 超时关单 | ✅ 完成并验证（61/61） |
| P4 | 前端：管理后台（Element Plus）+ H5 商城（Vant） | ✅ 两个工程均完成（构建/类型检查/接口冒烟全通过） |
| P5 | 收尾：接口文档 / README / Docker Compose / 演示数据 | ✅ 完成（Docker 部分本机无 Docker，只做了离线校验） |
| P6 | 上线准备：版本控制 / 密钥外部化 / 单元测试 / CI / 生产加固 | 🟡 进行中 |

### P1 验证结果（可作为回归基线）

```
POST /api/v1/portal/auth/register                       -> code 200
POST /api/v1/portal/auth/login                          -> code 200, 返回 token
GET  /api/v1/portal/user/me   (带 token)                -> code 200
GET  /api/v1/portal/user/me   (不带 token)              -> HTTP 401
POST /api/v1/portal/address                             -> code 200
GET  /api/v1/portal/address                             -> code 200, 返回列表
POST /api/v1/admin/auth/login (普通用户)                -> code 403 "非管理员账号，无法登录后台"
GET  /api/v1/admin/ping       (匿名)                    -> HTTP 401
```

### P2 验证结果（35/35 全绿，可回归）

一键跑（脚本自己 stop → build → run → seed → 35 项断言 → 汇总）：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\projects\mall\scripts\p2-verify.ps1"
# 参数：-SkipBuild 跳过构建 / -SkipRestart 只对已启动的服务跑断言 / -SkipSeed / -SkipCleanup
```

证据落在 `tmp\p2\*.json`（每个请求的响应体）与 `tmp\p2\summary.txt`。覆盖：

- **前台（匿名，不带 token）**
  - `GET /api/v1/portal/category/tree`：只返回启用分类，第一版是嵌套树（含 `children`）
  - `GET /api/v1/portal/product/page`：只返回上架商品；`categoryId`（自动聚合后代分类）、`keyword`（搜 name/sub_title）、`minPrice`/`maxPrice`、`sort=price_asc|price_desc|sales_desc|new`
  - `GET /api/v1/portal/product/{id}`：详情含 SKU 列表与图集数组；不存在 → `2001`；已下架 → `2002`
- **后台（需 role=1 的 token）**
  - 分类：`tree`/`POST`/`PUT /{id}`/`PUT /{id}/status`/`DELETE /{id}`；有子分类或商品时删除被拒（`2006`）；同级同名（`2008`）；父分类不能是自己或自己的子分类
  - 商品：`page`（含下架、可按 status 筛）/`GET /{id}`/`POST`/`PUT /{id}`/`PUT /{id}/status`/`DELETE /{id}`
    - 一次提交 SPU + 2 个 SKU；更新时 SKU 按 id 对齐（改一个 / 增一个 / 删一个）
    - 下架后前台详情 `2002`、前台列表不含；删除后前台 `2001`、后台列表 0 条
  - 参数校验：缺 name → `400`；分类不存在 → `2005`；SKU 列表为空 → `400`
  - 库存汇总：新商品 2 个 SKU（8 + 3）在前台列表显示 `stock=11`
- **鉴权 / 协议边界**：匿名打 `/api/v1/admin/**` → HTTP 401；`GET` 打 POST 接口 → HTTP 405 + code 405；不存在的路径 → HTTP 404 + code 404

### P3 验证结果（61/61 全绿，可回归）

一键跑（脚本自己 stop → build → run → **61 项断言** → 汇总）：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\projects\mall\scripts\p3-verify.ps1"
# 参数：-SkipBuild / -SkipRestart / -SkipCleanup / -TimeoutWaitSeconds 40
```

证据落在 `tmp\p3\*.json` 与 `tmp\p3\summary.txt`。覆盖：

- **购物车**：空车下单 → 2004；同一 SKU 加购累加不新增行；改数量；选中/取消选中；item 归属校验（别人的条目 → 2009）；
  缺必填参数 → 400（不是 500）；匿名访问 → HTTP 401
- **下单**：结算选中项（A×2 + B×1 = 250.00，状态待支付，返回 `orderNo` + `expireTime`）；
  只清选中项、未选中的保留；订单详情含明细 + **收货信息快照**（中文正常）；无地址 → 2010
- **库存与防超卖**：下单只锁不改总库存；商品列表 `stock` 显示可售合计；
  **真并发**（两个用户同时抢库存=1 的 SKU）→ 恰好一单成功、另一单 2003；库存锁完后再下单 → 2003
- **状态机**：支付（真正扣减 stock 并释放 locked、写 `pay_time`）；重复支付 / 已支付后取消 / 未发货就确认收货 / 重复发货 / 重复取消 → 均 3002
- **后台**：订单分页 + status 筛选 + 订单号搜索 + 详情；`ship`（1→2）、`close`（0→4 并释放库存）
- **超时关单**：把 `expire_time` 改成过去 → 等 admin 定时任务（≤40 秒）自动关单并释放锁定库存
- **越权 / 边界**：越权查/支付别人订单 → 3001；普通用户打后台 → HTTP 403；匿名打后台 → HTTP 401

### P3 收尾修复验证（8/8 全绿）

后续补的三处修复，用 `tmp\p3fix\fix-verify.ps1` 单独验证（脚本要求服务已在跑）：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\projects\mall\tmp\p3fix\fix-verify.ps1"
```

- 下单不计销量，**支付成功才 `sales += qty`**（pay 前 `sales=0`，付 2 件后 `sales=2`，同时 `stock` 8/`locked` 0）
- **已过 `expire_time` 的订单拒绝支付** → 3002「订单已超时，无法支付」，且不扣库存不加销量
- 订单号唯一（两单 `order_no` 不同）—— 撞号换号重试的兜底路径

### P4 前端验证

两个工程都是「`pnpm build` + `pnpm typecheck` 通过 + 起 dev server 走代理打真实后端冒烟」。
**注意：UI 的实际渲染没在浏览器里验过**（本机无无头浏览器），验到的是构建/类型/接口三层。

**管理后台** —— `tmp\admin-web\smoke.ps1`（8/8，结果在 `tmp\admin-web\smoke.txt`）：

- dev server 吐出 SPA 外壳；管理员登录 `code=200 role=1`（走 vite 代理 → 8081）
- 分类树 / 商品分页 / 商品详情（含 SKU）/ 订单分页 全部 `code=200`
- **协议边界**：密码错误是 HTTP 200 + `code=1003`；匿名打 `/api/v1/admin/**` 是真 HTTP 401

管理后台的页面：登录 / 分类管理 / 商品管理（列表 → **详情**、新增、编辑）/ 订单管理（列表 → 详情）。

> 商品详情页是后补的（`admin-web\src\views\ProductDetailView.vue`，路由 `/product/:id`，
> 数据来自 `GET /api/v1/admin/product/{id}`），单独验过 `tmp\admin-web\detail-smoke.ps1`（**9/9**，
> 见 `tmp\admin-web\detail-smoke.txt`）：dev server 能编译并吐出该组件、router 模块里确实注册了
> `productDetail`、详情接口返回图集与 SKU 三态库存（总/锁定/可售）、不存在的 id 返回 `2001` 时页面走空状态。
> 该页把 `detail` 的 HTML 标签剥掉当纯文本显示（**故意不用 `v-html`**，不给后台开 XSS 口子）。

**H5 商城** —— `tmp\portal-web\smoke.ps1`（15/15，结果在 `tmp\portal-web\smoke.txt`）：

- 匿名浏览：分类树、商品分页（能搜到新商品）、商品详情（含 SKU）—— 走 5174 代理 → 8090
- 买家流程：注册 → 登录 → `user/me` → 新增地址（默认）
- 加购 2 件 → 购物车 `size=1 qty=2 totalPrice=200.00 valid=true`
- 下单 `payAmount=200.00 status=0` → 支付 → 订单详情 `status=1 已支付`
- 我的订单分页 `total=1`
- **支付后商品 `sales=2`、SKU `stock=8 / locked=0`** —— H5 全链路顺带复验了 P3 的销量/库存修复
- 匿名访问购物车 → **HTTP 401**

> 脚本用专用测试商品（`H5 Test*` / `P3FIX*` 前缀）并在结尾清理，不污染演示数据。

**H5 的一处已知取舍（已在 P6 修掉）**：原先「立即购买」只能靠「先把该 SKU 加购 → 再把购物车其它条目取消选中 → 跳结算」
绕出来，**会覆盖用户原有的勾选**。P6 新增了 `POST /api/v1/portal/order/direct` 直连下单入口，见下方「P1 立即购买」。

### 前端真实渲染检查（`scripts\web-check.mjs`，14/14 + 0 告警）

⚠️ **`pnpm build` 和 `vue-tsc` 对前端的覆盖面很有限**——实测有 3 个 bug 它们全绿，只有真渲染才发现（见 §8 第 20~22 条）。
所以补了这个脚本：用本机 Chrome 的 DevTools Protocol 打开真实页面、必要时注入登录态、断言页面渲染出的文字，
并**把 Vue 运行时告警/控制台错误当失败**。

```bash
node scripts\web-check.mjs        # 需要：两个前端 dev server + 两个后端都在跑
# 报告：tmp\web\cdp-report.txt
```

覆盖：管理后台（商品列表 / **商品详情** / 分类管理 / 订单管理）、H5 匿名可看（首页 / 商品详情 / 不存在的商品走兜底 / 登录页）、
H5 登录后（购物车 / 确认订单 / 我的订单 / 收货地址 / 我的）、同路由换参数会重新加载。
最后一次结果：**15/15 PASS，控制台告警/错误 0 条**。

### 前端全流程交互测试（`scripts\web-flow.mjs`，29/29）

`web-check.mjs` 只断言「渲染出来没有」，证明不了「点了按钮业务真的跑通」。这个脚本**真的去点**，
把整条购物闭环跑一遍，最后再用 SQL + 接口双向核对落库状态：

```
注册 → 首页点商品卡片 → 详情页选规格 → 步进器加到 2 → 加入购物车 → 购物车(¥200.00)
     → 去结算 → 新增收货地址(6 个字段 + 设为默认) → 提交订单 → 去支付
     → 管理后台订单列表按单号找到该单 → 发货(过二次确认)
     → H5 订单详情 → 确认收货(过二次确认) → 已完成
```

```bash
node scripts\web-flow.mjs      # 需要：两个后端 + 两个前端 dev server + Chrome
# 报告：tmp\web\flow-report.txt
```

几处刻意做成「端到端」的断言（不只看 HTTP 200）：

- 购物车页面文本里真的出现商品名与 `合计:¥200.00`
- 下单后 SQL 核对 `stock=10, locked=2`（只锁不扣）
- 支付后 SQL 核对 `stock=8, locked=0` 且 `t_product.sales=2`
- 后台发货、H5 确认收货后都回查 `t_order.status` 变成 2 / 3
- 订单详情最终 `statusText=已完成`、`items=1`、收货人快照正确
- **整个流程 0 条控制台告警/错误**

脚本自带清理：**开跑前先删上次残留、跑完删本次数据**（`Web 流程测试*` / `webflow1` 前缀），
用独立测试商品，不碰演示数据，可反复执行。最后一次：**29/29 通过**。

### 上线准备（P6）进度

| 项 | 状态 | 说明 |
|---|---|---|
| 版本控制 | ✅ | `git init`（分支 `main`）+ `.gitignore` / `.gitattributes`；首次提交 180 个文件，`target/`、`node_modules/`、`dist/`、`logs/`、`tmp/` 均已排除 |
| 密钥 / 口令外部化 | ✅ | 见 §4.3；prod profile + `ProductionConfigGuard` 启动自检（验证 10/10，见 `tmp\p0\config-report.txt`） |
| 后端单元测试 | ✅ | 28 项，`build.bat` 默认执行，见 §4.2 |
| CI | ✅ | `.github/workflows/ci.yml`（3 个 job；**未在真实 CI 上跑过**） |
| 下单幂等（防重复订单/重复锁库存） | ✅ | 见下方「P1 下单幂等」；`scripts\idempotency-verify.ps1` 22/22 |
| 「立即购买」直连下单接口 | ✅ | 见下方「P1 立即购买」；`web-flow` 35/35 |
| 超时关单单点 / Redis 死依赖 | ✅ | 见下方「P1 超时关单 / Redis」；`timeout-job-verify.ps1` 18/18 |

### P1 立即购买（35/35）

**问题**：后端下单只认「购物车中已选中的条目」，所以老实现只能拿「先把该 SKU 加购 → 再把购物车其它条目取消勾选」
绕出来 —— **会破坏用户购物车里原有的勾选**（P4 时期就标为已知取舍）。

**解决**：新增直连下单入口，不读也不写购物车。

- 接口：`POST /api/v1/portal/order/direct`，入参 `CreateDirectOrderDTO { skuId, quantity, addressId, requestId }`
- 服务层把「建单」抽成共用的 `placeOrder(userId, requestId, address, lines)`（`OrderLine` 小 record），
  `create()`（购物车结算）与 `createDirect()`（立即购买）都调它 —— 两个入口的校验、原子锁库存、金额快照、幂等行为完全一致，
  差别只在「谁提供要结算的行」以及「要不要清购物车」
- 幂等键复用同一套机制（`(user_id, request_id)` 唯一键），所以直连下单同样能挡住重复提交
- H5：`ProductDetailView` 的「立即购买」不再加购，改为跳 `/checkout?productId=&skuId=&quantity=`；
  `CheckoutView` 识别到这三个参数就走直连语义（清单按 SKU 详情渲染，提交打 `/order/direct`）

**验证**：`web-flow.mjs` 里新增一段（整脚本 35/35）。关键在于**要能验出新老行为的差异**——
所以先把购物车弄成「有 1 件但**未勾选**」的状态，再走立即购买：

| 断言 | 结果 |
|---|---|
| 立即购买进入确认订单页（**购物车未勾选也能下单**） | PASS（老实现这里必然 2004 失败） |
| 立即购买订单金额 = 数量 × 单价（200.00，步进器 2 件） | PASS |
| 立即购买订单带幂等键 requestId | PASS |
| **立即购买没有破坏购物车原有的勾选**（仍是 `size=1 checked=0`） | PASS |

另有单元测试 7 项（`OrderServiceImplTest` 17 → 24）：缺 skuId / 数量非法 / SKU 不存在 / 商品下架 /
锁库存失败 / 正常下单**且断言 `cartMapper` 零交互** / 幂等键命中直接返回原单。

### P1 超时关单 / Redis（18/18）

**问题 1：超时关单单点。** `OrderTimeoutTask` 原先只在 mall-admin 开（portal 那边
`mall.order.timeout-job.enabled=false`）。admin 挂了或没启动，超时订单就**永远不关**、锁定库存**永远不释放**。

**解决**：两个应用都默认开启。**并发重复扫描是安全的**——关单走「带 from 状态的条件 UPDATE」，
只有把 status 从 0 改成 4 成功的那个实例才会去释放库存，另一个拿到 0 行直接跳过
（`OrderServiceImplTest#closeExpiredOrders_skipsOrdersSomeoneElseAlreadyHandled` 就是守这条）。
代价只是几次空扫描，比单点丢库存划算。真要单点跑，把另一边的 `enabled` 设成 false 即可。

**验证**：`scripts\timeout-job-verify.ps1`（**18/18**，产物 `tmp\p1-timeout\summary.txt`）。它自己停/起应用，分三段：

| 阶段 | 断言 |
|---|---|
| A 只起 portal（admin 停着） | 过期订单仍被 portal 关掉、锁定库存释放回 0、日志里恰好 1 次关闭 —— **这条是修复的核心**（改之前 portal 根本不跑这个任务） |
| B 两个应用同时扫 | 同一单仍然**只被关一次**（把两个日志里的 `closed N` 求和 == 1） |
| C 只起 admin（portal 停着） | 对称证明 admin 也在跑这个任务（订单先在两个应用都活着时建好，再停 portal、最后才让它过期） |

计数依据是日志行 `[timeout-job] closed N expired unpaid order(s)`：这行刻意保持 ASCII 且格式稳定，
因为它是唯一会被脚本解析的日志（见 §8 第 31 条）。

**问题 2：Redis 是死依赖。** 全项目零使用点，但 `mall-core` 引了 `spring-boot-starter-data-redis` + `commons-pool2`，
两个 yml 还配着 `spring.data.redis`。

**解决：删掉。** 没用到的依赖 = 多一份升级负担与攻击面，将来真要做缓存再带着目的加回来。
（本来考虑用 Redis 做超时关单的分布式锁，但订单状态的条件 UPDATE 已经保证了幂等，MySQL 也已经是硬依赖，
没必要为此引入一个中间件。）

### P2 前端错误兜底与包体（24/24）

**问题 1：两个前端都没有全局错误兜底。** 组件渲染抛异常时用户看到的是**整页白屏**，控制台只有一条 stack，
而 `pnpm build` / `vue-tsc` 完全发现不了。

**解决（分层兜底）**：

- `src/utils/errorHandler.ts`：`app.config.errorHandler`（Vue 组件树）+ `unhandledrejection`（忘了 catch 的 Promise）
  + `window.onerror`（只报 `e.error` 非空的，避免把资源加载失败/ResizeObserver 噪声当应用异常）。
  一律 `console.error`，并按错误去重（5s 内同一条只提示一次，避免渲染循环刷屏）。
- `src/components/AppErrorBoundary.vue`：`onErrorCaptured` 实现的错误边界，兜底 UI 带「刷新页面」按钮。
  返回 `false` 表示已处理 → Vue 不会再调 `errorHandler`，所以**一条错误只上报一次**。
  切路由时自动复位（否则出错后点导航也进不去新页面）。
- **放两层**：根 `App.vue` 一层（最后的安全网）+ **布局内容区**一层
  （`AdminLayout.vue` / `TabLayout.vue` 的 `router-view`）。
  只放根那一层的话，页面一抛异常**侧边栏/Tabbar 也会被兜底 UI 顶掉**，用户除了刷新无处可去；
  内容区那层接住页面异常后，用户还能点别的菜单切走。

**验证**（`web-check.mjs`，24/24，见 `tmp\web\cdp-report.txt`）：用 dev-only 的「故意抛错」页
`src/views/ErrorDemoView.vue`（路由挂在**布局的 children 下**，被 `import.meta.env.DEV` 包着，
生产构建整块 DCE，实测连 chunk 都不产出）：

```
[PASS] 管理后台 · 页面抛异常时显示兜底 UI 且保住侧边栏 :: len=84
[PASS] H5 · 页面抛异常时显示兜底 UI 且保住 Tabbar :: len=29
[PASS] 错误兜底：每个演示页恰好上报 1 次（边界返回 false -> 不会重复上报） :: count=2
```

> 这套检查顺带抓出两个真 bug（见 §8 第 33 条）：两个订单详情页把 `onMounted` 里异步 `load()` 的 reject
> 放了出去，于是「订单不存在」这种**正常业务结果**会被错误边界接住、整页变成「页面出错了」。
> 现在两个页面都自己 catch 并显示业务空状态，断言改成 `订单不存在时显示业务空状态`。
37. **批量改含中文的 `.ps1` 必须保持 UTF-8 BOM**：我用 PowerShell 做 `/api/` → `/api/v1/` 批量替换时，
    `WriteAllText` 用了**无 BOM** 的 `UTF8Encoding($false)`，把 5 个脚本的 BOM 全弄丢了 →
    PowerShell 5.1 按 GBK 解析、中文把后面的引号吞掉，报 `字符串缺少终止符` / `UnexpectedToken`
    （§8 第 11、28 条同一个坑的第三种踩法）。
    正确写法：读 `[System.Text.Encoding]::UTF8`，写 `New-Object System.Text.UTF8Encoding($true)`。
    改完**立刻用解析器验**（比真跑一遍快得多）：

    ```powershell
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$null, [ref]$errors)
    # $errors.Count 必须为 0
    ```
39. **Vite dev 代理会把浏览器的 `Origin` 头原样转发给后端**：换个 dev 端口（例如 5175）就被后端 CORS
    白名单挡成 **403**，而且前端只看到「请求失败」，非常像权限问题（实测排查了一轮）。
    生产是同源（nginx 反代 `/api`），浏览器压根不发 `Origin`、不走 CORS —— 所以正确做法是让 dev 代理
    **去掉这个头**（两个 `vite.config.ts` 的 `proxy.configure` 里 `proxyReq.removeHeader('origin')`），
    这样也就不用为了换端口去改后端白名单。
40. **`mysql.exe` / `mysqldump.exe` 的「Using a password on the command line」警告走 stderr**，
    在 `$ErrorActionPreference = 'Stop'` 的脚本里会被当成**致命错误**直接中断（报 NativeCommandError）。
    调用它们前后要临时把 EAP 设成 `Continue`（`e2e-lib.ps1` 的 `Invoke-Mysql` 就是这么做的）。
41. **PowerShell 里给 mysql 导入文件，别用 cmd 的 `<` 重定向，也别走管道**：
    - `cmd /c "... < file"`：引号在 PowerShell → cmd 传递过程中会被吃掉（实测报 `/c 不是内部或外部命令`）
    - 管道：PowerShell 会按文本处理，编码/BOM 容易被打坏
    正确做法是用 mysql 自己的 `source`：`mysql -uroot -p... db -e "source D:/path/file.sql"`（路径用正斜杠）。
42. **断言「两边相等」时必须同时要求非空**：我写过一个 `SUM(stock)`，但 `t_product` 没有 `stock` 列 →
    查询报错、函数返回空串，两边都是空串 → 判成「相等」**假通过**。
    正确写法：`($x.Length -gt 0 -and $x -eq $y)`。
43. **`e2e-lib.ps1` 要求调用方先定义 8 个变量**（`$root $portal $admin $mysql $java $outDir $reqFile $MysqlPassword`，
    见它文件头）。漏一个就会报「& 后面的表达式无效」这类完全指不到根因的错——这轮踩了两次。
    新写验证脚本时，把这段定义连同 dot-source 一起照抄。
44. **Element Plus 后台 UI 自动化的 4 个细节**（都在 `admin-crud-verify.mjs` 里踩过）：
    - `el-tree-select`（EP 2.9）的树节点**没有** `.el-tree-node__label`，文本直接在 `.el-tree-node__content` 上
      （实测 `.el-tree-node__label` count=0，按它等会一直等到超时）
    - 用合成事件填 `el-input-number`（set value + dispatch input/change）能驱动 v-model，但会额外冒一条
      `[Vue warn] Invalid event arguments: event validation failed for event "blur"`。真实用户点旋钮不会这样，
      属于自动化噪声；该脚本按**精确模式**放行（`web-check` / `web-flow` 仍然零容忍）
    - 新建商品页**默认已经有一行 SKU**，再点「添加 SKU」会多出空行，保存时报「第 2 个 SKU 价格不能为空」
    - 断言「某一行里不含某按钮文案」会被**操作列自己**命中：分类被禁用后那颗按钮文案变成「启用」、启用后变成「禁用」。
      要判状态就盯状态列（启用=正常，禁用=停用），别用按钮文案
45. **解析 Prometheus 文本时别用 `$` 做行尾锚**：curl 的输出经 PowerShell 之后行尾是 `\r\n`，`$` 前面会多一个 `\r`，
    永远匹配不上（症状：指标明明在 `/actuator/prometheus` 里，脚本取到的却是 -1）。另外计数器名字带 `_total` 后缀。
46. **想制造「库存不足」别用「加购一个超大数量」**：`CartService.add` 在**加购阶段**就会校验可售库存并返回 2003，
    订单根本到不了锁库存那一步（第一版这么写，断言直接失败）。正确做法是**正常加购，再把 SKU 总库存改成 0**。
47. **字段加密的实体必须 `@TableName(value = "...", autoResultMap = true)`**：只加
    `@TableField(typeHandler = EncryptedStringTypeHandler.class)` 时，**写入**会加密、**查询**却不会解密
    （MyBatis-Plus 只有开了 `autoResultMap` 才把 typeHandler 放进结果映射）。症状是「写进去密文、读出来还是密文」。
48. **BCrypt 只使用密码的前 72 字节**，超出部分被静默丢弃（"设了长密码但后半段无效"）。要在注册时显式拒绝：
    中文按 UTF-8 是 3 字节/字，64 个汉字就超了。
49. **字段加密的升级顺序不能反**：必须先跑加宽列的迁移（`sql/V6__pii_encryption.sql`）再启动应用，
    否则写密文直接 `data too long`。另外迁移用的裸 SQL mapper **必须放在 `com.mall.core.mapper`**，
    因为 `MybatisPlusConfig` 上是 `@MapperScan("com.mall.core.mapper")`，放别的包启动时注入失败。
50. **断言「有没有泄露 PII」，正则要带边界**：
    - 手机号写 `1[3-9]\d{9}` 会把 Prometheus 里的科学计数法数值（`1.18883110912E11`）当成手机号**误报**，
      应写成 `(?<![\d.])1[3-9]\d{9}(?![\d.])`
    - 判断「日志里有没有密文」不能只搜 `enc:v1:`，那会命中启动日志里那句「密文前缀 enc:v1:」的说明；
      要匹配 `enc:v1:[A-Za-z0-9_-]{16,}`（前缀 + 真正的载荷）
51. **本项目的接口约定是「HTTP 200 + 业务码」**：弱密码被拒时 http 仍是 200、`body.code=400`。
    断言别写 `http -ne 200`（踩过一次，4 条断言全"假失败"，实际策略是生效的）。要看 `Code($resp)`。
52. **Cookie 不区分端口**：localhost 下 `:5173`、`:5174`、`:8081`、`:8090` 共享同一批 Cookie。
    所以管理后台和 H5 **必须用不同的 Cookie 名**（`mall_admin_token` / `mall_portal_token`），
    否则两边的登录态会互相覆盖/互相认。
53. **`Secure` 标记在 http 环境下会让 Cookie 完全不下发**：dev 是 `http://localhost`，
    `secure=true` 时浏览器直接丢弃 → 表现是「登录成功但下一个请求就 401」，非常迷惑。
    dev 必须 `false`、prod 必须 `true`（由 `ProductionConfigGuard` 强制）。
54. **改成 Cookie 认证后，前端不能再拿 token 判断登录态**：凭证已经不在 JS 里了，
    继续用 `auth.token.value` 做守卫会导致**登录成功后立刻被踢回登录页**。
    要用本地保存的 user 信息（本项目是 `auth.isLoggedIn()`）。
55. **CSRF 只对「Cookie 认证的写请求」校验**：如果无差别要求所有写请求带 `X-CSRF-Token`，
    所有 Bearer 脚本（p2/p3/幂等/监控…）全都会 403 —— 而它们本来就没有 CSRF 风险
    （攻击者站点没法让浏览器自动带上 `Authorization` 头）。判断依据是 `JwtAuthFilter` 记下的凭证来源。
56. **字段加密的密文必须带 keyId**（`enc:v1:<kid>:<payload>`）：没有它就无法判断"这行是用哪把密钥加的"，
    密钥轮换时只能全表解密试探，几百万行根本没法做。有了 keyId，一条
    `WHERE col NOT LIKE CONCAT('enc:v1:', '<newKid>', ':%')` 就能精确圈出要重加密的行。
57. **base64url 不含 `:`，所以格式可以靠冒号切分**：`enc:v1:` 之后有冒号 = 新格式（kid:payload），
    没有冒号 = 早期格式。升级格式时**两种都要能读**，否则上线即全库解密失败。
58. **重加密/补加密的 UPDATE 一定要带「旧值」做乐观条件**（`WHERE id=? AND col=?`）：
    幂等靠它、双实例并发安全也靠它（后到的那次命中 0 行直接跳过）。
    别用 `WHERE id=?` 直接覆盖 —— 两个应用同时启动时会互相打断。
59. **写演练脚本时，别假设"现有数据一定在某个 keyId 下"**：上一版代码写的密文可能连 keyId 都没有。
    我第一次写轮换演练就栽在这：断言"数据在旧 kid 下"恒为 0。
    正确判据是「存在加密的 PII」+「轮换后数据**还未**迁到新 kid」。
60. **Cookie 名要显式配置，别靠字符串拼接推导**：我一开始把 refresh 名写成
    `tokenCookieName() + "_refresh"`，实际得到 `mall_portal_token_refresh`，
    而演练脚本按 `mall_portal_refresh` 找 —— 应用本身工作正常，但 4 条断言全红、排查半天。
61. **前端刷新必须「单飞」**：refresh 是**用一次即作废**的，多个并发请求同时 401 时如果各刷一次，
    第二个必然失败并把用户踢下线。做法是共享同一个 Promise（`refreshing`）。
62. **`LambdaUpdateWrapper.set(...)` 会立即解析列名**（需要 MyBatis-Plus 的 TableInfo 缓存），
    纯单元测试里没有缓存，会抛 `can not find lambda cache for this entity`。
    `.eq()` 是延迟解析的，所以「patch 实体 + 条件 wrapper」的写法在生产与单测里都能跑。
63. **刷新失败时后端返回 HTTP 200 + `code=401`**（本项目统一约定），前端不能只按 HTTP 状态判断，
    要看业务码；反过来，`/auth/**` 必须排除在「401 自动刷新」逻辑之外，否则会自己刷自己。
64. **同源部署下，浏览器对 POST 也会带 `Origin`，一样会撞 CORS**：nginx 既发静态文件又反代 `/api`，
    请求本来是同源的，但 Spring 的 `CorsFilter` 看到 `Origin` 就按跨域处理，白名单里没有本站域名
    → **403 `Invalid CORS request`**（登录直接挂，`curl` 不带 Origin 却是 200，很容易误判）。
    修法：反代里 `proxy_set_header Origin "";`（同源请求不需要 CORS），
    同时把 `MALL_CORS_ALLOWED_ORIGINS` 配成真实域名（给真正的跨域调用用）。
65. **`.env` 用 Windows 换行会让 docker compose 的变量带上 `\r`**：密码/密钥多一个 CR 就认证失败，
    而 `mysql` 客户端默认只报一句 warning，脚本里表现为**所有查询返回空**、看起来像"数据没建好"。
    修法：读变量时 `tr -d '\r'`；或者用 LF 写 .env。
66. **本机 docker 在 WSL 里时**：当前用户常常不在 `docker` 组（`/var/run/docker.sock` 无权限），
    用 `wsl -u root -e docker ...` 绕过；Docker Hub 基本拉不动（会解析到 199.96.58.240 之类的黑洞 IP），
    用 `docker.m.daocloud.io` 前缀拉取再 `docker tag` 成标准名字，compose 文件不用改。
67. **不要在 `/mnt/d`（drvfs）里构建镜像**：npm/maven 在 9p 文件系统上容易出权限/符号链接问题，
    先把仓库 `tar` 到 WSL 原生目录（排除 node_modules/target/.git）再 build，快且稳。
69. **列表页操作后别 `await load()` 整页重拉**：勾选/改数量/删除都重拉一次，界面会闪、还多一次往返
    （购物车尤其明显）。改成「先改本地 → 静默同步 → 失败回滚」，失败时才重新拉取。
70. **步进器必须防抖**：连点 10 下就是 10 个请求，最后一个到的顺序还不一定对。
    按条目 350ms 防抖、只发最后一次，界面用乐观值保证"跟手"。
71. **批量操作要有批量接口**：全选如果前端循环调 `/{id}/checked`，10 件商品就是 10 次往返。
    后端加一个 `PUT /cart/checked`（一条 SQL），前端一次调用。
72. **断言「某个按钮不该出现」要按卡片/行判断，别拿整页文本**：订单列表「全部」tab 里，
    已完成的订单本来就该有「再次购买」，整页 `includes('再次购买')` 必然为真（踩过）。
73. **注入登录态前先落在不发请求的页面**：直接 `goto('/')` 会先按默认路由发一次请求，
    而那时 localStorage 里可能还是**上一轮遗留的 access token**（只有 15 分钟，很容易过期）
    → 冒一条 401，把「零控制台告警」的断言打红。先 `goto('/#/login')` 设好 token 再跳目标页。74. **售后/退款这类"只能发生一次"的操作，必须用「带状态条件的 UPDATE + 判断影响行数」**：
    先 `selectById` 判断状态再 update，两个客服同时点「同意退款」就会退两次。
    正确写法：`UPDATE t_after_sale SET status=1 WHERE id=? AND status=0`，影响 0 行就报 3004。
75. **拒绝/撤销售后必须把订单退回"申请前的状态"**：如果只把售后单标记为拒绝，订单会永远卡在
    「售后中」（用户既不能再申请、商家也没法发货）。所以 `t_after_sale` 要有 `order_status_before` 字段。
76. **退款要不要恢复库存，取决于售后类型**：仅退款没有货退回仓库，加库存会让账面虚高 → 超卖；
    只有退货退款才 `stock + N`。同理「申请时锁库存、支付时扣库存、取消时释放锁库存」三条链路别混着用。
77. **PowerShell 捕获 `mysql.exe` 的中文输出会按控制台代码页（GBK）解码成乱码**，
    拿它跟源码里的 UTF-8 中文比一定失败（但库里的值是对的）。要比中文就用 `HEX(列)` 比十六进制，
    输出纯 ASCII，稳定。78. **支付幂等分支必须区分「已支付」和「已关闭」**：`UPDATE ... WHERE status=PENDING` 影响 0 行时，
    如果一律当"重复回调"放过，那么对**已关闭**的支付单调用支付接口会返回 200 —— 订单没变，
    但调用方以为付成功了（本轮 payment-verify 的 D3 就是这么抓到的真 bug）。
    正确做法：回查状态，PAID 才幂等返回，CLOSED/FAILED 报 4003。
79. **支付宝签名有个不对称，很容易踩**：请求签名的待签名串**包含** `sign_type`；
    异步通知验签要**同时剔除** `sign` 和 `sign_type`（官方 SDK 的 `rsaCheckV1` 就是这么做的）。
    搞反了会一直"验签失败"。
80. **金额单位别混**：微信是「分」，库里 `t_payment.amount` 是「元」。换算只在渠道适配层做，
    不要把分写进库，否则对账时差 100 倍。
81. **微信回调没有平台证书时必须拒绝**，不能"验不了就放过"——那等于谁都能伪造"支付成功"。
    本地联调要显式开 `mall.pay.wechat.allow-unverified-notify=true`，并且日志里会打 WARN。
82. **支付回调路径必须匿名可达**：渠道服务器不带我们的 token，把它关进鉴权里就永远收不到回调。
    安全边界完全靠验签（支付宝 RSA2 / 微信平台证书），验不过直接抛异常，走不到改订单状态那一步。83. **"同意售后"和"钱到账"是两件事**，必须分成两个字段记（`status` / `refund_status`）：
    微信退款会返回 `PROCESSING`（已受理未到账），这时候说"已退款"是骗用户；
    卡在待处理又不告诉客服能不能重试。同理适用于任何"本地决定 + 外部动作"的场景。
84. **退款单号必须由业务单号派生且可重复**（`RF` + 售后单号）：支付宝按 `out_request_no`、
    微信按 `out_refund_no` 幂等。用随机号的话，一次超时重试就会退两次钱。
85. **外部渠道调用不要包在数据库事务里**：一是秒级 IO 会占住连接，二是**失败记录会被回滚** ——
    抛异常后客服看不到任何失败原因。正确顺序：先调渠道（不在事务里）→ 记录结果 →
    再用 `TransactionTemplate` 包住"售后单 + 订单 + 库存"那几步。
86. **先退钱再改状态**，不要先标"已退款"再调渠道：后者一旦渠道失败，用户看到已退款却没收到钱，
    这是资金差错而不是体验问题。87. **定时任务会被两个后端各扫一遍**（`scanBasePackages = "com.mall"`）：不加限制就是双份执行。
    用 `@ConditionalOnProperty` 只在一个应用开（本项目 portal 开、admin 关），订单超时任务也是这个套路。
88. **微信的退款通知字段是 `refund_status`，不是支付通知的 `trade_state`**；取值 `SUCCESS / ABNORMAL / CLOSED`。
    拿支付通知的字段去解析退款通知，会一直判成"未成功"而且不报错。
89. **对账任务的「执行间隔」和「判定陈旧的时间」要分开**：间隔 5 分钟、但只查 5 分钟没动过的单。
    合成一个值会导致刚发起就查，把渠道正常处理中的单误判成异常。
90. **`@Scheduled` 方法必须自己 try/catch**：异常抛出去会被调度器吞掉并可能停止后续调度，
    表现为"任务只跑了一次就再也不动了"，而且日志里几乎没有痕迹。91. **对账兜底只能"渠道确认成功才落账"**，绝不能"查不到/查询失败就当已支付"。
    误判的影响面比不做兜底大得多。同理：`MOCK` 渠道的 `queryPayment` 必须返回 NOTPAY ——
    它返回"已支付"就等于让对账任务把待支付订单批量标成已付，纯白送。
92. **批量任务里"逐笔事务"要用 `TransactionTemplate` 显式开**：在一个大事务里 try/catch 没用，
    异常吞掉后事务已被标记 rollback-only，最后整批回滚。
93. **微信 v3 的 GET 查询签名，URL 必须带 query**（`/v3/pay/transactions/out-trade-no/{no}?mchid=xxx`），
    只签 path 会一直 401 签名错误。94. **账单解析按「表头名」映射，不要写死列序号**：渠道账单经常多一列（费率备注之类），
    按下标取会整体错位，而且错得很安静（金额取到别的列）。找不到表头就返回空 + 告警。
95. **账单解析为空时必须报错，不能生成"零差异"报告**：一份"全对"的假报告比不核对更危险。
96. **退款行不能参与支付对账**：否则每笔退款都会多出一条「渠道有、本地无」的假差异，把真差异淹没。
97. **对账断言别假设"当天库里只有自己造的数据"**：测试环境有历史订单，`MISSING_CHANNEL`
    天然会 > 1。断言用「相对数量」+「自己那笔必须在清单里」，不要写死等于几。98. **退款对账靠「商户退款单号」匹配**（`out_refund_no`），账单里没有这个号的行要**跳过并告警**，
    不能算成差异 —— 否则一批无主退款行会把真差异淹没。
99. **MySQL 8 没有 `ADD COLUMN IF NOT EXISTS`**：迁移脚本要幂等，用
    `information_schema` 判断 + `PREPARE/EXECUTE` 动态执行（V10/V12 都是这么写的），
    否则重跑一次迁移就报 1060 Duplicate column。100. **「标记已处理」不要顺手做资金操作**：对账差异的处理只能是归档（谁/何时/为什么），
     补记与重退必须走「同步支付」「查询退款」并人工确认。把不可逆的资金动作放进一个 UI 按钮，
     迟早点错一次。
101. **审计字段别直接记 `Authentication.getName()`**：本项目的 JWT principal 是用户 id，
     记下来就是 `by=4`，事后查不到是谁。用 id 反查用户名再落库。102. **自签证书必须写 SAN**：只写 `CN=domain` 时 curl/现代浏览器一律报 hostname mismatch
     （CN 早就不是主机名校验依据了）。用 `-addext "subjectAltName=DNS:...,IP:..."`。
103. **WSL 里跑的 nginx 要反代 Windows 上的服务，必须用网关 IP**（`ip route` 默认路由那条），
     `127.0.0.1` 在 WSL 内指 WSL 自己，会得到 connection refused。
104. **HSTS 的 `includeSubDomains` 会锁死子域**：某个子域还是 http 时浏览器直接拒绝访问。
     首次上线先 `max-age=300` 观察，确认无 http 子域后再放到一年。68. **initdb 会把中文写成「双重编码」，而且应用侧救不回来**：只设 `--character-set-server=utf8mb4`
    是不够的 —— 它只改服务端默认，**建连接时客户端仍会声明自己的字符集**（本机实测是 latin1），
    于是 UTF-8 的中文被当 latin1 收下再按 utf8mb4 存：`小米` → `å°ç±³`（HEX 里出现 `C3A5` 前缀）。
    判据：`SELECT HEX(name)` 正常 CJK 是 `E4..E9` 开头，双重编码必然是 `C3Ax`。
    修法：① 每个 SQL 文件开头 `SET NAMES utf8mb4;` ② mysqld 加 `--character-set-client-handshake=FALSE`。
    顺手把「接口返回 CJK + 库里无双重编码」做成部署测试断言，避免下次重建库又踩。
38. **等「页面出现某段文字」还不够，要等「你要点的那个操作所需的数据」就绪**（§8 第 23 条的变体）：
    结算页 `load()` 是**先取地址、再取购物车清单**，地址卡片一出现测试就去点「提交订单」，
    而此时 `items` 还是空的 → `submit()` 直接 return（只弹个 toast），后端根本没收到请求。
    症状很有迷惑性：页面文本里商品明明在（那是稍后才加载完的），但断言「跳到订单详情」失败。
    正确写法是点之前先 `waitText(商品名)`；顺带也修了真实体验——**提交按钮在加载期间禁用**
    （慢网络下用户真的会点到一个没反应的按钮）。

**问题 2：主包过大。** admin-web 入口 chunk **1,037 kB**（Element Plus 全量 `app.use`），H5 **321 kB**（Vant 全量）。

**解决：按需注册。** 两个入口不再 `app.use(整个库)`，改成显式列出用到的组件逐个 `app.use(组件)`
（每个组件的 install 会连带注册子组件，但**指令不算**——`v-loading` 得单独 `app.use(ElLoading)`）。
清单是扫模板 `<el-*>` / `<van-*>` 得到的，并核对过这些名字确实是两个库的导出。
Element Plus 的中文语言包改由根组件 `<el-config-provider :locale="zhCn">` 提供（以前是 `app.use(ElementPlus, {locale})` 给的）。

| | 入口 chunk | JS 合计 |
|---|---|---|
| admin-web 改前 | 1,037 kB | 1.44 MB |
| admin-web 改后 | **354 kB** | **0.64 MB** |
| portal-web 改前 | 321 kB | 0.59 MB |
| portal-web 改后 | **最 189 kB → 101 kB(vendor-vue)/84 kB(vendor-vant)** | **0.27 MB** |

⚠️ **CSS 仍用整份**（`element-plus/dist/index.css` / `vant/lib/index.css`）：按组件拆 CSS 要手工维护、
漏一个就是「页面能跑但外观崩」，而 CSS 是独立文件、gzip 后只有几十 kB，不值得为此冒险。
这次要解决的是 JS 主包。

⚠️ **维护提醒**：以后新写页面用到**新**组件，必须去 `main.ts` 的清单里补一行，否则组件不会注册
（症状是页面少一块、控制台一条 `Failed to resolve component` 告警——正好会被 `web-check` 的零容忍抓住）。

**vendor 分包**：`vite.config.ts` 里用**函数式** `manualChunks` 把第三方库与业务代码分开（发版后用户只需
重新下载业务代码那个文件）。⚠️ 不能用 `{ 'vendor-element-plus': ['element-plus'] }` 的对象式写法，
实测会让 element-plus 从 ~396 kB 反弹到 799 kB（见 §8 第 32 条）；所以 admin-web 对 element-plus
**刻意返回 undefined**（让它随主流），只拆 vue 与其它依赖。

### P4 生产加固（17/17）

四项都是「上线前不落地、就会以奇怪方式咬人」的。

**1. CORS 白名单**（原来是 `allowedOriginPatterns("*")` + `allowCredentials(true)`）
通配来源 + 携带凭证 = **任意站点都能拿用户身份调后端**。现在改成显式白名单
`mall.cors.allowed-origins`（环境变量 `MALL_CORS_ALLOWED_ORIGINS`），dev 默认是两个前端地址；
prod 下 `ProductionConfigGuard` 直接拦掉含 `*` 的配置。顺带把 `X-Trace-Id` 加进 `exposedHeaders`，
否则前端读不到这个响应头。

**2. 登录失败限流**（原来登录接口可无限爆破——BCrypt 只让每次尝试变慢，挡不住持续轮询）
`LoginThrottle`：同一用户名在窗口内失败达到 `max-failures`（默认 5）就锁 `lock-seconds`（默认 300）秒。

- 锁定期间**连正确密码也拒**（否则「再试一次对的」就把限流绕过去了）
- 密码正确才清计数；禁用账号不清（免得被拿来刷掉别人的锁定状态）
- 用户名不存在**也计数**（否则错误信息的差异就是用户名枚举探针）
- 被限流时**不查库、不跑 BCrypt**（否则限流反而成了压测入口）

两个刻意的取舍：① 按**用户名**而非 IP 计数——本项目生产走 nginx 反代，`remoteAddr` 是代理地址，
拿不到可信客户端 IP，按 IP 反而会把所有用户算成一伙；副作用是「知道用户名就能把人锁住」，
所以窗口故意设短。② 计数在**进程内存**里——单实例够用、重启即清零；多副本部署必须换共享存储，
否则上限会被放大到「副本数 × maxFailures」。

**3. traceId**（原来线上出问题只能靠时间戳在一堆并发日志里捞）
`TraceIdFilter`（最高优先级）：沿用上游 `X-Trace-Id`（跨服务能串起来），没有就生成 16 位 hex；
写入 MDC + 响应头；日志格式用 `%X{traceId:--}` 带出来；`finally` 清理 MDC（避免线程复用串号）。
上游值会被长度与字符白名单卡住——它要进日志，不能让换行伪造日志行。

**4. 接口版本 `/api/v1`**（原来全是裸 `/api/...`，第一次破坏性变更就没有迁移余地）
13 个 Controller 的 `@RequestMapping`、SecurityConfig 的匹配规则、两个前端 `baseURL`、
7 个验证脚本全部升级到 `/api/v1/...`。**没有保留旧路径别名**——旧路径直接 404，这才是版本前缀该有的行为。

**验证**：`scripts\security-verify.ps1`（**17/17**，产物 `tmp\security\summary.txt`）：

```
[PASS] A1 response carries X-Trace-Id (16 hex)
[PASS] A2 upstream X-Trace-Id is honoured
[PASS] A3 traceId really lands in the log line
[PASS] A4 over-long upstream value is replaced, not echoed
[PASS] B1 whitelisted origin gets Allow-Origin :: allow=http://localhost:5173
[PASS] B2 foreign origin gets NO Allow-Origin :: http=403
[PASS] C2 CORRECT password is refused while locked (1005) :: 请 299 秒后再试
[PASS] C4 other usernames are unaffected
[PASS] C5 two failures then success still works (below limit)
[PASS] C6 admin login still works
```

配套单测 +16 项：`LoginThrottleTest`(7)、`UserServiceImplTest`(5)、`TraceIdFilterTest`(4)、
`ProductionConfigGuardTest` 增 2 项 CORS 断言。

> A4 用「超长值」而不是「换行注入」：curl 无法在 header 里发真实换行（会截断成 `abc`，
> 而 `abc` 本身合法、被原样沿用是对的）。换行注入那条由 `TraceIdFilterTest` 直接单测覆盖。

### 日志策略（为什么后端不自带文件滚动）

后端**只往 stdout 打日志**（格式见 `backend/mall-core/src/main/resources/logback-spring.xml`，行内带 traceId）。
目录与轮转交给运行环境：

- **docker 部署**：`docker-compose.yml` 里每个服务都配了 `json-file` 的 `max-size=20m / max-file=5`。
  docker 默认**不限制**日志，长期跑会把宿主磁盘吃满——这是「凌晨磁盘 100% 导致服务不可用」的最常见原因。
- **裸机长跑**：把 stdout 重定向到文件后交给 `logrotate`（或 systemd-journald）。
- **本地开发**：`run.bat` 每次启动都会截断 `logs\*.log`，不会累积。

> 我原先加过一份自带 `RollingFileAppender`（单文件 50MB / 按天切 / gzip / 总量 1GB）。
> 默认阈值下它工作正常（写出 15KB 无异常），但把阈值压到 2KB 强制触发滚动时，
> **实时文件变成 0 字节且没有任何错误上报**（打开 logback 内部状态也没有 ERROR）。
> 2KB 属于病态配置（同秒内反复滚动、归档名互相覆盖），可这恰恰说明我无法证明它在
> 「第一次真正滚动」时是可靠的——**一个可能静默丢日志的日志出口比没有更危险**，所以不带上线。
> 12-factor 的做法本来就是「只打 stdout，由平台负责轮转」，这里回到那条路上。

### 敏感数据安全审计（`security-data-verify.ps1` 34/34）

**审计方法**：不看"有没有写加密代码"，而是追每一条敏感数据的**完整链路** —— 存储 / 传输 / 序列化 / 日志 / 备份 / 前端。

| 数据 | 审计结论（改动前） | 现在 |
|---|---|---|
| 登录密码 | ✅ 已是 BCrypt（strength 10），任何接口都不返回、日志里也没有 | 补了**强度策略**（8-64 位、必须字母+数字）、**72 字节上限**、`@JsonIgnore` + `@ToString.Exclude` |
| 手机号 / 收货人 / 详细地址 | ❌ **全库明文**（`t_user.phone`、`t_address.receiver/phone/detail`、`t_order` 三列快照） | ✅ **AES-256-GCM 字段级加密**（见下） |
| JWT | ✅ HS256，密钥走环境变量，prod 自检拒绝开发默认值 | 补**密钥分离**：`mall.crypto.secret` 不得等于 `mall.jwt.secret` |
| 日志 | ✅ 扫过：无手机号、无密码哈希、无 token；但有一条 Spring 自动生成的默认密码 | ✅ 已消除（排除 `UserDetailsServiceAutoConfiguration`） |
| 备份文件 | ❌ 原来**没有备份** | ✅ 有备份；且因为列级加密，dump 里的 PII 已是密文（密码哈希仍是哈希） |
| 传输 | ❌ **全程明文 HTTP**（密码、JWT、手机号裸奔） | ⚠️ 只给了模板 `deploy/nginx-tls.conf.example`（TLS1.2/1.3 + HSTS + CSP + 安全头）。**本机没有 nginx 和证书，无法验证 —— 这一项必须在目标机完成** |
| 前端 | ⚠️ 无 `console.log`，但 token 存 localStorage（XSS 能读走） | ✅ 已迁 **HttpOnly Cookie**（见下方「登录态迁移」）；CSP 模板已给 |

#### PII 字段级加密怎么做的

- `com.mall.core.crypto.AesGcmCipher`：AES-256-GCM（**AEAD**，改密文会解密失败而不是解出错数据；ECB 还会让相同明文得到相同密文，手机号这种低基数字段一眼能看出重复）。密钥用 `SHA-256(mall.crypto.secret)` 派生，任意长度口令都能用。
- 存储格式 `enc:v1:<base64url(iv ‖ 密文 ‖ tag)>`。**带前缀是刻意的**：读时靠前缀区分「已加密」与「历史明文」，于是上线不需要停机一次性迁移。
- `EncryptedStringTypeHandler` + 实体上的 `@TableField(typeHandler = ...)`：读写自动加解密，业务代码零改动。
- ⚠️ **实体类必须 `@TableName(value = "...", autoResultMap = true)`**，否则查询不走 typeHandler —— 写进去是密文、读出来还是密文（§8 第 45 条）。
- 存量明文：`PiiEncryptionMigrator`（`ApplicationRunner`）启动时逐行加密，每条 `UPDATE` 带 `AND <列> = 旧值` 乐观条件，两个应用同时启动也只成功一次（实测 admin 迁了 11 个值、portal 迁了 7 个，**没有重复加密**）。
- 列宽：密文比明文长得多，`sql/V6__pii_encryption.sql` 先把 `phone`→128、`receiver`→256、`detail`→1024、`receiver_addr`→2048 加宽（幂等）。**升级顺序：先跑 V6，再启动应用**，否则写密文会 `data too long`。
- 代价（已知且可接受）：加密后**不能再按这些字段做等值/模糊查询**。本项目正好没有（`UserService.page` 只 `like(username)`）；将来要按手机号查，必须加一列 HMAC「盲索引」，不能直接 `WHERE phone = ?`。

#### 验证（34 项，`tmp\security-data\summary.txt`）

```
[PASS] A1 6-digit all-numeric password rejected :: code=400 msg=密码需同时包含字母和数字
[PASS] A4 password over the 72-byte BCrypt limit rejected :: code=400
[PASS] A6 password stored as BCrypt hash :: prefix=$2a$10$
[PASS] A9 user info response carries no password field/hash
[PASS] B1 every t_address PII value is ciphertext :: rows=4 plaintextValues=0
[PASS] B2 every t_order PII value is ciphertext :: rows=2 plaintextValues=0
[PASS] B4 known seed PII is no longer readable from columns :: hits=0
[PASS] C2 stored value is ciphertext (enc:v1: prefix) :: stored=enc:v1:|enc:v1:|enc:v1:
[PASS] C3 API returns decrypted plaintext :: phoneVisible=True
[PASS] C6 order snapshot stored as ciphertext
[PASS] C7 order detail decrypts receiver/phone/address
[PASS] C8 same phone in two rows yields different ciphertext (random IV, not ECB) :: same=False
[PASS] D1 no plaintext phone number in logs        [PASS] D2 no BCrypt hash in logs
[PASS] D3 no JWT/Bearer token in logs              [PASS] D4 no spring generated default password in logs
[PASS] D5 no ciphertext dumped into logs           [PASS] E2 prometheus leaks no phone / no ciphertext
```

`C8` 是这套设计的关键证据：**同一个手机号插到两行，密文必须不同**。如果用 ECB 或固定 IV，两行密文会完全一样，等于没加密（攻击者能看出"这两个用户同一个号"）。

#### 仍未做的（如实列出）

1. **传输加密**要在目标机落地（模板已给，未验证）。
2. ~~token 存 localStorage~~ → **已改 HttpOnly Cookie**（见下）。但 **7 天有效期 + 无吊销机制**仍在：token 一旦以别的方式泄露（被抓包、被中间件记录），到期前一直有效。要根治得加 refresh token + jti 黑名单。
3. **管理员看手机号是明文**（发货需要），没有做响应脱敏。
4. ~~密钥轮换没做~~ → **已支持双密钥轮换 + 一次性重加密**（见下方「密钥轮换」小节，`crypto-rotate-verify.ps1` 演练 28/28）。

### HTTPS 落地（`https-verify.sh` 18/18）

之前 `deploy/nginx-tls.conf.example` 只是**一份没人跑过的模板**，这是上线前的硬阻塞之一。

#### 怎么做到"可验证"

开发机没有 nginx、也没有证书。做法是：在 WSL 里装一个 nginx，用 **openssl 自签一套 CA + 服务器证书**，
把配置真的跑起来，然后用 curl/openssl 逐项断言。所以这份模板不是"看起来对"，而是"跑通过"。

```
== 0) 证书
[PASS] 0.2 证书链校验通过（自签 CA 签发）
[PASS] 0.3 SAN 覆盖 localhost / 127.0.0.1
== 1) nginx
[PASS] 1.1 nginx -t 语法检查通过
== 2) 跳转
[PASS] 2.1 HTTP 返回 301 / 2.2 跳转目标是 https
== 3) 握手
[PASS] 3.1 信任 CA 时握手成功（200）
[PASS] 3.2 不信任该 CA 时校验失败（说明证书确实是我们签的）
[PASS] 3.3 TLS 1.1 被拒绝
[PASS] 3.4 TLS 1.3 可用且链校验通过
== 4) 安全头
[PASS] 4.1 HSTS（max-age=31536000; includeSubDomains）
[PASS] 4.2 X-Content-Type-Options: nosniff
[PASS] 4.3 X-Frame-Options: DENY
[PASS] 4.4 Referrer-Policy
[PASS] 4.5 不暴露 nginx 版本
== 5) 业务
[PASS] 5.1 HTTPS -> nginx -> 后端：管理员登录成功
[PASS] 5.2 带 token 的业务接口经 HTTPS 可用（200）
```

#### 三个容易踩的点

1. **自签证书必须写 SAN**：只写 `CN=mall.local`，curl 和现代浏览器都不认（hostname mismatch）。
   脚本用 `-addext "subjectAltName=DNS:localhost,DNS:mall.local,IP:127.0.0.1"` + `ext.cnf` 显式声明。
2. **WSL 里的 nginx 要访问 Windows 后端，必须走网关 IP**（`ip route` 默认路由，实测 192.168.176.1）；
   `127.0.0.1` 在 WSL 里指 WSL 自己。
3. **HSTS 的 `includeSubDomains` 有锁死风险**：某个子域还是 http 就会被浏览器直接拒绝。
   首次上线先把 `max-age` 调到 300 秒观察一天，再放大到一年。

#### 上线前必须做、但开发机做不了的

| 项 | 原因 |
|---|---|
| 真实 CA 证书 + 域名 | 没有签发权限 |
| 证书自动续期（certbot + deploy-hook） | 需要真实 ACME 流程 |
| OCSP stapling | 需要真实证书链 |
| `mall.security.cookie.secure=true` 的实际下发 | 依赖 prod profile，已由 `ProductionConfigGuard` 强制 |

模板见 `deploy/nginx-tls.conf.example`，文末列了这四条 TODO。

### 对账：账单逐笔比对（`reconcile-verify.ps1` 35/35）

前两轮做的「回调 + 主动查单」都是**单笔**兜底，而且都依赖"我们知道要查哪一笔"。
真正的兜底是**对账**：拿渠道账单和本地记录逐笔比。它回答的是另一个问题 ——
**有没有哪一笔钱，渠道收了、我们根本不知道。**

#### 三类差异（含义完全不同，处理方式也不同）

| 类型 | 含义 | 危险程度 |
|---|---|---|
| `MISSING_LOCAL` | 渠道账单有、本地没有 | **最高**：钱收了没记账 |
| `MISSING_CHANNEL` | 本地有、账单里没有 | 中：可能是测试数据，或账单缺页 |
| `AMOUNT_MISMATCH` | 两边都有、金额不同 | 低：通常是优惠/手续费口径 |

#### 为什么是「上传账单」而不是「自动下载」

真实账单接口需要商户资质（支付宝需要应用证书、微信需要 API 证书），而且**账单格式必须拿真实文件核对**。
所以这一版把控住风险最高的部分 —— **解析与比对逻辑**，账单来源先做成上传。
解析器按**表头名**映射列（不是写死列号），账单多一列也不会整体错位。

| 解析器 | 格式 |
|---|---|
| `NormalizedBillParser` | 归一化 CSV（表头英文），模拟渠道与手工整理用 |
| `AlipayBillParser` | 支付宝业务明细：跳过前几行说明、按表头取「商户订单号/支付宝交易号/订单金额」，有退款批次号的行判为退款 |
| `WechatBillParser` | 微信交易账单：去反引号、按表头取列、交易状态列 |

> 两个真实解析器的**列映射是按公开文档写的默认值，上线前必须拿一份真实账单核对**。
> 归一化格式是确定可用的，本地与自动化都用它。

#### 两个刻意的行为

1. **账单解析不出任何流水时直接报错（400）**，而不是生成一份"零差异"报告 ——
   静默的"全对"比不核对更危险
2. **退款行不参与支付对账**（v1），否则每笔退款都会凭空多出一条"渠道有本地无"

#### 退款对账（V12）

V11 只对了支付，账单里的退款行被跳过。但退款错了更贵：**我们标了"已退款"、渠道其实没退**（用户在等钱）。

- 匹配键是**商户退款单号**（`out_refund_no`）：我们发给渠道的退款单号会原样出现在账单里
- 账单里**没有退款单号的行跳过并告警**，不算成差异 —— 算进去只会制造噪音，把真差异淹没
- 退款侧单独一组计数（`refundTotalLines / refundMatched / refundMissingLocal / refundMissingChannel / refundAmountMismatch`），
  不与支付计数混在一起；差异表用 `biz_type`（PAY/REFUND）区分

```
[PASS] A7  refundTotalLines = 3
[PASS] A9  refund missingLocal = 1 (channel refunded, we have no record)
[PASS] A10 refund amountMismatch = 1
[PASS] B4  refund ghost is classified as missing-local
[PASS] B6  the omitted refund is reported as missing-channel
```

#### 差异处理（V13）

只出差异清单不够——没人处理的话，这张表只会越积越多，最后运营就不看了。

- 每条差异可标「已核实」或「忽略」，记录**处理人、时间、说明**（审计）
- 只有「待处理」能被处理：条件 UPDATE + 影响行数，重复点击返回 400
- 批次列表显示**未处理条数**（一次分组查询，不做 N+1）

> **刻意只做归档，不做资金操作**。「标记已处理」按钮里顺手"补记一笔钱"或"重新退款"是危险设计：
> 那是不可逆的资金动作，必须走「同步支付」「查询退款」接口并人工确认之后再单独触发。

> 审计字段别记 JWT 的 principal（那是用户 id，会变成 `by=4`，事后查不到是谁）。
> 控制器里用 id 反查用户名再记（本轮实测从 `by=4` 改成 `by=malladmin`）。

#### 验证（35/35，节选）

```
[PASS] A2 totalLines = 3
[PASS] A3 matched = 1
[PASS] A4 missingLocal = 1 (channel has it, we do not)
[PASS] A5 amountMismatch = 1
[PASS] A6 missingChannel >= 1 (local payments absent from the bill)
[PASS] B2b the ghost order is classified as missing-local, not missing-channel
[PASS] B4 amount mismatch carries both amounts (60 vs 55)
[PASS] C2 unparsable bill fails loudly (400), not a fake clean report
```

> 第一次跑是 20/22：我断言"`missingChannel=1`"，实际是 19 —— 因为当天库里还有别的测试订单，
> 它们确实"本地有、账单无"。**引擎是对的，断言错了**。改成相对断言后才对。

界面上：后台新增「对账」菜单 —— 选渠道/日期、上传或粘贴账单、看汇总与逐笔差异清单、历史批次。

### 支付查单兜底：回调丢了也能救回来（`payment-verify.ps1` 39/39）

**问题**：支付成功靠渠道异步回调。回调会丢（网络、回调地址配错、服务在重启），
这时用户**钱已经扣了**，订单却还是「待支付」，最后被超时任务关掉 —— 钱扣了、单关了，
是最难解释的一类事故。

#### 两条兜底路径

| 路径 | 触发方 | 说明 |
|---|---|---|
| 定时对账 | `PaymentReconcileTask`（每 2 分钟） | 捞「待支付且创建超过 2 分钟」的单，主动查渠道 |
| 人工同步 | 后台 `POST /admin/payment/{paymentNo}/sync` | 用户打电话说"我付过钱了"，客服点一下查真实状态 |

> `MOCK` 渠道的 `queryPayment` **永远返回 NOTPAY**。这不是偷懒：模拟渠道没有"渠道侧真相"，
> 如果它也说"付过了"，对账任务就会把所有待支付订单直接改成已支付 —— 那不是兜底，是白送。

#### 一条铁律

**只有渠道明确说 SUCCESS 才落账**。查不到、超时、返回 NOTPAY/CLOSED 一律**不动状态**。
写成"查询失败就当已支付"是最危险的实现方式 —— 兜底逻辑一旦误判，影响面比没有兜底大得多。

#### 逐笔事务

一批里单笔失败不能拖累整批（渠道超时很常见）。但把 `try/catch` 放在一个大事务里没用：
异常被吞掉后事务已经被标记 rollback-only，最后还是整批失败。所以用 `TransactionTemplate`
**每笔单独开事务**。

#### 验证（39/39，节选）

```
[PASS] J1 sync endpoint reports not paid for MOCK (no false positive)
[PASS] J2 order stays unpaid when the channel says NOTPAY :: orderStatus=0
[PASS] J3 payment stays PENDING :: payStatus=0
[PASS] J4 syncing an unknown payment returns 4002
[PASS] J5 normal user cannot call the admin sync endpoint (403)
```

单元测试（`PaymentServiceImplTest`，5 条）盯的是同一个不变量：
渠道说成功才落账、说不成功什么都不改、已支付不重复落账、已关闭不被"复活"、渠道不可用直接跳过。

### 退款状态收敛：通知 + 主动查单（`after-sale-verify.ps1` 57/57）

上一节做完了「退款打到渠道」，但留下一个洞：**微信退款可能只返回 `PROCESSING`（已受理未到账），
而退款通知会丢**（网络抖动、回调地址配错、服务正好在重启）。只靠通知的话，这笔单会永远停在
「退款中」——用户看不到钱，客服也不知道该不该重试。

#### 三条收敛路径

| 路径 | 触发方 | 说明 |
|---|---|---|
| 退款异步通知 | 渠道 → `POST /portal/pay/refund-notify/{channel}` | 微信 `REFUND.SUCCESS / ABNORMAL / CLOSED`；**支付宝退款是同步接口，没有独立退款通知** |
| 定时对账 | `RefundReconcileTask`（每 5 分钟） | 捞「退款中且 5 分钟没动」的单，主动查一次渠道 |
| 人工查单 | 后台 `POST /admin/after-sale/{id}/query-refund` | 用户打电话过来说没收到钱时，先查真实状态 |

三条路径最终都走同一个落库方法，且用**带条件的 UPDATE**（`WHERE refund_status=1`）：
通知和定时任务同时到达时只有一次生效。

#### 两个容易写错的点

1. **退款通知的字段是 `refund_status`，不是支付通知的 `trade_state`** —— 用错会永远判不出成功
2. **定时任务的「执行间隔」和「判定陈旧的时间」必须分开**：间隔 5 分钟、但只查 5 分钟没动过的单。
   混成一个阈值的话，刚发起就去查，渠道还在正常处理，会被误判成异常

#### `ABNORMAL / CLOSED` 怎么处理

标成 `refund_status=3`（退款失败）+ 原因落库，**售后单仍然是「已同意」**。
这是刻意的：商家确实同意了，差的是钱；把它退回「待处理」会让用户以为申请被驳回，客服也会重复处理。

#### 验证（57/57，节选）

```
[PASS] I1 manual refund query converges PROCESSING -> SUCCESS (2)
[PASS] I2 db reflects the converged status :: row=2|set
[PASS] I3 querying again is idempotent (still SUCCESS)
[PASS] I4 refund notify rejected without platform cert (4001/4005)
[PASS] I5 mock channel has no refund notify (rejected, not silent 200)
```

> 定时任务只在一个应用里跑：两个后端都会扫描 `com.mall`，所以用
> `@ConditionalOnProperty(mall.pay.refund-reconcile.enabled)`，portal 为 true、admin 为 false。
> 订单超时关单任务也是这个套路。

### 退款真正打到渠道（`after-sale-verify.ps1` 52/52）

**改动前**：售后「同意退款」只是把订单标成已退款 + 恢复库存 —— **钱一分没退**。
用户看到"已退款"，账户里却没有钱，这是最典型的资金差错。

#### 两个状态必须分开

| | 含义 | 取值 |
|---|---|---|
| `t_after_sale.status` | 业务决定（商家同不同意） | 0 待处理 / 1 已同意 / 2 已拒绝 / 3 已撤销 |
| `t_after_sale.refund_status` | 外部动作（钱退没退成） | 0 未退款 / 1 退款中 / 2 退款成功 / 3 退款失败 |

合成一个字段就会出问题：微信退款可能返回 `PROCESSING`（已受理未到账），这时候说"已退款"是骗用户；
而如果因此把售后卡在"待处理"，客服又不知道到底该不该重试。

#### 顺序：先退钱，再改状态

反过来的写法（先标已退款再调渠道）一旦渠道失败，用户看到"已退款"但没收到钱 —— 这是资金差错。
所以 `approve()`：

```
1. 调渠道退款（幂等）   ← 失败：记录 refund_status=3 + 原因，抛错，售后仍是待处理
2. 事务内：售后单 → 已同意 / 订单 → 已退款 / 退货退款恢复库存
```

#### 三个关键细节

1. **退款单号由售后单号派生**（`RF` + afterSaleNo）：失败后重试复用同一个号，
   支付宝按 `out_request_no`、微信按 `out_refund_no` 幂等 —— **绝不会重复退钱**
2. **外部调用不放在数据库事务里**：调渠道是秒级 IO，包在事务里会占住连接；
   更重要的是失败记录必须**提交**下来（放在会回滚的事务里，客服什么都看不到）
3. **事务边界用 `TransactionTemplate` 显式控制**：只有第 2 步（售后单+订单+库存）需要原子性

#### 渠道实现

| 渠道 | 接口 | 幂等键 |
|---|---|---|
| MOCK | 直接成功（本地/自动化） | refundNo |
| 支付宝 | `alipay.trade.refund`（同步） | `out_request_no` |
| 微信 | `POST /v3/refund/domestic/refunds` | `out_refund_no` |

> **已知限制**：两个真实渠道的**响应没有做验签**（需要平台证书/支付宝公钥的响应签名规范）。
> 我们作为客户端走 TLS 直连固定网关、只从响应里取 code 和退款单号；
> 在拿不到商户资质、无法与真实网关联调的前提下，草率实现响应验签反而可能误拒正常响应，
> 所以先不做并在这里记明，而不是假装验过。

#### 验证（52/52，节选）

```
[PASS] E2b refund really went through the channel (2|MOCK|RFAS...) :: row=2|MOCK|RFAS202610220937097644
[PASS] E6b retry reuses the same refund no (no second refund) :: refundNo=RFAS202610220937097644
[PASS] F4 return-refund also refunded through the channel :: refund_status=2
[PASS] H2 approve fails when there is no successful payment (4002) :: code=4002
[PASS] H3 after-sale stays PENDING and refund marked FAILED (0|3) :: state=0|3
[PASS] H5 order stays in AFTER_SALE(5) on failure (not refunded) :: status=5
```

失败路径是刻意造的：把该订单的支付记录删掉（模拟"钱收不到/记录丢失"），
审批必须失败、售后保持待处理、`refund_status=3` 且原因落库，订单**不能**变成已退款。

界面上：H5 售后详情多「退款单号 / 退款状态」，后台售后列表多「退款」列（未退款 / 退款中 / 已退款 / 退款失败）。

### 支付：收银台 + 支付单 + 三渠道（`payment-verify.ps1` 34/34 + `web-flow` 61/61）

**改动前**：`POST /order/{id}/pay` **直接把订单状态改成已支付** —— 没有支付单、没有渠道、没有回调、
没有对账依据。用户点的那下"去支付"其实什么也没发生。

**现在**的链路：

```
结算页下单 → /pay/{orderId} 收银台 → 选渠道 → POST /portal/payment 创建支付单
     → 渠道调起（MOCK 直接成功 / 支付宝跳收银台 / 微信返回 code_url）
     → 渠道异步回调 POST /portal/pay/notify/{channel}（验签 → 校验金额 → 幂等）
     → 支付单 PAID + 订单 markPaid（扣库存、加销量）
```

#### 三个渠道（SPI：`PaymentChannel`，加渠道不用改业务代码）

| 渠道 | 实现程度 | 需要的配置 |
|---|---|---|
| `MOCK` | 完整可用（本地/自动化） | 无，`mall.pay.mock-enabled=true` |
| `ALIPAY_WAP` | **真实签名/验签**（RSA2 = SHA256withRSA），生成收银台 URL | appId、应用私钥、支付宝公钥、公网 notify_url |
| `WECHAT_NATIVE` | **真实 v3 签名**（WECHATPAY2-SHA256-RSA2048）+ 回调解密（AES-256-GCM） | mchId、证书序列号、商户私钥、APIv3 密钥、平台证书 |

> **没有商户资质，线上真实收款无法自测**，这点必须说清楚。但签名层不是"写个样子"：
> 单测用自签 RSA 密钥对验证了「生成的支付链接/授权头能被公钥验过、改一个字节就验不过」，
> 以及微信回调报文能被 APIv3 密钥正确 AES-GCM 解密。把环境变量配好即可切到真实渠道。

#### 三条不变式（都有断言守着）

1. 只有「待支付」的支付单能变「已支付」—— 条件 UPDATE + 影响行数；渠道重复回调**不会重复扣库存**
2. 回调金额必须等于支付单金额（4004），防中间人改价
3. 一笔订单同时只有一个待支付单 —— 重新发起时先关掉旧的，避免"两笔都能付成功"

#### prod 自检

`mall.pay.mock-enabled` 在 prod **必须为 false**：模拟渠道调一个接口就能把订单标成已支付，
留在 prod 等于白送商品。`ProductionConfigGuard` 会在启动时直接失败。

#### 验证（节选）

```
[PASS] A2 unconfigured channels are hidden (no alipay/wechat without credentials)
[PASS] B2 amount equals order payAmount (100.00)
[PASS] B5 unconfigured channel is rejected (4001)
[PASS] C4 stock deducted exactly once (-2) :: stock 100 -> 98
[PASS] C6 paying twice is idempotent (no extra stock deduction) :: stock 98 -> 98
[PASS] D1 the older pending payment was closed (2)
[PASS] D3 a closed payment cannot be paid (4003)      <- 第一次跑出来的真 bug
[PASS] E1 forged alipay notify is rejected            <- 伪造回调改不了订单
[PASS] G3 legacy pay also leaves a paid payment record (unified flow)
```

UI 侧（`web-flow` 57 → **61/61**）：

```
[PASS] 提交订单后进入收银台（而不是直接标记已支付） :: hash=#/pay/238
[PASS] 收银台只列出已配置的渠道（模拟支付）
[PASS] 未配置的支付宝/微信不出现在收银台
[PASS] 收银台支付成功（模拟渠道）
[PASS] 支付单已落库：status=1 且 channel=MOCK :: row=1|MOCK
```

### 售后：退款 / 退货退款（`after-sale-verify.ps1` 44/44 + `web-flow` 11 条 UI 断言）

**改动前**：系统里**完全没有售后**。订单只有 待支付/已支付/已发货/已完成/已关闭，
用户付了钱发现不想要，唯一出路是「不确认收货」——没有任何可走的流程。这是功能缺口，不是体验问题。

#### 状态机（两套状态，必须一起看）

售后单：

```
PENDING(0 待商家处理) ──商家同意──> APPROVED(1 已退款)      ← 终态
        ├──商家拒绝──> REJECTED(2 商家已拒绝)               ← 终态
        └──用户撤销──> CANCELED(3 用户已撤销)               ← 终态
```

订单：申请时 `已支付(1)/已发货(2)/已完成(3)` → **`售后中(5)`** → 同意则 **`已退款(6)`**，
拒绝/撤销则**退回申请前的状态**（`t_after_sale.order_status_before`）。

#### 只做两种售后类型（`换货` 明确不做）

| 类型 | 含义 | 库存 |
|---|---|---|
| 仅退款 | 没收到货 / 不想要了，商品不寄回 | **不动** |
| 退货退款 | 已收到货需寄回，商家收到后退款 | **恢复（stock + N）** |

换货要再走一次发货流程，边界比退款复杂得多。**与其做个半成品让人误以为能用，不如不做**，
在类型选择页和文档里都写清楚。

#### 四条不变式（每条都有断言守着）

1. 只有 已支付/已发货/已完成 能申请（待支付 → 3006，已退款 → 3006）
2. **同一订单同时只能有一个进行中的售后** —— 申请时先把订单推进到「售后中」，
   并发第二个申请影响 0 行 → 3005
3. 售后单只有 `PENDING` 能被处理 —— 重复点「同意退款」返回 3004，**不会重复退款**
4. 拒绝/撤销必须把订单**退回申请前的状态**，否则订单永远卡在「售后中」

#### 验证

`scripts/after-sale-verify.ps1`（**44/44**，真库真接口）：

```
[PASS] A1 applying on an unpaid order is rejected (3006)
[PASS] B2 order moved to AFTER_SALE(5) :: status=5
[PASS] B3 row: pending / refund-only / refund=200.00 / prevStatus=1 :: 0|1|200.00|1
[PASS] B5 a second in-flight application is rejected (3006/3005)
[PASS] B6 applying on someone else order is rejected (3001)
[PASS] C3 order restored to PAID(1) :: status=1
[PASS] C4 cancelling twice is rejected (3004)
[PASS] D5 order restored to PAID(1) :: status=1
[PASS] D6 reject remark is stored :: hex=E4B88D...
[PASS] D7 rejecting twice is rejected (3004)
[PASS] E5 refund-only does NOT restore stock :: stock 48 -> 48
[PASS] E6 approving twice is rejected (3004, no double refund)
[PASS] F3 return-refund restores stock (+2) :: stock 46 -> 48
[PASS] G4 a normal user cannot read the admin after-sale list (403)
```

UI 侧接进 `web-flow`（41 → **57/57**），走的是真按钮：

```
[PASS] 订单详情出现「申请售后」入口
[PASS] 选择「退货退款」类型 / 选择售后原因
[PASS] 申请后订单状态变为「售后中」 :: db status=5
[PASS] 后台售后列表出现待处理单并可点「同意退款」
[PASS] 同意后售后单已退款 / 订单已退款(6) :: as=1 order=6
[PASS] H5 售后详情显示「退款已完成」
```

#### 界面（这次重点解决"不明确"）

- **入口三处**：订单详情底部按钮、订单详情顶部状态条、我的 → 我的售后
- **申请页**：两种类型带一句话说明（谁需要寄回）、固定原因选项、退款金额直接显示在提交栏
- **详情页**：顶部大字状态 + 一句话解释，下面是**时间线**（提交申请 → 商家处理 → 退款完成），
  商家备注直接展示
- **后台**：售后处理菜单 → 按状态筛选 → 同意（二次确认，提示"退款金额/是否恢复库存"）/ 拒绝（必填理由）

### 购物流程手感优化（`web-flow` 41→46）

**目标是"和常规购物软件基本一致"**。先按主流电商的路径逐项对照，找出真正影响手感的地方，再动手：

| 问题（改之前） | 主流做法 | 现在 |
|---|---|---|
| 购物车勾选/改数量/删除后都 `await load()` **整页重拉**，点一下列表闪一次 | 本地立即生效，静默同步 | **乐观更新 + 失败回滚**（`checked` / `quantity` / `totalPrice` 先改本地） |
| 步进器连点 → 每一下一个请求（请求风暴） | 合并 + 防抖 | 按条目 **350ms 防抖**，只发最后一次 |
| 全选循环发 **N 个请求**（10 件=10 次往返） | 一次批量接口 | 新增 `PUT /api/v1/portal/cart/checked`，**一条 SQL 改完** |
| 失效商品只能一条条划走 | 顶部提示 + 一键清理 | 顶部「N 件商品已失效 / 清空失效商品」 |
| 待支付订单没有剩余时间 | 倒计时，到点自动关闭 | 订单卡片显示「剩 mm:ss 自动关闭」，归零自动刷新成"已关闭" |
| 已关闭/已完成订单不能再买 | 「再次购买」 | 一键把商品按原数量加回购物车（部分下架也能加，提示哪几件失败） |
| 售罄商品点了才报错 | 按钮直接置灰 | 两个按钮变「已售罄」并禁用 |

**关于"乐观更新"的两个细节**（做错了反而更糟）：

- **失败必须回滚**：先改本地、请求失败就把值恢复并重新拉一次，否则用户会看到"勾上了但结算金额不对"。
- **角标别再多发一次请求**：`cart.sync(list)` 直接用已有数据算件数（老实现每次改动都 `cart.refresh()` → 又一次 `GET /cart`）。

**验证**（`web-flow` 41 → **46/46**，新增 5 条）：

```
[PASS] 订单列表：待支付订单显示倒计时 :: containsCountdown=true
[PASS] 订单列表：待支付订单卡片不显示「再次购买」 :: card=none
[PASS] 已关闭订单显示「再次购买」 :: len=175
[PASS] 再次购买把商品加回购物车 :: hash=#/cart containsItem=true
[PASS] 售罄商品：按钮显示「已售罄」且全部禁用 :: text=true buttons=2|2（期望 2|2）
```

批量全选接口另外单独验过：2 件商品 `checked=1,1` → 取消全选 `0,0`，非法参数 `checked=9` 返回 `400`。

### 本地部署测试（Docker，`deploy-verify.sh` 36/36 + `deploy-browser-check.mjs` 8/8）

**为什么单开一节**：单元测试和接口测试都是"对着源码跑"；这一节是"对着构建出来的镜像跑"。
两者能发现的问题不一样 —— 这次就抓到一个**只在部署形态下才出现、会直接挡住上线**的 bug（见下）。

**怎么跑**（本机 docker 在 WSL 里、且要 root；Docker Hub 被墙，用国内镜像源拉基础镜像再 retag）：

```bash
# 1) 拉基础镜像（docker.m.daocloud.io 可达；拉完 retag 成标准名字，compose 就不用改）
docker pull docker.m.daocloud.io/library/mysql:8.0            && docker tag ... mysql:8.0
docker pull docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 && ...
docker pull docker.m.daocloud.io/library/eclipse-temurin:17-jre && ...
docker pull docker.m.daocloud.io/library/node:22-alpine        && ...
docker pull docker.m.daocloud.io/library/nginx:1.27-alpine     && ...

# 2) 拷到 WSL 原生文件系统再构建（/mnt/d 是 drvfs，npm/maven 在上面容易出权限/符号链接问题）
tar -C /mnt/d/projects/mall --exclude=node_modules --exclude=target --exclude=.git \
    --exclude=logs --exclude=tmp -cf - . | tar -C /root/mallbuild -xf -

# 3) 起栈（宿主端口换成 1xxxx，避开本机已占用的 3306/8081/8090/5173）
cd /root/mallbuild
cp /mnt/d/projects/mall/.env.example .env      # 填三个密钥
docker compose -p malltest -f docker-compose.localtest.yml up -d --build

# 4) 验证
bash scripts/deploy-verify.sh                                   # 36 项：容器/迁移/prod 行为/Cookie/nginx/端到端
PORTAL_WEB=http://localhost:15174 PORTAL_API=http://localhost:18090 \
  node scripts/deploy-browser-check.mjs                          # 8 项：真浏览器登录
```

产物：H5 `http://localhost:15174`、后台 `http://localhost:15173`。

**验证覆盖**：

| 分组 | 内容 |
|---|---|
| A 容器 | 5 个服务都在跑；mysql healthy 且是 8.0.46 |
| B 迁移 | **全新数据卷**上 initdb 依次跑完 V1..V7：9 张表、`t_refresh_token` 存在、demo 数据 5 个商品、`t_address.phone` 已被 V6 加宽到 128 |
| C prod 行为 | `health`=200、`prometheus`=200、`info`/`metrics`=404、`/v3/api-docs`=404、`/webjars/**`=404、无凭证=401、公共接口=200 |
| D prod Cookie | `Secure` + `HttpOnly` + `SameSite=Lax` 三个标记齐全；access TTL **正好 900s**；refresh Cookie 也在 |
| E nginx | 生产构建产物可访问；`nosniff`/`X-Frame-Options` 生效；`/api` 真反代到后端 |
| F 端到端 | 注册→地址→下单在容器里跑通；订单快照在容器 DB 里是 `enc:v1:<kid>:` 密文；详情接口回明文；清理干净 |
| 浏览器 | UI 登录成功、localStorage 无 token、JS 看不到登录 Cookie、**受保护接口能通**、控制台零报错 |

**这次部署测试抓到的第二个 bug：中文全变乱码（`小米 14` 显示成 `å°ç±³ 14`）**

不是前端的问题，是**数据在入库时就被写坏了**。证据：

```
utf8mb4 客户端读：  name=å°ç±³ 14   HEX=C3A5C2B0C28F...   LENGTH=15（"小米 14" 正常应是 9）
latin1  客户端读：  name=小米 14      ← 能读出来，说明是「UTF-8 字节被当 latin1 又编码了一次」
SHOW VARIABLES:    character_set_client / connection / results = latin1   ← 根因
```

根因：compose 只给了 `--character-set-server=utf8mb4`（这只设服务端默认），
而 **initdb 用的 `mysql` 客户端是以 latin1 建连接的** —— 它把 UTF-8 文件里的中文按 latin1
发过去，服务端再按 utf8mb4 存 → 双重编码。应用侧怎么改都救不回来，只能重建库。

修法（两条一起）：

1. 每个 `sql/V*.sql` 开头加 `SET NAMES utf8mb4;`（显式声明"这些字节是 UTF-8"，与客户端默认无关）
2. mysqld 启动参数加 `--character-set-client-handshake=FALSE`（忽略客户端声明的字符集，
   统一按服务端 utf8mb4 处理连接）

**这类问题会不会再犯？** 已经把「中文没坏」做成部署测试的固定断言（H 组 4 条）：

```
[PASS] H product api returns real CJK text
[PASS] H product api has no latin1 mojibake chars
[PASS] H no double-encoded rows in the database :: badRows=0
[PASS] H server overrides client charset (utf8mb4) :: character_set_client=utf8mb4
```

浏览器冒烟里也加了一条看**渲染后文本**的断言（必须有 CJK、且不能出现 U+00C0–U+00FF）——
因为用户看到的就是渲染结果，接口正常但页面乱码也算失败。
> 最后那条「受保护接口能通」是这次部署测试最有价值的一条：prod 的 Cookie 带 `Secure`，
> 本地没有 HTTPS，只有浏览器把 `localhost` 当安全上下文时才会发送它。curl 测不出这件事。

**这次部署测试抓到并修掉的 bug（只在部署形态下出现）**：

同源部署时，浏览器对 **POST 也会带 `Origin`**（fetch 规范），nginx 原样转发 → Spring 的
`CorsFilter` 看到 Origin 就按跨域处理 → 白名单里只有 dev 的 5173/5174，于是**登录直接 403
`Invalid CORS request`**。curl 复现：

```
no-origin   -> 200
Origin: http://localhost:15174 -> 403 Invalid CORS request
```

修法两条一起（互相兜底）：
1. 两个前端 `nginx.conf` 的 `location /api/` 里 `proxy_set_header Origin "";`
   —— 同源请求本来就不该走 CORS（和 dev 的 vite 代理去掉 Origin 是同一个道理，§8 第 39 条）
2. `docker-compose*.yml` 显式给 `MALL_CORS_ALLOWED_ORIGINS`（默认 dev 端口那套值不能带到线上）

### 令牌生命周期：15 分钟 access + 可吊销 refresh（`token-lifecycle-verify.ps1` 26/26）

**问题**：access token 有效期 7 天且**无法吊销** —— 登出只清了浏览器 Cookie，已经泄露出去的 token
在到期前一直能用。（上一轮审计里"仍未做"的最后一条。）

**做法**：

- access（JWT）**15 分钟**；refresh **7 天**，存 `t_refresh_token`，**只存 SHA-256 哈希**
  （它等价于一个长期口令，明文入库等于把用户会话直接交出去）。
- refresh **用一次即换新**（轮换）：`UPDATE ... WHERE token_hash=? AND revoked=0 AND expires_at>now`
  看影响行数，并发重放只有一个能成功。
- 用 refresh 换 access 时**重新检查账号状态** —— 被禁用的账号不能靠 refresh 一直续命。
- 登出 = 吊销库里那一行 + 清三个 Cookie。
- 前端：401 → **单飞**刷新（多个并发 401 只刷一次）→ 重试原请求一次（`__retried` 标记防死循环）。

**验证**（26 项，`tmp\token-lifecycle\summary.txt`）—— 把 access TTL 临时调成 **5 秒**来真验过期，而不是"看着像会过期"：

```
[PASS] A2 access JWT lifetime is ~5000ms (claims exp-iat) :: exp-iat=5s
[PASS] A3 access cookie carries the short Max-Age :: maxAge=5
[PASS] A4 refresh cookie lives much longer (7 days) :: maxAge=604800
[PASS] B1 expired access is rejected with 401 :: http=401
[PASS] C1 refresh call succeeds :: code=200
[PASS] C4 replaying the OLD refresh fails (one-time use) :: code=401
[PASS] D3 every refresh row for this user is revoked in the DB :: rows=2 active=0
[PASS] D4 refresh after logout is rejected :: code=401
[PASS] E1 Bearer-only request works (scripts unchanged) :: http=200
[PASS] F2 default access TTL is 15 minutes again :: exp-iat=900s
```

**仍未做（如实列）**：

1. access token 本身仍**不可吊销**：15 分钟内泄露的 token 照样能用。要彻底解决得引入 jti 黑名单
   （每个请求查一次缓存），成本明显上升，暂不做。
2. `revokeAll(userId)` 已经实现但**没暴露成"踢下线"接口**（改密码时应当调用它）。
3. refresh 没有设备/会话列表，用户看不到"我在哪些设备登录过"。

### 密钥轮换（`crypto-rotate-verify.ps1` 28/28）

**问题**：只有一把密钥、改了就解不开 —— 密钥一旦泄露，唯一出路是"全部数据作废"，没有补救手段。

**做法**：

- 密文格式升级为 `enc:v1:<keyId>:<base64url(iv‖密文‖tag)>`，其中 `keyId` = SHA-256(secret) 的前 8 个 hex。
  带 keyId 的好处很实际：**哪些数据还在旧密钥下，一条 SQL 就能精确筛出来**，不必全表解密试探。
- `mall.crypto.previous-secrets`（**仅用于解密**）+ `mall.crypto.re-encrypt-on-startup`（一次性开关）。
- 早期格式 `enc:v1:<payload>`（没有 keyId）照样能读（先试当前密钥、再逐个试历史密钥），
  所以这次格式升级**不需要停机重写历史数据**。
- 重加密用「旧值」做乐观条件（`AND <列> = 旧值`），幂等且双实例并发安全。

**三步操作（不用写代码）**：

1. `MALL_CRYPTO_SECRET=新` + `MALL_CRYPTO_PREVIOUS_SECRETS=旧` → 重启：新写入用新密钥，旧数据仍可读
2. 再加 `MALL_CRYPTO_RE_ENCRYPT_ON_STARTUP=true` → 重启一次，把旧数据全部重加密
3. 确认「不在新 kid 下」的行数为 0 后，清掉 `previous-secrets` 和开关

**验证**（28 项，`tmp\crypto-rotate\summary.txt`）—— 不是"看代码觉得能行"，是真的重启四次跑出来的：

| 阶段 | 断言 |
|---|---|
| **A** 新密钥 + previous=旧 | 旧数据仍可读（`code=200 rows=3 ciphertextLeak=False`）；且**数据物理上还没迁**（说明"换密钥"本身不重写数据） |
| **B** 开重加密 | `t_order` / `t_user.phone` / `t_address.phone` 里**没有一行**还挂在旧密钥下 |
| **C** 只留新密钥（不给 previous） | 仍可读 —— **这条才是真正的证明**：如果重加密没发生，这里必然解密失败 |
| **D** 还原 | 数据回到默认密钥，环境恢复成默认，普通启动仍可读 |

> 我第一版演练有两条断言写错了：假设"现有数据一定在某个 keyId 下"。
> 实际上库里当时是**上一版代码写的无 keyId 旧格式**，所以正确判据是「存在加密 PII」+
> 「轮换后数据仍未迁到新 kid」，而不是「在旧 kid 下」（§8 第 59 条）。

### 登录态：localStorage → HttpOnly Cookie + CSRF（`auth-cookie-verify.ps1` 27/27）

**问题**：token 存 localStorage，**任何 XSS 都能读走**，而且读走后可以离线用满 7 天有效期。
这是上一轮审计里我自己列的「仍未做」第 2 条。

**做法**：

- 登录成功由服务端下发 `mall_portal_token` / `mall_admin_token`：**HttpOnly** + `SameSite=Lax` + `Path=/`，
  prod 还要 **Secure**（`ProductionConfigGuard` 强制，等于要求 prod 必须跑在 TLS 后面）。
- 另发一个 **JS 可读**的 `mall_csrf`（双提交令牌），前端把它放进请求头 `X-CSRF-Token`。
- `JwtAuthFilter` 支持两种凭证，顺序：`Authorization: Bearer`（脚本 / 服务端）→ Cookie（浏览器），
  并把**来源**记到请求属性 `mall.auth.source`。
- `CsrfFilter` **只对「靠 Cookie 认证的写请求」校验** —— Bearer 客户端完全不受影响
  （否则所有自动化脚本都得改，且它们本来就没有 CSRF 风险）。
- 新增 `POST /auth/logout`：Cookie 是 HttpOnly 的，前端删不掉，必须服务端清（下发 `Max-Age=0`）。
- 前端：`localStorage` 只存 `user`（且把其中的 token 字段抹成空串），
  `isLoggedIn()` 改用 user 判断（凭证已经不在前端了）。

**为什么有了 SameSite 还要 CSRF**：Cookie 是浏览器自动携带的，攻击者站点可以让浏览器发起跨站 POST。
`SameSite=Lax` 能挡掉大部分，但那是**浏览器行为**（老浏览器 / 某些 WebView 不遵守）；
双提交是服务端强制的，不依赖浏览器。

**验证**（27 项，`tmp\auth-cookie\summary.txt`）：

```
[PASS] A2 token cookie is HttpOnly (JS cannot read it) :: ...; HttpOnly; SameSite=Lax
[PASS] A5 CSRF cookie is set and JS-readable (not HttpOnly) :: mall_csrf=...; SameSite=Lax
[PASS] B1 /user/me works with cookie only (no Authorization header) :: http=200
[PASS] B3 no credential at all -> 401
[PASS] C1 cookie + POST without X-CSRF-Token -> 403 :: {"code":403,"message":"CSRF 校验失败..."}
[PASS] C4 cookie + POST with correct X-CSRF-Token -> accepted
[PASS] D1 Bearer + POST without CSRF header -> accepted (scripts keep working)
[PASS] E1 logout returns clearing Set-Cookie :: mall_portal_token=; Path=/; Max-Age=0; HttpOnly
[PASS] E3 old session no longer authenticates -> 401
[PASS] F3 a portal-only session cannot touch the admin API -> 401
```

浏览器侧由 `web-flow` 断言（41/41）——这两条才是「XSS 偷不走」的直接证据：

```
[PASS] 登录后 localStorage 里不保存 token（凭证只在 HttpOnly Cookie） :: token=none userHasJwt=false
[PASS] JS 读不到登录 Cookie，只读得到 CSRF Cookie :: cookie=mall_csrf=443990289556429d962db87972063ff0
```

**代价 / 取舍（如实记录）**：

- 登录响应体里**仍然返回 token**（给脚本和服务端调用走 Bearer）。浏览器端忽略它。
  严格说，XSS 若恰好在"登录那一刻"运行，仍可能截到这个响应；但它拿不到**持久化在浏览器里的凭证**，
  也没法在用户下次访问时复用。要彻底消除，得让登录响应按"是否需要 body token"分流。
- 仍未做 refresh token / 主动吊销（7 天有效期内一直可用）。

### 监控（Prometheus，`metrics-verify.ps1` 16/16）

只有 `/actuator/health` 时，出问题只能看到「进程还活着」，看不到「业务健不健康」。现在两个后端都暴露
`/actuator/prometheus`（`micrometer-registry-prometheus` 声明在 `mall-core`，两个应用都继承）：

- **通用指标**：JVM、HikariCP 连接池、`http_server_requests_seconds_*`（按 uri/status 分）
- **业务指标**（`OrderMetrics`，埋在 `placeOrder` 里）：
  - `mall_orders_created_total` —— 成功建单数。**幂等重放不计**，否则客户端一重试曲线就被刷高
  - `mall_orders_stock_shortage_total` —— 条件 UPDATE 锁库存失败（库存不足）的次数
- 暴露范围：dev `health,info,metrics,prometheus`；**prod `health,prometheus`**（prod 不暴露 info/metrics 明细）

建议的告警规则（比「CPU 高」有用得多）：

```yaml
- alert: NoOrdersInBusinessHours
  expr: rate(mall_orders_created_total[5m]) == 0        # 营业时段下单掉到 0 = 链路断了
- alert: StockShortageSpike
  expr: rate(mall_orders_stock_shortage_total[5m]) > 10 # 陡增 = 被抢空或在刷单
- alert: OrderApiErrors
  expr: rate(http_server_requests_seconds_count{uri=~".*portal/order.*",status=~"5.."}[5m]) > 0
```

验证（`scripts\metrics-verify.ps1`，16/16）：端点可抓取、通用与业务指标都在、**建单 +1**、
**幂等重放不计**、**库存不足 +1**（`tests` 里也有 `OrderServiceImplTest` 用 `SimpleMeterRegistry` 盯着这两条）。

### 后台 CRUD 的 UI 用例（`admin-crud-verify.mjs` 16/16）

`p2-verify` 只测接口、`web-check` 只测渲染、`web-flow` 只覆盖订单发货 —— **后台最常用的
「建分类 → 建商品 → 上下架 → 删除」此前没有一次是从界面上点过的**。现在补上：

```
1. 分类：新增顶级分类 → 表格出现 → 编辑改名 → 禁用 → 重新启用
2. 商品：新增（分类树选择 + SKU 行填写）→ 列表出现 → 下架 → 详情页显示下架 → 删除
3. 删除分类（二次确认）
```

```bash
node scripts\admin-crud-verify.mjs      # 需要：两个后端 + admin dev server + Chrome
# 报告：tmp\admin-crud\report.txt
```

### 数据库备份 / 恢复（`backup-restore-verify.ps1` 13/13）

```bash
# 备份（--single-transaction 一致性快照，不锁写入；保留最近 7 份）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup-db.ps1

# 恢复到临时库（默认 mall_restore，绝不会碰 mall）
powershell ... -File scripts\restore-db.ps1 -File tmp\backup\mall-YYYYMMDD-HHmmss.sql

# 真的演练一遍：备份 -> 恢复 -> 逐表比对 -> 清理 -> 确认主库零漂移
powershell ... -File scripts\backup-restore-verify.ps1
```

**没有恢复过的备份不算备份**，所以这个演练是必须的。最后一次（`tmp\backup\summary.txt`）：

```
[PASS] 1.2 dump contains schema (CREATE TABLE) :: 16.1 KB
[PASS] 1.3 dump contains data (INSERT INTO)
[PASS] 3.1 every table has the same row count :: t_order=2 t_order_item=2 t_product=6 t_sku=10 t_user=5 ...
[PASS] 3.2 order rows/amount/maxId identical :: live=2|19997.00|105 restored=2|19997.00|105
[PASS] 3.3 product rows/price/sales identical :: live=6|29294.00|503 restored=6|29294.00|503
[PASS] 3.5 sku rows/stock/locked identical :: live=10|537|0 restored=10|537|0
[PASS] 3.4 chinese text survived the round trip :: 男士休闲夹克
[PASS] 4.1 scratch database dropped
[PASS] 4.2 live database untouched by the rehearsal :: no drift
```

两个刻意的设计：`mysqldump` **不加 `--databases`**（这样 dump 里没有 `CREATE DATABASE`/`USE`，
才能恢复到任意库名，演练才不需要动生产库）；`restore-db.ps1` 覆盖 `mall` 必须显式带 `-Force`。
| 前端全局错误兜底 + UI 库按需引入 | ✅ | 见下方「P2 前端错误兜底与包体」；`web-check` 24/24 |
| 生产加固（CORS 白名单 / 登录限流 / traceId / 接口版本） | ✅ | 见下方「P4 生产加固」；`security-verify.ps1` 17/17 |
| 日志与磁盘（避免长期跑吃满磁盘） | ✅ | 只打 stdout + docker `json-file` 上限，见下方「日志策略」 |
| 数据库备份 / 恢复 | ✅ | `scripts\backup-db.ps1` / `restore-db.ps1`，真演练 `backup-restore-verify.ps1` 13/13 |
| 测试覆盖（H5 取消订单） | ✅ | `web-flow.mjs` 39/39（新增取消订单 + 释放库存断言） |
| 监控指标（Prometheus） | ✅ | `/actuator/prometheus` + 两个业务计数器，见下方「监控」；`metrics-verify.ps1` 16/16 |
| 敏感数据加密（密码 / PII / 日志 / 密钥） | ✅ | 见下方「敏感数据安全审计」；`security-data-verify.ps1` **34/34** |
| 登录态改 HttpOnly Cookie + CSRF | ✅ | 见下方「登录态迁移」；`auth-cookie-verify.ps1` **27/27** |
| PII 加密密钥轮换（双密钥 + 重加密） | ✅ | 见下方「密钥轮换」；`crypto-rotate-verify.ps1` **28/28** |
| 令牌生命周期（短 access + 可吊销 refresh） | ✅ | 见下方「令牌生命周期」；`token-lifecycle-verify.ps1` **26/26** |
| HTTPS 传输加密 | ⚠️ | 只给了模板 `deploy/nginx-tls.conf.example`，本机没有 nginx/证书无法验证；但**部署测试已确认 prod 的 Secure Cookie 与安全响应头在真实容器里生效** |
| 本地部署测试（Docker） | ✅ | 见下方「本地部署测试」；`deploy-verify.sh` **36/36** + `deploy-browser-check.mjs` **8/8** |
| 购物流程手感优化 | ✅ | 见下方「购物流程手感优化」；`web-flow` **57/57** |
| 售后（退款 / 退货退款） | ✅ | 见下方「售后」；`after-sale-verify.ps1` **44/44** + UI 11 条 |
| 支付（收银台 / 支付单 / 三渠道） | ✅ | 见下方「支付」；`payment-verify.ps1` **34/34** + UI 6 条 |
| 退款打到渠道 | ✅ | 见下方「退款真正打到渠道」；`after-sale-verify.ps1` **52/52**（含失败路径） |
| 退款状态收敛（通知 + 查单） | ✅ | 见下方「退款状态收敛」；`after-sale-verify.ps1` **57/57** |
| 支付查单兜底（回调丢失） | ✅ | 见下方「支付查单兜底」；`payment-verify.ps1` **39/39** |
| HTTPS 落地（TLS 终止 + 安全头） | ✅ | 见下方「HTTPS 落地」；`https-verify.sh` **18/18** |
| 对账（支付 + 退款逐笔比对 + 差异处理） | ✅ | 见下方「对账」；`reconcile-verify.ps1` **35/35** |
| 测试覆盖（后台 CRUD 的 UI） | ✅ | `admin-crud-verify.mjs` 16/16（分类增删改禁用、商品新增/下架/详情/删除） |

### P1 下单幂等（22/22）

**问题**：`create()` 原本是「读购物车选中项 → 锁库存 → 建单 → 清选中项」。双击提交 / 网络重试 / 多标签页时，
两个请求会**都读到同一批选中项**，各建一单、各锁一次库存 —— 库存不会超卖（SKU 条件 UPDATE 兜住了），
但会**凭空多占库存**并产生**重复订单**。

**解决（两层，互相兜底）**：

1. **购物车行锁**：`cartMapper.selectList(... .last("FOR UPDATE"))`。同一用户的并发提交在第一步就串行化：
   后到者要等前者提交，而**锁定读总是读最新已提交数据**（不受事务快照影响），于是它看到选中项已被清空 → 2004。
2. **幂等键**：`t_order.request_id` + `uk_user_request (user_id, request_id)`（见 `sql\V5__order_request_id.sql`）。
   调用方在**一次结算**里固定不变地传同一个值；命中已有单就直接把那一单返回（同一个 orderId）。
   唯一键取 `(user_id, request_id)` 而不是单列 `request_id`，是为了避免「两个用户传了同一个 requestId →
   按 user 查不到、插入又撞键 → 500」。
   不传 requestId 时退化为普通下单（靠第 1 层兜底），老客户端不受影响。

前端：`CheckoutView` 在组件挂载时生成一个 requestId，重试提交沿用同一个（离开再回来会重新挂载 → 换新键）。
⚠️ `crypto.randomUUID()` **只在安全上下文（https / localhost）可用**，手机用局域网 IP 打开 H5 时它是 undefined，
代码里有兜底实现，别删。

**验证**：`scripts\idempotency-verify.ps1`（22/22，产物 `tmp\p1-idem\summary.txt`）。
用 `HttpClient.PostAsync` 不 await 制造真并发（一次跑两个、再一起收结果）：

| 用例 | 断言 |
|---|---|
| A 同 requestId 并发 2 次 | 两次都 200、返回**同一个** orderId、库里只 1 行、库存只锁一次（+2） |
| B 不同 requestId 并发 | 恰好一个 200、一个 2004、只多 1 单、库存只锁一次（+3） |
| C 顺序重试同 requestId | 返回原单、无新单、库存不变 |
| D 完全不传 requestId 并发 | 仍然只 1 单（第 1 层兜底生效） |
| E requestId 超 64 字符 | 400，且在拿锁之前就被拒 |
| F 畸形请求体（字符串形式的 JSON） | 400（**修复前是 500**，见下） |

**顺带修掉一个真实缺陷**：客户端发畸形请求体时，`HttpMessageNotReadableException` 会落到 `Exception` 兜底里
变成 **code 500** —— 把**客户端的错算进了服务端错误率**，上线后会误导告警。已在 `GlobalExceptionHandler`
里单独处理成 400，并加 `GlobalExceptionHandlerTest` 守住。

### P5 收尾验证（接口文档 / 演示数据 / Docker 编排）

**接口文档** —— 证据 `tmp\p5\verify.txt`：

- 新增 `com.mall.core.config.OpenApiConfig`（两个应用共用，为此 `mall-core` 也引了 knife4j starter）：
  title `Mall 商城 API` / version `1.0.0` / 安全方案 `bearer-jwt`，描述里写清了 `R<T>` 与 401 的约定
- `/v3/api-docs` 实测：mall-admin `paths=14`、mall-portal `paths=20`，两边 `securitySchemes=bearer-jwt`
- `/doc.html` 在 8081 与 8090 都是 **HTTP 200**

**演示数据（商品图）**：

- 原 `t_product.main_image` 指向 `/img/p1.jpg` 这类相对路径，**项目里根本没有这些文件** → 两个前端的商品图全是坏的
- 现在图片是 10 张自绘 SVG，放在 `backend/mall-core/src/main/resources/static/img/`。
  放在 core 而不是各前端 `public/` 的理由：`classpath:/static/**` **连依赖 jar 里的也算**，
  所以一份文件 mall-admin 与 mall-portal 都会当静态资源对外提供，前端只需各加一条 `/img` 代理。
- 实测：`:8081/img/p1.svg` 与 `:8090/img/p1.svg` 都是 **HTTP 200 `image/svg+xml`（996 字节）**；`:8081/img/nope.svg` 是 **404**
- `V3__seed_demo.sql` 图片后缀改成 `.svg`（幂等，重跑一遍即可同步已有库）

**Docker 编排**：

- `docker-compose.yml`（mysql + mall-admin + mall-portal + admin-web + portal-web）、
  `backend/Dockerfile`（`MODULE` 参数化）、两个前端各自的 `Dockerfile` + `nginx.conf`
- ⚠️ **本机没装 Docker，没有实际 `up` 过**。做的是离线校验：`tmp\p5\yamlcheck\check.mjs` 用 `yaml` 解析 compose 后断言
  21 项（服务清单 / 卷 / healthcheck / initdb 挂载 / 依赖健康检查 / JDBC 指向 `mysql` 服务 /
  超时关单只在 admin 开 / 前端 nginx 反代目标与 `/img`）→ **21/21 通过**（`tmp\p5\compose-check.txt`）
- `sql/schema.sql` 改名为 `sql/V1__schema.sql`（原因见 §5）

---

## 8. 踩坑清单（**这些坑已经踩过，别重复**）

1. **`mvn clean` 删不掉 jar** → 应用还在跑。先 `stop.bat`。
2. **绝对不要 `taskkill /IM java.exe`** → IDEA 自己的进程也是 `java.exe`（本机同时有 5 个），会误杀用户的 IDE 和正在调试的项目。要杀就按 **PID** 或按**命令行匹配 `mall-*.jar`**。
3. **`start /b java -jar` 起的进程，WMI 里 `CommandLine` 可能是空** → 无法按命令行识别，只能靠端口。停止脚本已同时用两种方式兜底。
4. **端口 8080 被占用** → 是使用者从 IDEA 启动的进程，**不要杀**。mall-portal 已改到 8090。
5. **内联 PowerShell 引号被吃** → 写成 `.ps1` 文件执行。
6. **控制台把中文显示成 `????`** → 是管道编码，不是接口问题；要判断内容请落盘成文件再读。
7. **`JAVA_HOME` 在 Windows 上指向 `D:\jdks`（错的）** → 脚本里必须显式覆盖。
8. **`exec` 只有 ~10 秒预算** → 长命令后台跑 + 轮询。
9. **启动慢**：两个服务各约 15 秒（首次更久，要下依赖）。
10. **Maven `install` 必须写 `C:\Users\Administrator\.m2`（在工作区之外）** → 受限沙箱/低权限环境下会 BUILD FAILURE，报错是
    `Failed to install artifact com.mall:mall:pom:1.0.0: ...tmp ->`，看着像磁盘问题其实是没权限。需要在能写 `.m2` 的权限下构建。
11. **含中文的 `.ps1` 必须存成 UTF-8 带 BOM** → Windows PowerShell 5.1 对无 BOM 文件按 GBK 解析，中文后面的引号会被吞掉，
    报 `字符串缺少终止符` / `Unexpected token '}'`（pwsh 7 不受影响）。本项目脚本已带 BOM，改完记得保持。
12. **PowerShell 里 `@('--data-binary', '@' + $file)` 会被拆成 3 个参数** → curl 报 `Failed to open`、HTTP 码为空。
    必须先 `$bodyArg = '@' + $file` 再放进数组（`scripts\p2-verify.ps1` 里有注释）。
13. **`mysql.exe` 的 `Using a password ...` 警告走 stderr** → 在 `$ErrorActionPreference='Stop'` 下会被当成
    NativeCommandError 中断脚本；要局部 `Continue` + `2>$null`。
14. **自动化脚本里别用 `cmd /c run.bat`** → `start /b` 起的 java 继承了脚本的 stdout 管道，父进程等不到 EOF 会被上层超时杀掉，
    **连带把刚启动的服务也杀掉**。`scripts\p2-verify.ps1` 的做法：`Start-Process -RedirectStandardOutput/-RedirectStandardError`
    + 写 `logs\<name>.pid`。（`run.bat` 本人用/手动起服务仍然没问题。）
15. **WMI 可能被策略拒绝**（`Get-CimInstance` → 拒绝访问），按命令行匹配就失效 → `stop-apps.ps1` 已加
    `logs\<name>.pid` 与「启动日志里的 PID」两级兜底；实测在 WMI 被拒、`Get-NetTCPConnection` 也拿不到监听项的环境里仍能停干净。
16. **测试脚本要能重复跑**：本项目的验证数据统一用 `P2 Test` / `p3test` 前缀，脚本开跑前先真删
    （`DELETE ... WHERE name LIKE 'P2 Test%'` 等），否则上一轮失败残留会撞唯一/同名校验（实测撞出 `2008` 并连锁失败）。
    `p3-verify.ps1` 跑完还会再删一次并断言「无残留 + 无残留锁定库存」。
17. **`Start-Process -ArgumentList` 只是用空格把数组拼成命令行**（不会自动加引号）→ 带空格的参数
    （`Content-Type: application/json; charset=utf-8`、`Authorization: Bearer xxx`）会被拆散，curl 报
    `Could not resolve host: application` / `Failed to open`。要么自己加内嵌引号，要么别用 Start-Process：
    并发测试直接用 `System.Net.Http.HttpClient` 连发两次 `PostAsync`（不 await 第一个）最省事，见 `p3-verify.ps1`。
18. **`@RequestParam` 缺参数 / 类型不符**原本被 `Exception` 兜底成 code 500，现已单独处理成 **400**；404/405 也已归位（`GlobalExceptionHandler`）。
19. **pnpm 11 有两个坑叠加**：① 默认拦截依赖的安装脚本，不配 `allowBuilds: esbuild: true` 就 `ERR_PNPM_IGNORED_BUILDS`；
    ② 这个配置**只能写在 `pnpm-workspace.yaml`**，写在 package.json 的 `pnpm` 字段里会被**静默忽略**（只留一条 WARN，看起来像生效了）。
    两个前端工程都已配好（见 §4.1）。
20. **Vant 4 没有 `GoodsAction`**（那是 Vant 3 的名字，Vant 4 叫 `ActionBar`）。模板里写 `<van-goods-action>` **不会报任何错**：
    Vue 找不到组件就把标签当原生元素原样输出、props 当普通属性，于是**构建和类型检查全绿，页面上整条操作栏却消失了**。
    实测踩过（H5 商品详情页少了「加入购物车/立即购买」）。
    - 排查：看渲染后的 DOM——**如果还残留 `<van-*>` / `<el-*>` 开标签，就是没注册的组件**（注册成功会被渲染成 `<div class="van-...">`）。
      注意别拿 CSS 里的类名计数当证据（Vant 的样式表里全是这些类名，登录页也有）。
    - 控制台会有一条 Vue 告警 `Failed to resolve component: van-goods-action`，`scripts\web-check.mjs` 正是以这类告警为失败条件。
21. **漏写 `onMounted(load)` 是最阴的一类**：`load()` 定义了却没人调用 → 数据永远不加载 → 页面一直显示空态/兜底文案，
    而 build 与类型检查全绿（未使用的 import 不会报错）。实测踩过（H5 商品详情页恒显示「商品不存在或已下架」，用户报上来的）。
    所以前端验证必须断言「页面渲染出了商品名/SKU 这些真实内容」，而不是只断言 HTTP 200。
22. **同一路由只换参数时组件会被复用**：`/product/1` → `/product/2`（`/order/:id` 同理）属于**同一个路由记录**，
    Vue Router 会复用组件实例、`onMounted` 不再执行 → 页面停在旧数据上（实测踩过：H5 从商品 2 跳到不存在的商品，页面还显示商品 2）。
    修法是给承载这些路由的 `router-view` 加 `:key="$route.fullPath"` 强制重挂载：
    - H5：`portal-web/src/App.vue`（顶层路由都在根 `router-view` 里）
    - 管理后台：`admin-web/src/layouts/AdminLayout.vue`（业务路由都是它的子路由，它本来就带 key；
      根 `App.vue` 只有 `/login` 一条无参路由，不需要）
23. **写前端测试时「路由变了」≠「内容渲染好了」**：hash 路由下 `location.hash` 是立刻变的，页面数据还在请求中。
    断言必须 `waitFor(页面上出现某段文字)`，只判 hash 会读到空页面而误判。实测踩过（刚提交订单跳到订单详情就断言「待支付」，
    实际读到的是还没加载完的空页）。
24. **未认证是真正的 HTTP 401 且 body 为空**（见 §6.1），测试脚本里直接 `fetch(...).json()` 会抛
    `Unexpected end of JSON input`，看着像脚本崩了，其实是协议本身如此。要先 `await r.text()` 判空再解析。
    （`scripts\web-flow.mjs` 的 `api()` 就是这么写的。）
25. **别把自己的断言错误当成产品 bug**：实测踩过一次——H5 详情页数量步进器默认 1，测试没点「+」就去加购，
    然后断言「购物车 ¥200.00 / locked=2」，实际是 ¥100.00 / locked=1。
    正确的处理是**把步进器也纳入用例**（点一下 `+` 再断言），而不是把期望值改小——后者会让测试失去覆盖。
26. **Spring Boot 在 `@ConfigurationProperties` 绑定路径上，对「解析不了的占位符」是原样保留、不报错**，
    和 `@Value` 的行为完全不同。实测：配置写 `mall.jwt.secret: ${MALL_JWT_SECRET}` 且不设环境变量时，
    应用**照样启动**，密钥变成字面量 `"${MALL_JWT_SECRET}"`，数据库口令同理 → 直到连库才以
    `Access denied for user 'root'@'localhost' (using password: YES)` 这种**指向完全错误方向**的错误暴露出来。
    根因是 `PropertySourcesPlaceholdersResolver` 构造 `PropertyPlaceholderHelper` 时用了
    `ignoreUnresolvablePlaceholders = true`。
    → **结论：敏感配置的缺失必须在启动时显式校验**（见 `ProductionConfigGuard`），不能指望 YAML 占位符抛异常。
27. **写单测时 `MockEnvironment` 与真实 Environment 行为不同**：`MockEnvironment.getProperty("x")` 会把 `${VAR}`
    拿去解析、解析不到直接抛 `IllegalArgumentException`；而真实环境是第 26 条那样原样保留。
    要测「guard 碰到未解析占位符该怎么办」这类逻辑，得用 Mockito 直接喂给它真实会看到的值，别用 `MockEnvironment`。
    另外：**别手数字符串长度**——我把一个 32 字符的串当成 31 字符去测「长度不足」，白跑一轮；
    用 `"x".repeat(n)` 这种写法让长度一眼可见。
28. **`write_to_file` 写出的 `.ps1` 没有 BOM**，而 Windows PowerShell 5.1 会按 GBK 解析无 BOM 文件 —— 只要脚本里
    出现中文，后面的引号就可能被吞掉，报 `Unexpected token ')'` 之类（§8 第 11 条同一个坑）。
    → 给自动化脚本的规矩：**一律写纯 ASCII**（要输出的中文由被测对象提供，不要写在脚本字面量里）。
    本次就在 `tmp\p0\verify-config.ps1` 上踩了一次。
29. **`e2e-lib.ps1` 的两个使用陷阱**（各浪费了我一轮排查）：
    - `Payload $resp` 返回的是 `body.data`（**整个对象**）。登录接口必须写 `(Payload $resp).token`；
      直接把返回值当 token 用，会带着一个畸形的 `Authorization` 头请求 → 后端按**未认证**处理，返回 **HTTP 401 且 body 为空**。
      症状是「响应体全空、后续拿到的 id 全是 0」，很容易误判成权限或建表问题。
    - `Http-Json -Body` 内部会 `ConvertTo-Json`，所以**只能传 hashtable**；传已经序列化好的 JSON **字符串**会被
      **二次编码**成 `"{\"addressId\":1}"`，服务端报 `Cannot construct instance of ... CreateOrderDTO`。
      需要发原始字符串时走 `Start-RaceOrder` 那种直接用 `HttpClient` 的路径。
30. **HTTP 层的「客户端错误」也要算 400，别让它掉进 500 兜底**：畸形请求体抛的是
    `HttpMessageNotReadableException`，原本被 `Exception` 兜底成 **code 500** —— 上线后 5xx 会被当成服务端故障来告警，
    把客户端错误算进服务端错误率。已在 `GlobalExceptionHandler` 单独处理成 400。
31. **会被自动化脚本解析的日志行要刻意保持稳定**：`OrderTimeoutTask` 的
    `[timeout-job] closed N expired unpaid order(s)` 是 `timeout-job-verify.ps1` 用来断言
    「同一单只关一次」的依据，所以这一行**保持 ASCII、不轻易改前缀与措辞**（同文件其它日志行仍是中文，不受影响）。
32. **Rollup `manualChunks` 的对象式写法会毁掉 tree-shaking**：`{ 'vendor-element-plus': ['element-plus'] }`
    会把整包的入口模块当成 chunk 入口，入口的导出不会被摇掉——实测 element-plus 从 ~396 kB **反弹到 799 kB**
    （JS 合计 0.63 → 1.10 MB，反而更差）。必须用**函数式**（按 `id.includes(...)` 判断）。
    并且对 element-plus 这种库即使函数式也膨胀，所以 admin-web 干脆让函数对它返回 `undefined`，只拆 vue 与其它依赖。
33. **`onMounted(async () => ...)` 里的 reject 会冒到错误边界**：Vue 用 `callWithAsyncErrorHandling` 包装生命周期钩子，
    会对它返回的 Promise 挂 `.catch` → `handleError` → 一路找到 `onErrorCaptured`。
    后果是**「订单不存在」这种正常业务结果，只要页面没自己 catch，整页就会变成「页面出错了」**（实测踩过：
    两个订单详情页）。规矩：**页面自己发起的请求要自己 try/catch**，把失败渲染成业务空状态；
    错误边界只兜「意料之外」的异常。
34. **模板里不能直接写全局对象**：`@click="() => location.reload()"` 会被 `vue-tsc` 报
    `TS2339: Property 'location' does not exist on type 'CreateComponentPublicInstance<...>'`
    ——模板表达式的属性是往**组件实例**上找的。在 `<script setup>` 里包一个 `reload()` 函数即可。
35. **错误边界只放在根组件会把导航一起顶掉**：用户除了点「刷新」无处可去。
    正确做法是**布局内容区再放一层**（`AdminLayout` / `TabLayout` 的 `router-view`）：
    页面异常由内层接住、外壳（侧边栏/Tabbar）保留；布局自身出错才轮到根那层。
36. **这台机器上 `git push` 到 GitHub 会被连接重置**（`Recv failure: Connection was reset` /
    `Failed to connect to github.com:443 after 21s`），但同一时刻 `Test-NetConnection github.com -p 443`、
    `Invoke-WebRequest https://github.com`、GitHub API 全都是正常的 200 —— 说明是 git 传输层被拦，
    不是网络不通。**绕过办法**（实测有效，缺一不可）：

    ```bash
    git -c http.version=HTTP/1.1 -c http.sslBackend=openssl push origin main
    ```

    只加 `http.version=HTTP/1.1` 仍然失败；换成 openssl 后端才通。**不要改全局 git 配置**，每次带上参数即可。
    另外这台机器到 GitHub 的**读**连接也间歇性失败（`ls-remote` 时通时不通），重试即可——
    真正的判据是 push 自己打印的 `xxx..yyy  main -> main`，以及随后 `git status -sb` 不再显示 `ahead N`。

---

## 9. 标准工作流（照着做）

```
1. 读本文件，确认环境（尤其 §2）
2. 改代码：直接在 WSL 里编辑 /mnt/d/projects/mall/... 下的文件
3. 停服务：cmd.exe /c "D:\projects\mall\stop.bat"
4. 构建  ：cmd.exe /c "D:\projects\mall\build.bat" clean install
          → 看 build.log 里的 BUILD SUCCESS / BUILD_EXIT=0
5. 起服务：cmd.exe /c "D:\projects\mall\run.bat"
6. 验证  ：grep "Started Mall.*Application" logs/*.log
          再用 curl 落盘验证接口（见 §7 基线）；商品域跑 scripts\p2-verify.ps1，购物车/订单域跑 scripts\p3-verify.ps1，
          动了下单链路再跑 scripts\idempotency-verify.ps1（真并发幂等）；动了超时关单再跑
          scripts\timeout-job-verify.ps1（单应用能关 + 两应用只关一次）
7. 动了后端：mvn -B test（28 项单元测试；build.bat 也会跑）
8. 动了前端：起两个 dev server，先 node scripts\web-check.mjs（页面渲染），再 node scripts\web-flow.mjs（全流程闭环）
9. 更新本文件的 §7 进度表
```

---

## 10. 还可以怎么帮使用者

- 他习惯**中文交流**，喜欢**直接给结论和证据**，不要客套。
- 汇报进度时给出**可验证的证据**（日志片段 / 返回体），不要只说“已完成”。
- 涉及**杀进程、改端口、动他现有项目**的操作，先问。

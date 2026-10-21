# Mall 商城

自研电商 MVP（不是套壳开源项目）。完整购物闭环：**浏览 → 加购 → 下单 → 模拟支付 → 发货 → 收货**，外加管理后台。

| 端 | 技术栈 | 地址 |
|---|---|---|
| 前台接口 `mall-portal` | Spring Boot 3.2.5 / Java 17 / MyBatis-Plus 3.5.7 | http://localhost:8090 |
| 后台接口 `mall-admin` | 同上 | http://localhost:8081 |
| H5 商城 `portal-web` | Vue 3 / Vite / TS / Vant 4 | http://localhost:5174 |
| 管理后台 `admin-web` | Vue 3 / Vite / TS / Element Plus | http://localhost:5173 |
| 数据库 | MySQL 8（库名 `mall`） | localhost:3306 |

## 目录结构

```
mall/
├── backend/                  Maven 多模块
│   ├── mall-common/          统一返回 R / 错误码 / 业务异常 / 全局异常 / JWT 工具
│   ├── mall-core/            实体 / Mapper / Service / 安全配置 / 定时任务 / 演示图静态资源
│   ├── mall-admin/           后台管理接口   :8081
│   └── mall-portal/          前台商城接口   :8090
├── frontend/
│   ├── admin-web/            管理后台（分类 / 商品 / 订单）
│   └── portal-web/           H5 商城（商品 / 购物车 / 下单 / 我的）
├── sql/                      建库建表与迁移（V1 → V4，按文件名顺序执行）
├── scripts/                  验证与运维脚本（p2-verify / p3-verify / stop-apps / e2e-lib）
├── docs/AI-HANDOFF.md        ⭐ 开发交接文档：环境、约定、进度、踩坑清单、验证基线
└── docker-compose.yml        一键起全栈
```

## 快速开始

### 方式一：Docker Compose（最省事）

```bash
cp .env.example .env      # 填 MYSQL_ROOT_PASSWORD / MALL_JWT_SECRET / MALL_CRYPTO_SECRET（都是必填，没有默认值）
docker compose up -d --build
```

MySQL 首次启动时会自动执行 `sql/` 下的 V1→V4（建表 + 演示数据 + 后台账号），无需手动初始化。
起完直接开 http://localhost:5174 。停止用 `docker compose down`（加 `-v` 连数据一起删）。

密钥生成方式（`.env.example` 里也有）：

```bash
openssl rand -hex 32
# PowerShell: -join ((1..64) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
```

> ⚠️ 本机开发环境没有 Docker，这套编排**只做了语法与结构校验，没有实际跑过**。

### 方式二：本地手动起

前置：JDK 17、Maven 3.9+、MySQL 8、Node 20+ 与 pnpm。

```bash
# 1) 建库 + 演示数据（按顺序）
mysql -uroot -p --default-character-set=utf8mb4 -e "source sql/V1__schema.sql"
mysql -uroot -p --default-character-set=utf8mb4 -e "source sql/V2__add_user_role.sql"
mysql -uroot -p --default-character-set=utf8mb4 -e "source sql/V3__seed_demo.sql"
mysql -uroot -p --default-character-set=utf8mb4 -e "source sql/V4__order_index.sql"
mysql -uroot -p --default-character-set=utf8mb4 -e "source sql/V5__order_request_id.sql"

# 2) 后端（Windows 下也可直接 build.bat / run.bat）
cd backend && mvn clean install && cd ..
# 分别启动
java -jar backend/mall-admin/target/mall-admin-1.0.0.jar
java -jar backend/mall-portal/target/mall-portal-1.0.0.jar

# 3) 前端（另开两个终端）
cd frontend/admin-web  && pnpm install && pnpm dev   # http://localhost:5173
cd frontend/portal-web && pnpm install && pnpm dev   # http://localhost:5174
```

数据库口令在 `backend/mall-admin/src/main/resources/application.yml`（以及 `mall-portal` 的同名文件，**两个必须一致**）。

## 默认账号

| 用途 | 账号 |
|---|---|
| 管理后台 | `malladmin` / `admin123456`（`t_user.role = 1`） |
| H5 商城 | 没有预置账号，自己注册（用户名 4-32 位；密码 8-64 位，**必须同时含字母和数字**） |

## 接口文档

两个后端都带 Knife4j，服务起来后访问：

- 后台：http://localhost:8081/doc.html
- 前台：http://localhost:8090/doc.html

统一返回 `R<T>`：**业务失败时 HTTP 状态码仍是 200**，错误码在 `body.code` 里；只有未认证才是真正的 HTTP 401。
详见 `docs/AI-HANDOFF.md` §6.1。

### 下单幂等（客户端必读）

`POST /api/v1/portal/order` 支持 `requestId` 字段（可选，最长 64 字符）。**同一次结算里请固定不变地传同一个值**
（例如一个 UUID），这样重复提交（双击 / 网络重试）只会落一单，并返回原来那一单的 `orderId`。
不传也能保证不会并发重复下单（服务端有购物车行锁兜底），只是重试时会返回「购物车为空」而不是原单。

## 验证

```bash
# 后端单元测试（62 项，纯内存不需要数据库；build.bat 也默认会跑）
cd backend && mvn -B test

# 下单幂等的真并发验证（22 项断言：同/异 requestId 并发、重试、畸形请求体）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/idempotency-verify.ps1

# 超时关单验证（18 项断言：单应用能关 + 两应用同跑只关一次；会自己停/起后端）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/timeout-job-verify.ps1

# 备份 / 恢复演练（13 项断言：备份→恢复到临时库→逐表比对→清理，不碰主库）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/backup-restore-verify.ps1

# 监控指标验证（16 项断言：/actuator/prometheus + 业务计数器真的会动；会自己停/起后端）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/metrics-verify.ps1

# 生产加固验证（17 项断言：traceId / CORS 白名单 / 登录失败限流；会自己停/起后端）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/security-verify.ps1

# 敏感数据安全验证（34 项断言：密码策略 / PII 字段加密 / 读写往返 / 日志无泄露；会自己停/起后端）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/security-data-verify.ps1

# 登录态验证（27 项断言：HttpOnly Cookie / CSRF 双提交 / 退出清理；会自己停/起后端）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/auth-cookie-verify.ps1

# 密钥轮换演练（28 项断言：换密钥→旧数据仍可读→重加密→去掉旧密钥仍可读→还原；会重启 5 次后端）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/crypto-rotate-verify.ps1

# 令牌生命周期（26 项断言：access 5 秒真过期 / refresh 轮换 / 重放失败 / 登出吊销；会自己停/起后端）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/token-lifecycle-verify.ps1

# 商品域端到端（36 项断言）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/p2-verify.ps1

# 购物车 + 订单端到端（61 项断言，含并发防超卖与超时关单）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/p3-verify.ps1

# 前端页面渲染检查（24 项断言 + 控制台告警零容忍；需要 Chrome + 前后端都已启动）
node scripts/web-check.mjs

# 前端全流程交互测试（41 项断言：真点按钮跑完整个购物闭环，含「立即购买」「取消订单」与「登录后不落 token」）
node scripts/web-flow.mjs

# 管理后台 UI CRUD（16 项断言：分类增删改禁用 / 商品新增-下架-删除）
node scripts/admin-crud-verify.mjs
```

前两个脚本会自己 stop → build → run → 跑断言 → 汇总，证据落在 `tmp/<阶段>/`。

> 后两个脚本用本机 Chrome 的 DevTools Protocol 驱动真实页面，不需要额外依赖。
> `web-check.mjs` 断言页面渲染出的文字（并注入登录态覆盖需要登录的页面），`web-flow.mjs` 则真的去点按钮，
> 把「注册 → 浏览 → 选规格 → 加购 → 购物车 → 建地址 → 下单 → 支付 → 后台发货 → 确认收货」跑一遍，
> 最后用 SQL + 接口双向核对落库状态（库存三态、销量、订单状态机）。
>
> **为什么需要它们**：`pnpm build` / `vue-tsc` 对前端覆盖有限——实测有 3 个前端 bug 它们全绿
> （Vant 4 已无 `GoodsAction`、漏写 `onMounted(load)`、同路由换参数组件被复用），都是真渲染才发现。
> 详见 `docs/AI-HANDOFF.md` §8 第 20~22 条。

## 上线

用 `prod` profile 启动（`SPRING_PROFILES_ACTIVE=prod` 或 `--spring.profiles.active=prod`），并且**必须**通过环境变量注入：

| 环境变量 | 说明 |
|---|---|
| `MALL_JWT_SECRET` | JWT 签名密钥，**两个后端必须一致**，至少 32 字符 |
| `MALL_CRYPTO_SECRET` | 敏感字段（手机号/收货人/地址）加密密钥，两个后端一致、至少 32 字符，**必须与 JWT 密钥不同**。⚠️ 上线后不要随意更换，否则旧密文解不开 |
| `MALL_COOKIE_SECURE` | 登录态 Cookie 是否只在 HTTPS 下发送。**prod 必须 `true`**（自检会拦），dev（http）必须 `false` |
| `MALL_DB_PASSWORD` | 数据库口令（两个后端一致） |
| `MALL_DB_URL` / `MALL_DB_USERNAME` | 数据库连接（默认 localhost / root） |
| `MALL_CORS_ALLOWED_ORIGINS` | 允许跨域的来源（逗号分隔）。**不能含 `*`**，prod 启动自检会直接拦下 |
| `MALL_LOGIN_MAX_FAILURES` / `MALL_LOGIN_LOCK_SECONDS` | 登录失败限流（默认 5 次 / 300 秒） |

prod 下的行为差异：

- 关闭 springdoc / knife4j 接口文档与 `/actuator/info`，只保留 `health`
- 超时关单定时任务**两个后端都默认开启**：只在一个应用跑的话，它挂了超时订单就永远不关、锁定库存永不释放。
  并发重复扫描是安全的（关单走条件 UPDATE，只有成功那次才释放库存）。要单点跑就把另一边的
  `MALL_ORDER_TIMEOUT_JOB_ENABLED` 设成 `false`
- CORS 变成**显式白名单**（原来是通配来源 + 允许携带凭证，等于任意站点可带用户身份调后端）
- **敏感数据在库里是加密的**：手机号、收货人、详细地址（含订单快照）都用 **AES-256-GCM** 字段级加密，enc:v1: 前缀标识密文；存量明文会在应用启动时自动补加密（幂等、双实例并发安全）。密码是 BCrypt，任何接口都不返回。scripts/security-data-verify.ps1 会用 34 项断言验证这件事（含「同一个手机号两行密文必须不同」）
- **登录态在 HttpOnly Cookie 里**（`SameSite=Lax` + 前端 `X-CSRF-Token` 双提交校验），**localStorage 不存 token** —— XSS 读不到凭证；脚本/服务端仍可用 `Authorization: Bearer`，且不受 CSRF 影响
- **令牌分两级**：access 只活 15 分钟（无状态、不可吊销，所以越短越安全）；refresh 7 天、**只存哈希**、**用一次即换新**、可吊销。access 过期由前端自动用 refresh 换新（单飞，避免并发刷新互相踩），用户无感
- **PII 加密密钥支持轮换**：密文带 keyId（`enc:v1:<keyId>:<payload>`），轮换时把新密钥放进 `MALL_CRYPTO_SECRET`、旧密钥放进 `MALL_CRYPTO_PREVIOUS_SECRETS`（仅解密），再用 `MALL_CRYPTO_RE_ENCRYPT_ON_STARTUP=true` 重启一次把数据迁到新密钥，最后清掉旧密钥。`scripts/crypto-rotate-verify.ps1` 会把这条链路真跑一遍
- **密码策略**：8-64 位且必须同时含字母和数字；超过 72 字节（BCrypt 的有效长度）直接拒绝
- **传输加密**：容器只监听 80，HTTPS 需要在前面加一层 TLS 终止，模板见 deploy/nginx-tls.conf.example（含 HSTS / CSP / 安全响应头，**需在目标机验证**）
- 登录有**失败次数限流**：同一用户名窗口内失败 5 次锁 5 分钟，锁定期间连正确密码也拒
- 每个响应都带 `X-Trace-Id`（16 位 hex），日志行里也有，排查时让用户把它给你即可
- 业务接口统一在 **`/api/v1/**`** 下（旧的无版本路径已移除，会 404）
- **日志只打 stdout**：docker 部署由 `docker-compose.yml` 里的 `json-file` 上限（`max-size=20m` / `max-file=5`）兜住；
  裸机长跑请把 stdout 交给 `logrotate` 或 systemd-journald。后端**不自带**文件滚动（原因见 `docs/AI-HANDOFF.md`）
- **备份**：`scripts/backup-db.ps1` 做一致性快照（保留最近 7 份）；`scripts/backup-restore-verify.ps1`
  会真的备份→恢复到临时库→逐表比对→清理，用来证明「备份能恢复」。**没有恢复过的备份不算备份。**
- **监控**：两个后端都暴露 `/actuator/prometheus`（JVM / 连接池 / HTTP 指标 + 两个业务计数器）。
  最值得配的两条告警是 `rate(mall_orders_created_total[5m]) == 0`（营业时段下单掉到 0 = 链路断了）
  和 `rate(mall_orders_stock_shortage_total[5m]) > 10`（库存被抢空或在刷单）。
  prod 只暴露 `health,prometheus`。详见 `docs/AI-HANDOFF.md` 的「监控」小节。
- 启动时执行 `ProductionConfigGuard` 自检：密钥若为空、是未解析的占位符、是开发默认值或长度不足，
  **应用直接启动失败**，不会带着弱密钥跑起来

> 这个自检是必需的：Spring Boot 在 `@ConfigurationProperties` 绑定路径上会把解析不了的占位符
> **原样保留而不是报错**，只靠 YAML 写 `${MALL_JWT_SECRET}` 拦不住（详见 `docs/AI-HANDOFF.md` §8 第 26 条）。

CI 见 `.github/workflows/ci.yml`：后端编译+单测、在全新 MySQL 上按顺序应用 V1..V7、两个前端 typecheck+build。

## 几个必须知道的坑

1. **Windows 的 `JAVA_HOME` 指向 `D:\jdks`（缺 `\bin`）**，直接跑 `mvn` 会报 `JAVA_HOME is not defined correctly`；`build.bat` 里已显式覆盖。
2. **构建前先停服务**：Windows 下运行中的 java 会锁住 `mall-*.jar`，`mvn clean` 删不掉直接 BUILD FAILURE。顺序是 `stop.bat` → `build.bat`。
3. **不要 `taskkill /IM java.exe`**：IDEA 自己的进程也是 java.exe，会误杀。要停用 `stop.bat`（按 PID / 命令行匹配）。
4. **pnpm 11 会拦截依赖的安装脚本**，`allowBuilds: esbuild: true` 只能写在 `pnpm-workspace.yaml`（写 package.json 里会被静默忽略），否则 `pnpm install` 报 `ERR_PNPM_IGNORED_BUILDS`。
5. **端口 8080 被占用**：`mall-portal` 因此用了 **8090**，别改回去。
6. **两个前端的 UI 组件是按需注册的**：新页面用到**新**组件时，要去 `src/main.ts` 的清单里补一行，
   否则组件不会注册（症状是页面少一块 + 控制台一条 `Failed to resolve component`）。
   `node scripts/web-check.mjs` 的告警零容忍会抓住这类漏注册。
7. **前端有分层错误兜底**：根 `App.vue` 一层 + 布局内容区一层（侧边栏/Tabbar 不会被顶掉）。
   页面内主动发的请求要自己 `try/catch`，别把「订单不存在」这类业务结果抛给错误边界——那会把整页换成「页面出错了」。

更多（含 18+ 条踩坑与全部验证基线）见 `docs/AI-HANDOFF.md`。

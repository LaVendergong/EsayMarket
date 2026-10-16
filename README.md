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
cp .env.example .env      # 填 MYSQL_ROOT_PASSWORD 与 MALL_JWT_SECRET（都是必填，没有默认值）
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
| H5 商城 | 没有预置账号，自己注册（用户名 4-32 位，密码 6-64 位） |

## 接口文档

两个后端都带 Knife4j，服务起来后访问：

- 后台：http://localhost:8081/doc.html
- 前台：http://localhost:8090/doc.html

统一返回 `R<T>`：**业务失败时 HTTP 状态码仍是 200**，错误码在 `body.code` 里；只有未认证才是真正的 HTTP 401。
详见 `docs/AI-HANDOFF.md` §6.1。

### 下单幂等（客户端必读）

`POST /api/portal/order` 支持 `requestId` 字段（可选，最长 64 字符）。**同一次结算里请固定不变地传同一个值**
（例如一个 UUID），这样重复提交（双击 / 网络重试）只会落一单，并返回原来那一单的 `orderId`。
不传也能保证不会并发重复下单（服务端有购物车行锁兜底），只是重试时会返回「购物车为空」而不是原单。

## 验证

```bash
# 后端单元测试（44 项，纯内存不需要数据库；build.bat 也默认会跑）
cd backend && mvn -B test

# 下单幂等的真并发验证（22 项断言：同/异 requestId 并发、重试、畸形请求体）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/idempotency-verify.ps1

# 商品域端到端（36 项断言）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/p2-verify.ps1

# 购物车 + 订单端到端（61 项断言，含并发防超卖与超时关单）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/p3-verify.ps1

# 前端页面渲染检查（15 项断言 + 控制台告警零容忍；需要 Chrome + 前后端都已启动）
node scripts/web-check.mjs

# 前端全流程交互测试（29 项断言：真点按钮跑完整个购物闭环）
node scripts/web-flow.mjs
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
| `MALL_DB_PASSWORD` | 数据库口令（两个后端一致） |
| `MALL_DB_URL` / `MALL_DB_USERNAME` | 数据库连接（默认 localhost / root） |

prod 下的行为差异：

- 关闭 springdoc / knife4j 接口文档与 `/actuator/info`，只保留 `health`
- 启动时执行 `ProductionConfigGuard` 自检：密钥若为空、是未解析的占位符、是开发默认值或长度不足，
  **应用直接启动失败**，不会带着弱密钥跑起来

> 这个自检是必需的：Spring Boot 在 `@ConfigurationProperties` 绑定路径上会把解析不了的占位符
> **原样保留而不是报错**，只靠 YAML 写 `${MALL_JWT_SECRET}` 拦不住（详见 `docs/AI-HANDOFF.md` §8 第 26 条）。

CI 见 `.github/workflows/ci.yml`：后端编译+单测、在全新 MySQL 上按顺序应用 V1..V4、两个前端 typecheck+build。

## 几个必须知道的坑

1. **Windows 的 `JAVA_HOME` 指向 `D:\jdks`（缺 `\bin`）**，直接跑 `mvn` 会报 `JAVA_HOME is not defined correctly`；`build.bat` 里已显式覆盖。
2. **构建前先停服务**：Windows 下运行中的 java 会锁住 `mall-*.jar`，`mvn clean` 删不掉直接 BUILD FAILURE。顺序是 `stop.bat` → `build.bat`。
3. **不要 `taskkill /IM java.exe`**：IDEA 自己的进程也是 java.exe，会误杀。要停用 `stop.bat`（按 PID / 命令行匹配）。
4. **pnpm 11 会拦截依赖的安装脚本**，`allowBuilds: esbuild: true` 只能写在 `pnpm-workspace.yaml`（写 package.json 里会被静默忽略），否则 `pnpm install` 报 `ERR_PNPM_IGNORED_BUILDS`。
5. **端口 8080 被占用**：`mall-portal` 因此用了 **8090**，别改回去。

更多（含 18+ 条踩坑与全部验证基线）见 `docs/AI-HANDOFF.md`。

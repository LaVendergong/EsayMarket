# mall 商城项目 · AI 交接文档

> 目的：让**任何接手的 AI**（或人）在不问任何问题的情况下，能继续开发、构建、运行、验证这个项目。
> 最后更新：2026-10-16

---

## 1. 项目是什么

一个**自研的电商商城**（不是套壳开源项目）。范围是 MVP 完整购物闭环：
用户 → 商品 → 购物车 → 下单 → 模拟支付，外加管理后台。

- **代码位置**：`D:\projects\mall`（Windows 路径） = `/mnt/d/projects/mall`（WSL 路径）
- 技术栈：Spring Boot 3.2.5 / Java 17 / MyBatis-Plus 3.5.7 / MySQL 8 / Redis / Knife4j 4.5.0 / jjwt 0.12.5
- 前端（尚未开始）：Vue3 + Vite + TypeScript + pnpm，管理后台用 Element Plus，H5 用 Vant

---

## 2. 运行环境（**先读这节，否则会白折腾**）

### 2.1 拓扑

```
Windows 11（主机名 Administrator）
├── IntelliJ IDEA 2026.2.1      <- 人在用，别乱杀进程
├── 本地 MySQL 8 (3306)；Redis (6379) **未安装也未使用**（只引了 starter，Java 代码里没有 Redis 使用点，缺了不影响启动）
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
│   └── V4__order_index.sql       t_order 加 idx_status_expire（超时关单扫描用），幂等
├── sqlschema.sql            ⚠️ 初版建表脚本（无 role 列），已被 sql\ 取代，勿用
├── scripts\
│   ├── e2e-lib.ps1               验证公用库（Http-Json / Check / Invoke-Mysql / Start-MallApps / Save-Summary），被 p2/p3 脚本 dot-source
│   ├── stop-apps.ps1             停止脚本（PID 文件 → 命令行 → 端口 → 日志 PID，四重兜底）
│   ├── p2-verify.ps1             P2 商品域端到端验证（36 项断言）
│   ├── p3-verify.ps1             P3 购物车+订单端到端验证（61 项断言，含并发防超卖与超时关单）
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
- 登录态放 localStorage（admin 用 `mall_admin_token`，H5 用 `mall_portal_token`），都是 hash 路由。
- 两个 dev server 都只监听本机；生产部署 `pnpm build` 产物丢任意静态服务器即可（hash 路由，不用配 rewrite）。

---

## 5. 数据库

- MySQL 8，服务名 **MySQL80**，监听 **3306**
- 库名 **`mall`**
- 账号 **`root`**，密码在 `backend/mall-admin/src/main/resources/application.yml` 和 `mall-portal` 的同名文件里（两个文件必须一致）
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

> 换库重建的顺序：`V1__schema.sql` → `V2__add_user_role.sql` → `V3__seed_demo.sql` → `V4__order_index.sql`。
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
  - 白名单：`/api/portal/auth/**`、`/api/admin/auth/**`、swagger/knife4j/actuator
  - `/api/admin/**` → 需要 `ROLE_ADMIN`
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
- **SKU 更新语义**（`PUT /api/admin/product/{id}`）：按 id 对齐 —— 传了 id 就更新、没 id 就新增、原有但没出现的**逻辑删除**。
- **删除语义**：商品删除 = 商品 + 其 SKU 逻辑删除；分类只有在「没有子分类且没有商品」时才允许删除（否则 2006）。
- **浏览类接口匿名开放**：`SecurityConfig` 里 `GET /api/portal/category/**`、`GET /api/portal/product/**` 是 `permitAll`，
  其余 `/api/**` 仍需登录，`/api/admin/**` 仍需 `ROLE_ADMIN`。POST/PUT/DELETE 到这些路径**依然要登录**。
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

### P1 验证结果（可作为回归基线）

```
POST /api/portal/auth/register                       -> code 200
POST /api/portal/auth/login                          -> code 200, 返回 token
GET  /api/portal/user/me   (带 token)                -> code 200
GET  /api/portal/user/me   (不带 token)              -> HTTP 401
POST /api/portal/address                             -> code 200
GET  /api/portal/address                             -> code 200, 返回列表
POST /api/admin/auth/login (普通用户)                -> code 403 "非管理员账号，无法登录后台"
GET  /api/admin/ping       (匿名)                    -> HTTP 401
```

### P2 验证结果（35/35 全绿，可回归）

一键跑（脚本自己 stop → build → run → seed → 35 项断言 → 汇总）：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\projects\mall\scripts\p2-verify.ps1"
# 参数：-SkipBuild 跳过构建 / -SkipRestart 只对已启动的服务跑断言 / -SkipSeed / -SkipCleanup
```

证据落在 `tmp\p2\*.json`（每个请求的响应体）与 `tmp\p2\summary.txt`。覆盖：

- **前台（匿名，不带 token）**
  - `GET /api/portal/category/tree`：只返回启用分类，第一版是嵌套树（含 `children`）
  - `GET /api/portal/product/page`：只返回上架商品；`categoryId`（自动聚合后代分类）、`keyword`（搜 name/sub_title）、`minPrice`/`maxPrice`、`sort=price_asc|price_desc|sales_desc|new`
  - `GET /api/portal/product/{id}`：详情含 SKU 列表与图集数组；不存在 → `2001`；已下架 → `2002`
- **后台（需 role=1 的 token）**
  - 分类：`tree`/`POST`/`PUT /{id}`/`PUT /{id}/status`/`DELETE /{id}`；有子分类或商品时删除被拒（`2006`）；同级同名（`2008`）；父分类不能是自己或自己的子分类
  - 商品：`page`（含下架、可按 status 筛）/`GET /{id}`/`POST`/`PUT /{id}`/`PUT /{id}/status`/`DELETE /{id}`
    - 一次提交 SPU + 2 个 SKU；更新时 SKU 按 id 对齐（改一个 / 增一个 / 删一个）
    - 下架后前台详情 `2002`、前台列表不含；删除后前台 `2001`、后台列表 0 条
  - 参数校验：缺 name → `400`；分类不存在 → `2005`；SKU 列表为空 → `400`
  - 库存汇总：新商品 2 个 SKU（8 + 3）在前台列表显示 `stock=11`
- **鉴权 / 协议边界**：匿名打 `/api/admin/**` → HTTP 401；`GET` 打 POST 接口 → HTTP 405 + code 405；不存在的路径 → HTTP 404 + code 404

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
- **协议边界**：密码错误是 HTTP 200 + `code=1003`；匿名打 `/api/admin/**` 是真 HTTP 401

管理后台的页面：登录 / 分类管理 / 商品管理（列表 → **详情**、新增、编辑）/ 订单管理（列表 → 详情）。

> 商品详情页是后补的（`admin-web\src\views\ProductDetailView.vue`，路由 `/product/:id`，
> 数据来自 `GET /api/admin/product/{id}`），单独验过 `tmp\admin-web\detail-smoke.ps1`（**9/9**，
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

**H5 的一处已知取舍**：后端下单只结算「购物车中已选中」的条目，所以「立即购买」的实现是
「先把该 SKU 加购 → 再把购物车其它条目取消选中 → 跳结算」，**会覆盖用户在购物车里原有的勾选**。

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
          再用 curl 落盘验证接口（见 §7 基线）；商品域跑 scripts\p2-verify.ps1，购物车/订单域跑 scripts\p3-verify.ps1
7. 动了前端：起两个 dev server，先 node scripts\web-check.mjs（页面渲染），再 node scripts\web-flow.mjs（全流程闭环）
8. 更新本文件的 §7 进度表
```

---

## 10. 还可以怎么帮使用者

- 他习惯**中文交流**，喜欢**直接给结论和证据**，不要客套。
- 汇报进度时给出**可验证的证据**（日志片段 / 返回体），不要只说“已完成”。
- 涉及**杀进程、改端口、动他现有项目**的操作，先问。

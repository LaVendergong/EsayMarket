/**
 * 前端「页面渲染」检查 —— 用本机 Chrome 跑一遍两个前端的页面，断言真实渲染出的文字。
 *
 * 为什么需要它：`pnpm build` 和 `vue-tsc` 都发现不了下面这类问题，实测踩过三次——
 *   1. 模板里用了 Vant 4 已不存在的组件（`van-goods-action` 是 Vant 3 的，Vant 4 叫 `van-action-bar`）
 *      → 组件没注册，Vue 把标签当原生元素原样输出，页面少一整条「加入购物车」操作栏，构建/类型检查全绿；
 *   2. 页面定义了 `load()` 但漏写 `onMounted(load)` → 数据永远不加载，页面一直显示空/兜底文案；
 *   3. 漏写 `:key="$route.fullPath"` → 同一路由只换参数时组件被复用，页面停在旧数据上。
 * 这三类只有「真的把页面渲染出来 + 监听 Vue 运行时报警」才能发现。
 *
 * 与 web-flow.mjs 的分工：本脚本只验渲染（快、稳）；业务闭环请跑 web-flow.mjs（真点按钮）。
 *
 * 前置：两个前端 dev server + 两个后端都在跑；本机有 Chrome。
 * 用法：node scripts/web-check.mjs
 * 产物：tmp\web\cdp-report.txt
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { launch } from './cdp.mjs'

// 端口可覆盖：本机 5173 有时被别的项目占着（见 docs §8），用 ADMIN_WEB_URL 指到我们的 dev server
const ADMIN_WEB = process.env.ADMIN_WEB_URL || 'http://localhost:5173'
const PORTAL_WEB = process.env.PORTAL_WEB_URL || 'http://localhost:5174'
const ADMIN_API = 'http://localhost:8081'
const PORTAL_API = 'http://localhost:8090'
const OUT_DIR = 'D:/projects/mall/tmp/web'

const report = []
const log = (s) => {
  report.push(s)
  console.log(s)
}

async function post(url, body) {
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  const text = await r.text()
  if (!text) return { code: r.status, message: '(空响应体)' }
  try {
    return JSON.parse(text)
  } catch {
    return { code: r.status, message: text.slice(0, 200) }
  }
}

mkdirSync(OUT_DIR, { recursive: true })

// ------------------------------------------------------------------ 登录态
const adminVo = (await post(`${ADMIN_API}/api/v1/admin/auth/login`, { username: 'malladmin', password: 'admin123456' })).data
log(`[prep] mall-admin login: ${adminVo ? 'ok role=' + adminVo.role : 'FAILED'}`)

// H5 用一个固定的测试账号（注册失败说明已存在，直接登录）
await post(`${PORTAL_API}/api/v1/portal/auth/register`, {
  username: 'webcheck1',
  password: 'webcheck123456',
  nickname: 'Web Check'
}).catch(() => {})
const buyerVo = (await post(`${PORTAL_API}/api/v1/portal/auth/login`, { username: 'webcheck1', password: 'webcheck123456' })).data
log(`[prep] portal buyer login: ${buyerVo ? 'ok' : 'FAILED'}`)

// ------------------------------------------------------------------ 浏览器
const browser = await launch({ port: 9333, profileDir: `${OUT_DIR}/check-profile`, width: 420, height: 900 })
const { goto, innerText, waitFor, setStorage, problems, close } = browser

const checks = []
function record(name, ok, detail) {
  checks.push(ok)
  log(`[${ok ? 'PASS' : 'FAIL'}] ${name} :: ${detail}`)
}
function expect(name, txt, mustHave) {
  const hit = mustHave.filter((m) => txt.includes(m))
  record(name, hit.length === mustHave.length, `len=${txt.length} hit=[${hit.join(',')}]`)
}

// 1) 管理后台（业务路由都挂在 AdminLayout 上，整页带 ?r=N 重载后守卫才会读到 token）
if (adminVo) {
  // 先停在登录页写 token 再进业务页：直接开 `/` 会渲染首页看板并在登录前发出请求，产生 401 告警
  await goto(`${ADMIN_WEB}/#/login`, 1500)
  await setStorage({ mall_admin_token: adminVo.token, mall_admin_user: JSON.stringify(adminVo) })
  await goto(`${ADMIN_WEB}/?r=1#/product`, 4000)
  // 不校验具体商品名：验证库里商品越建越多，演示数据会被挤出第一页（不是页面坏了）
  expect('管理后台 · 商品列表', await innerText(), ['商品管理', '新增商品', '主图'])
  await goto(`${ADMIN_WEB}/?r=2#/product/1`, 3500)
  expect('管理后台 · 商品详情', await innerText(), ['商品详情', 'iPhone 15 Pro', 'SKU', '可售'])
  await goto(`${ADMIN_WEB}/?r=3#/category`, 3000)
  expect('管理后台 · 分类管理', await innerText(), ['分类管理', '数码', '笔记本'])
  await goto(`${ADMIN_WEB}/?r=4#/order`, 3000)
  expect('管理后台 · 订单管理', await innerText(), ['订单管理', '订单号'])
  // 表单类页面以前没被覆盖，而「组件漏注册」正是最容易藏在这种页面里（见 docs §8 第 20 条）
  await goto(`${ADMIN_WEB}/?r=5#/product/new`, 3500)
  expect('管理后台 · 商品新增页', await innerText(), ['新增商品', '商品名称'])
  await goto(`${ADMIN_WEB}/?r=6#/product/1/edit`, 3500)
  // 运费模板下拉是结算计价的入口：组件没注册时整行会消失，构建/类型检查都发现不了
  expect('管理后台 · 商品编辑页', await innerText(), ['编辑商品', '商品名称', '运费模板'])
  await goto(`${ADMIN_WEB}/?r=8#/coupon`, 3500)
  expect('管理后台 · 优惠券管理', await innerText(), ['优惠券管理', '新增优惠券'])
  await goto(`${ADMIN_WEB}/?r=9#/freight`, 3500)
  expect('管理后台 · 运费模板', await innerText(), ['运费模板', '新增模板'])
  await goto(`${ADMIN_WEB}/?r=14#/review`, 3500)
  expect('管理后台 · 评价管理', await innerText(), ['评价管理', '商品'])
  await goto(`${ADMIN_WEB}/?r=15#/report`, 4000)
  expect('管理后台 · 销售报表', await innerText(), ['销售报表', 'GMV'])
  await goto(`${ADMIN_WEB}/?r=16#/dashboard`, 4000)
  expect('管理后台 · 首页看板', await innerText(), ['首页看板', '今日 GMV', '低库存提醒'])
  await goto(`${ADMIN_WEB}/?r=17#/audit`, 3500)
  expect('管理后台 · 操作审计', await innerText(), ['操作审计', '动作'])
  await goto(`${ADMIN_WEB}/?r=18#/maintenance`, 3500)
  expect('管理后台 · 数据维护', await innerText(), ['数据维护', '搜索日志保留'])
  await goto(`${ADMIN_WEB}/?r=19#/rbac`, 3500)
  expect('管理后台 · 角色权限', await innerText(), ['角色权限', '管理员授权'])
  // 不存在的订单：页面要自己显示业务空状态，**不能**把异常抛给错误边界（那会把整页换成兜底 UI）
  await goto(`${ADMIN_WEB}/?r=7#/order/999999`, 3000)
  expect('管理后台 · 订单不存在时显示业务空状态', await innerText(), ['订单详情', '订单不存在'])
} else {
  record('管理后台（缺 token，跳过）', false, 'admin login failed')
}

// 2) H5 匿名可看
// 先清 localStorage：浏览器 profile 是复用的，上一轮的过期 token 还在，
// 会让首页带着无效凭证去请求 /portal/cart，401 后跳登录页（白屏）
await goto(`${PORTAL_WEB}/#/login`, 1200)
await setStorage({ mall_portal_token: '', mall_portal_user: '' })
await goto(`${PORTAL_WEB}/#/home`, 4000)
// 商品列表是异步拉的：后端刚重启时首屏可能要 1-2s，直接取 innerText 会偶发拿到白屏
await waitFor(`[...document.querySelectorAll('.van-card')].length > 0`, 8000)
expect('H5 · 首页', await innerText(), ['小米 14', '首页', '购物车'])
await goto(`${PORTAL_WEB}/#/product/2`, 3500)
expect('H5 · 商品详情', await innerText(), ['小米 14', '加入购物车', '立即购买', '选择规格', '商品评价'])
await goto(`${PORTAL_WEB}/#/product/999999`, 3000)
expect('H5 · 商品不存在走兜底', await innerText(), ['商品不存在或已下架'])
await goto(`${PORTAL_WEB}/#/login`, 3000)
expect('H5 · 登录页', await innerText(), ['登录', '用户名'])
await goto(`${PORTAL_WEB}/#/register`, 3000)
expect('H5 · 注册页', await innerText(), ['注册'])

// 3) H5 登录后（购物车 / 结算 / 订单 / 地址 / 我的）
if (buyerVo) {
  // 这里重新登录一次拿新鲜 token：后台阶段在同一浏览器里登录过 admin，Cookie 是同域共享的，
  // 用开头那个旧 token 偶尔会撞上「刚过期 / Cookie 被覆盖」→ 被踢回登录页，首页断言随机失败
  const fresh = (await post(`${PORTAL_API}/api/v1/portal/auth/login`, {
    username: 'webcheck1',
    password: 'webcheck123456'
  })).data || buyerVo
  // 先停登录页写 token：直接开 `/` 会先发一次未登录的购物车请求，产生 401 告警
  await goto(`${PORTAL_WEB}/#/login`, 1500)
  await setStorage({ mall_portal_token: fresh.token, mall_portal_user: JSON.stringify(fresh) })
  await goto(`${PORTAL_WEB}/?r=5#/cart`, 4000)
  expect('H5 · 购物车', await innerText(), ['购物车'])
  await goto(`${PORTAL_WEB}/?r=6#/checkout`, 3500)
  expect('H5 · 确认订单', await innerText(), ['确认订单', '应付金额'])
  await goto(`${PORTAL_WEB}/?r=7#/orders`, 3500)
  expect('H5 · 我的订单', await innerText(), ['我的订单', '全部', '待支付'])
  await goto(`${PORTAL_WEB}/?r=8#/address`, 3000)
  expect('H5 · 收货地址', await innerText(), ['收货地址', '新增地址'])
  await goto(`${PORTAL_WEB}/?r=9#/mine`, 3000)
  expect('H5 · 我的', await innerText(), ['我的订单', '收货地址', '退出登录'])
  // 券页面用 van-tabs 分「我的券 / 领券中心」，两个标题都要在（漏注册 Tabs 会只剩一张空白页）
  await goto(`${PORTAL_WEB}/?r=12#/coupon`, 3500)
  expect('H5 · 我的优惠券', await innerText(), ['我的券', '领券中心'])
  await goto(`${PORTAL_WEB}/?r=13#/digital`, 3500)
  expect('H5 · 我的卡券', await innerText(), ['我的卡券'])
  await goto(`${PORTAL_WEB}/?r=14#/favorites`, 3500)
  expect('H5 · 我的收藏', await innerText(), ['我的收藏'])
  await goto(`${PORTAL_WEB}/?r=10#/address/new`, 3000)
  expect('H5 · 新增地址页', await innerText(), ['新增地址', '保存'])
  await goto(`${PORTAL_WEB}/?r=11#/order/999999`, 3000)
  expect('H5 · 订单不存在时显示业务空状态', await innerText(), ['订单详情', '订单不存在'])
} else {
  record('H5 登录后页面（缺 token，跳过）', false, 'portal login failed')
}

// 4) 收尾：同一路由换参数不能停在旧数据上（曾经的真实 bug）
await goto(`${PORTAL_WEB}/#/product/2`, 3000)
await goto(`${PORTAL_WEB}/#/product/999999`, 2500)
const swapped = await waitFor(
  `document.body.innerText.includes('商品不存在或已下架') && !document.body.innerText.includes('小米 14')`,
  5000
)
record('H5 · 同路由换参数会重新加载', swapped, `换到不存在的商品后不再显示旧商品=${swapped}`)

// 5) 错误兜底：/__error-demo 是 dev-only 的「故意抛错」页（见各自 src/views/ErrorDemoView.vue），
//    用它证明「页面抛异常时用户看到的是兜底 UI 而不是白屏」。
//    这一段**会故意**产生 console.error，所以单独计数，验完从总账里摘掉，免得下面的零容忍断言误报。
log('')
log('--- 错误兜底（故意抛错的演示页）---')
const errBaseline = problems.length

await goto(`${ADMIN_WEB}/?r=err#/__error-demo`, 3500)
const adminBoundary = await innerText()
record(
  '管理后台 · 页面抛异常时显示兜底 UI 且保住侧边栏',
  adminBoundary.includes('页面出错了') &&
    adminBoundary.includes('__error_demo__') &&
    adminBoundary.includes('刷新页面') &&
    adminBoundary.includes('商品管理'),
  `len=${adminBoundary.length}`
)

await goto(`${PORTAL_WEB}/#/__error-demo`, 3500)
const h5Boundary = await innerText()
record(
  'H5 · 页面抛异常时显示兜底 UI 且保住 Tabbar',
  h5Boundary.includes('__error_demo__') && h5Boundary.includes('刷新页面') && h5Boundary.includes('首页'),
  `len=${h5Boundary.length}`
)

const boundaryProblems = problems.slice(errBaseline)
record(
  '错误兜底：每个演示页恰好上报 1 次（边界返回 false -> 不会重复上报）',
  boundaryProblems.length === 2,
  `count=${boundaryProblems.length} :: ${boundaryProblems.map((p) => p.slice(0, 70)).join(' | ')}`
)
// 这两条是预期内的，从总账摘掉
problems.splice(errBaseline)

// 6) 控制台告警 / 错误（Vue 未解析组件、运行时异常都会落在这里）
log('')
const uniq = [...new Set(problems)]
log(`--- 捕获到的控制台告警/错误：${uniq.length} 条 ---`)
for (const p of uniq.slice(0, 40)) log('   ' + p)
record('无控制台告警/错误', uniq.length === 0, `count=${uniq.length}`)

const allOk = checks.every(Boolean)
log('')
log(allOk ? 'ALL PASSED' : 'SOME CHECKS FAILED')
writeFileSync(`${OUT_DIR}/cdp-report.txt`, report.join('\r\n'), 'utf8')
console.log(`\n报告已写入 ${OUT_DIR}/cdp-report.txt`)

await close()
process.exit(allOk ? 0 : 1)

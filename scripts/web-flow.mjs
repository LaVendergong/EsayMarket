/**
 * 全流程交互测试 —— 真的去点按钮，把「注册 → 浏览 → 选规格 → 加购 → 购物车 → 建地址 → 下单 → 支付 → 后台发货 → 确认收货」
 * 整条链路跑一遍，最后再用接口核对数据库里的状态。
 *
 * 与 web-check.mjs 的分工：
 *   web-check.mjs：只验「页面渲染」，不点按钮（快、稳、抓组件级问题）
 *   web-flow.mjs ：真的操作页面（慢、但能证明业务闭环真的能跑通）
 *
 * 前置：两个后端 + 两个前端 dev server 都在跑；本机有 Chrome；MySQL 客户端在默认路径。
 * 用法：node scripts/web-flow.mjs
 * 产物：tmp\web\flow-report.txt
 *
 * 脚本自带清理：开跑前先真删上次残留，跑完再删本次数据（演示数据不受影响），可反复执行。
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { launch } from './cdp.mjs'

// ------------------------------------------------------------------ 配置
const ADMIN_WEB = 'http://localhost:5173'
const PORTAL_WEB = 'http://localhost:5174'
const ADMIN_API = 'http://localhost:8081'
const PORTAL_API = 'http://localhost:8090'

const MYSQL = 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'
const DB_PWD = '521131'

const BUYER = 'webflow1'
const BUYER_PWD = 'webflow123456'
const CAT = 'Web 流程测试分类'
const ITEM = 'Web 流程测试商品'
const SPEC = 'FLOW-A'
const OUT_DIR = 'D:/projects/mall/tmp/web'
const PRICE = 100
const STOCK = 10
const QTY = 2
const TOTAL = (PRICE * QTY).toFixed(2)

const report = []
const log = (s) => {
  report.push(s)
  console.log(s)
}

const results = []
function check(name, ok, detail) {
  results.push(ok)
  log(`[${ok ? 'PASS' : 'FAIL'}] ${name} :: ${detail}`)
}
function info(s) {
  log(`      · ${s}`)
}

// ------------------------------------------------------------------ MySQL 辅助
function sql(query) {
  const out = execFileSync(MYSQL, ['-uroot', `-p${DB_PWD}`, '-N', '--default-character-set=utf8mb4', '-e', `USE mall; ${query}`], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore']
  })
  return (out || '').trim()
}

function purge(message) {
  sql(
    `DELETE FROM t_order_item WHERE product_name = '${ITEM}'; ` +
      `DELETE o FROM t_order o WHERE o.user_id IN (SELECT id FROM t_user WHERE username = '${BUYER}'); ` +
      `DELETE FROM t_cart WHERE user_id IN (SELECT id FROM t_user WHERE username = '${BUYER}'); ` +
      `DELETE FROM t_address WHERE user_id IN (SELECT id FROM t_user WHERE username = '${BUYER}'); ` +
      `DELETE FROM t_user WHERE username = '${BUYER}'; ` +
      `DELETE s FROM t_sku s JOIN t_product p ON s.product_id = p.id WHERE p.name = '${ITEM}'; ` +
      `DELETE FROM t_product WHERE name = '${ITEM}'; ` +
      `DELETE FROM t_category WHERE name = '${CAT}';`
  )
  info(message)
}

// ------------------------------------------------------------------ HTTP 辅助
async function api(method, url, { body, token } = {}) {
  const r = await fetch(url, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  })
  // 未认证是真正的 HTTP 401 且 body 为空（见 docs §6.1），直接 r.json() 会抛 JSON 解析错
  const text = await r.text()
  if (!text) return { code: r.status, message: '(空响应体)' }
  try {
    return JSON.parse(text)
  } catch {
    return { code: r.status, message: text.slice(0, 200) }
  }
}

// ------------------------------------------------------------------ 准备测试数据
mkdirSync(OUT_DIR, { recursive: true })

purge('开跑前清掉上次残留')

const adminLogin = await api('POST', `${ADMIN_API}/api/admin/auth/login`, {
  body: { username: 'malladmin', password: 'admin123456' }
})
const adminToken = adminLogin.data?.token
check('准备：管理员登录', adminLogin.code === 200, `code=${adminLogin.code} role=${adminLogin.data?.role}`)

const catR = await api('POST', `${ADMIN_API}/api/admin/category`, {
  body: { parentId: 0, name: CAT, sort: 93 },
  token: adminToken
})
const catId = catR.data
const prodR = await api('POST', `${ADMIN_API}/api/admin/product`, {
  body: {
    categoryId: catId,
    name: ITEM,
    subTitle: '全流程测试用',
    price: PRICE,
    status: 1,
    skus: [{ skuCode: SPEC, spec: SPEC, price: PRICE, stock: STOCK }]
  },
  token: adminToken
})
const prodId = prodR.data
check('准备：测试商品已就绪', catR.code === 200 && prodR.code === 200 && !!prodId, `categoryId=${catId} productId=${prodId} stock=${STOCK} price=${PRICE}`)

// ------------------------------------------------------------------ 起浏览器
const browser = await launch({
  port: 9334,
  profileDir: `${OUT_DIR}/flow-profile`,
  width: 420,
  height: 900
})
const { goto, evalJs, innerText, waitFor, clickText, clickSelector, clickIn, setInput, setStorage, close } = browser

/** 按 van-cell 的标题文字定位输入框（省份/城市/区县 三个 placeholder 都是「选填」，只能按标签找） */
function setFieldByLabel(label, value) {
  return evalJs(
    `(() => {
      const cell = [...document.querySelectorAll('.van-cell')]
        .find((c) => (c.querySelector('.van-cell__title')?.innerText || '').trim() === ${JSON.stringify(label)});
      const el = cell?.querySelector('input, textarea');
      if (!el) return false;
      const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(value)});
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    })()`
  )
}

const onRoute = (fragment) => `location.hash.startsWith('#/${fragment}')`
const pageHas = (text) => `document.body.innerText.includes(${JSON.stringify(text)})`
/** 等页面上真的出现这段文字（路由跳转只是 hash 变了，内容可能还在加载） */
const waitText = (text, ms = 8000) => waitFor(pageHas(text), ms)
const waitSel = (selector, ms = 8000) => waitFor(`!!document.querySelector(${JSON.stringify(selector)})`, ms)

let orderNo = ''
let orderId = 0

try {
  // ================================================================ H5：注册
  log('')
  log('--- H5：注册 ---')
  await goto(`${PORTAL_WEB}/#/register`, 2000)
  await setInput('input[placeholder="4-32 位"]', BUYER)
  await setInput('input[placeholder="6-64 位"]', BUYER_PWD)
  const okFill = await evalJs(`[...document.querySelectorAll('input')].filter((i) => i.value).length >= 2`)
  check('注册表单可填写', okFill === true, `已填 ${await evalJs(`[...document.querySelectorAll('input')].filter((i) => i.value).length`)} 个输入框`)

  await clickText('.van-button', '注册并登录')
  const registered = await waitFor(onRoute('home'), 10000)
  check('注册并自动登录后进入首页', registered, `hash=${await evalJs('location.hash')}`)

  // ================================================================ H5：首页 → 商品详情
  log('')
  log('--- H5：浏览 → 选规格 → 加购 ---')
  const sawProduct = await waitFor(pageHas(ITEM), 8000)
  check('首页列表出现测试商品', sawProduct, `找到「${ITEM}」=${sawProduct}`)

  await clickText('.van-card', ITEM)
  const onDetail = await waitFor(onRoute('product/'), 6000)
  check('点商品卡片进入详情页', onDetail, `hash=${await evalJs('location.hash')}`)

  const detailReady = await waitFor(
    `document.body.innerText.includes(${JSON.stringify(ITEM)}) && !!document.querySelector('.van-action-bar-button')`,
    8000
  )
  check('详情页渲染出商品与底部操作栏', detailReady, `actionBar=${await evalJs(`document.querySelectorAll('.van-action-bar-button').length`)} 个按钮`)

  await clickText('.van-tag', SPEC)
  await clickSelector('.van-stepper__plus')
  const qtyIsTwo = await waitFor(`document.querySelector('.van-stepper__input')?.value === '${QTY}'`, 4000)
  check(
    `详情页数量步进器加到 ${QTY}`,
    qtyIsTwo,
    `qty=${await evalJs(`document.querySelector('.van-stepper__input')?.value`)}（期望 ${QTY}）`
  )

  await clickText('.van-action-bar-button', '加入购物车')
  const addedToast = await waitFor(pageHas('已加入购物车'), 5000)
  check('点击「加入购物车」有成功反馈', addedToast, `toast 出现=${addedToast}`)

  // ================================================================ H5：购物车
  await clickText('.van-action-bar-icon', '购物车')
  const onCart = await waitFor(onRoute('cart'), 6000)
  const cartItemVisible = await waitText(ITEM)
  const cartAmount = await waitText(TOTAL, 5000)
  const cartText = await innerText()
  check(
    `购物车出现该商品且金额=${TOTAL}`,
    onCart && cartItemVisible && cartAmount,
    `hash=${await evalJs('location.hash')} 商品可见=${cartItemVisible} 金额${TOTAL}=${cartAmount} | 文本="${cartText.slice(0, 150)}"`
  )

  await clickText('.van-submit-bar__button', '去结算')
  const onCheckout = await waitFor(onRoute('checkout'), 6000)
  check('点击「去结算」进入确认订单', onCheckout, `hash=${await evalJs('location.hash')}`)

  // ================================================================ H5：新增收货地址
  log('')
  log('--- H5：新增收货地址 ---')
  await waitFor(pageHas('还没有收货地址'), 5000)
  await clickText('.van-cell', '去添加')
  const onAddrNew = await waitFor(onRoute('address/new'), 6000)
  check('进入新增地址页', onAddrNew, `hash=${await evalJs('location.hash')}`)

  await setInput('input[placeholder="姓名"]', '流程测试收货人')
  await setInput('input[placeholder="手机号"]', '13700000000')
  await setFieldByLabel('省份', '广东省')
  await setFieldByLabel('城市', '深圳市')
  await setFieldByLabel('区县', '南山区')
  await setInput('textarea[placeholder="街道门牌等"]', '全流程测试路 1 号')
  await clickSelector('.van-switch')
  const filled = await evalJs(
    `document.querySelector('input[placeholder="姓名"]').value.length > 0
     && document.querySelector('textarea[placeholder="街道门牌等"]').value.length > 0`
  )
  check('地址表单填写完整', filled === true, `姓名/详细地址已填=${filled}`)

  await clickText('.van-button', '保存')
  const backToCheckout = await waitFor(onRoute('checkout'), 8000)
  await waitFor(pageHas('流程测试收货人'), 6000)
  const checkoutText = await innerText()
  check(
    '保存地址后回结算页且地址已带上',
    backToCheckout && checkoutText.includes('流程测试收货人') && checkoutText.includes('应付金额'),
    `hash=${await evalJs('location.hash')} 收货人可见=${checkoutText.includes('流程测试收货人')}`
  )

  // ================================================================ H5：提交订单
  log('')
  log('--- H5：下单 → 支付 ---')
  await clickText('.van-submit-bar__button', '提交订单')
  const onOrder = await waitFor(onRoute('order/'), 10000)
  const orderReady = await waitText('待支付')
  const orderText = await innerText()
  check(
    '提交订单后跳转订单详情且状态为待支付',
    onOrder && orderReady,
    `hash=${await evalJs('location.hash')} 待支付=${orderReady} | 文本="${orderText.slice(0, 150)}"`
  )

  // 拿订单号/订单 id，后面后台发货要用
  const buyerId = sql(`SELECT id FROM t_user WHERE username = '${BUYER}';`)
  orderId = Number(sql(`SELECT id FROM t_order WHERE user_id = ${buyerId} ORDER BY id DESC LIMIT 1;`))
  orderNo = sql(`SELECT order_no FROM t_order WHERE id = ${orderId};`)
  check('订单已落库', orderId > 0 && !!orderNo, `orderId=${orderId} orderNo=${orderNo}`)
  check(
    `下单只锁不定总库存（${STOCK},${QTY}）`,
    sql(`SELECT CONCAT(stock,',',IFNULL(locked_stock,0)) FROM t_sku WHERE product_id = ${prodId};`) === `${STOCK},${QTY}`,
    `sku=${sql(`SELECT CONCAT(stock,',',IFNULL(locked_stock,0)) FROM t_sku WHERE product_id = ${prodId};`)}（期望 ${STOCK},${QTY}）`
  )

  await waitSel('.footer .van-button')
  await clickText('.footer .van-button', '去支付')
  const paid = await waitFor(pageHas('已支付'), 8000)
  const afterPay = await innerText()
  check('前端支付成功且状态变为已支付', paid && afterPay.includes('已支付'), `已支付=${afterPay.includes('已支付')}`)
  check(
    `支付后真正扣库存并释放锁定（${STOCK - QTY},0）、销量 =${QTY}`,
    sql(`SELECT CONCAT(stock,',',IFNULL(locked_stock,0)) FROM t_sku WHERE product_id = ${prodId};`) === `${STOCK - QTY},0` &&
      sql(`SELECT sales FROM t_product WHERE id = ${prodId};`) === String(QTY),
    `sku=${sql(`SELECT CONCAT(stock,',',IFNULL(locked_stock,0)) FROM t_sku WHERE product_id = ${prodId};`)} sales=${sql(`SELECT sales FROM t_product WHERE id = ${prodId};`)}`
  )

  // ================================================================ 后台：发货
  log('')
  log('--- 管理后台：发货 ---')
  await goto(`${ADMIN_WEB}/`, 1500)
  await setStorage({ mall_admin_token: adminToken, mall_admin_user: JSON.stringify(adminLogin.data) })
  await goto(`${ADMIN_WEB}/?flow=1#/order`, 4000)
  const sawOrderRow = await waitFor(pageHas(orderNo), 8000)
  check('后台订单列表能看到该订单', sawOrderRow, `orderNo=${orderNo} 可见=${sawOrderRow}`)

  // 发货前先确认订单在库里是「已支付」
  check('发货前订单状态=已支付', sql(`SELECT status FROM t_order WHERE id = ${orderId};`) === '1', `status=${sql(`SELECT status FROM t_order WHERE id = ${orderId};`)}`)

  await clickIn('.el-table__row', orderNo, '.el-button', '发货')
  const dialogUp = await waitFor(`!!document.querySelector('.el-message-box__btns')`, 5000)
  check('发货弹出二次确认', dialogUp, `确认框出现=${dialogUp}`)
  await clickSelector('.el-message-box__btns .el-button--primary')
  const shipped = await waitFor(pageHas('已发货'), 8000)
  check('后台发货成功，状态=已发货', shipped && sql(`SELECT status FROM t_order WHERE id = ${orderId};`) === '2', `db status=${sql(`SELECT status FROM t_order WHERE id = ${orderId};`)}（期望 2）`)

  // ================================================================ H5：确认收货
  log('')
  log('--- H5：确认收货 ---')
  await goto(`${PORTAL_WEB}/#/order/${orderId}`, 3500)
  const shippedOnH5 = await waitFor(pageHas('已发货'), 8000)
  check('H5 订单详情看到「已发货」', shippedOnH5, `已发货=${shippedOnH5}`)

  await waitSel('.footer .van-button')
  await clickText('.footer .van-button', '确认收货')
  const confirmUp = await waitFor(`!!document.querySelector('.van-dialog__confirm')`, 5000)
  check('确认收货弹出二次确认', confirmUp, `确认框出现=${confirmUp}`)
  await clickSelector('.van-dialog__confirm')
  const finished = await waitFor(pageHas('已完成'), 8000)
  check('确认收货成功，状态=已完成', finished && sql(`SELECT status FROM t_order WHERE id = ${orderId};`) === '3', `db status=${sql(`SELECT status FROM t_order WHERE id = ${orderId};`)}（期望 3）`)

  // ================================================================ H5：立即购买（直连下单，不碰购物车）
  log('')
  log('--- H5：立即购买（不破坏购物车勾选） ---')
  const apiToken = (await api('POST', `${PORTAL_API}/api/portal/auth/login`, {
    body: { username: BUYER, password: BUYER_PWD }
  })).data?.token
  // 本脚本前面一直按 product_id 定位，这里要用 sku_id 直接加购/直连下单，所以补一次查询
  const skuId = Number(sql(`SELECT id FROM t_sku WHERE product_id = ${prodId} LIMIT 1;`))
  info(`测试商品 skuId=${skuId}`)

  // 先在购物车放一件并【取消勾选】：老实现是「先加购再把其它条目取消勾选」绕出来的，
  // 只有让购物车处于「有货但未勾选」的状态，才能验出新老行为的差异
  const addResp = await api('POST', `${PORTAL_API}/api/portal/cart`, {
    body: { skuId, quantity: 1 },
    token: apiToken
  })
  const cartBefore = (await api('GET', `${PORTAL_API}/api/portal/cart`, { token: apiToken })).data
  check('预备：购物车里放进 1 件', addResp.code === 200 && cartBefore.length === 1, `addCode=${addResp.code} size=${cartBefore.length}`)
  await api('PUT', `${PORTAL_API}/api/portal/cart/${cartBefore[0].id}/checked?checked=0`, { token: apiToken })
  const cartPre = (await api('GET', `${PORTAL_API}/api/portal/cart`, { token: apiToken })).data
  info(`购物车预备状态：size=${cartPre.length} checked=${cartPre[0]?.checked}（期望 1 / 0）`)

  await goto(`${PORTAL_WEB}/#/product/${prodId}`, 3500)
  await waitSel('.van-action-bar-button')
  await clickSelector('.van-stepper__plus') // 数量 1 -> 2，用来区分「直连下单」和「购物车那件（1 件）」
  await clickText('.van-action-bar-button', '立即购买')
  const onCo2 = await waitFor(onRoute('checkout'), 8000)
  const co2Ready = await waitText('应付金额')
  const co2Text = await innerText()
  check(
    '立即购买进入确认订单页（购物车未勾选也能下单）',
    onCo2 && co2Ready && co2Text.includes(ITEM),
    `hash=${await evalJs('location.hash')} 含商品=${co2Text.includes(ITEM)}`
  )

  await clickText('.van-submit-bar__button', '提交订单')
  const onOrder2 = await waitFor(onRoute('order/'), 10000)
  const order2Ready = await waitText('待支付')
  check('立即购买下单成功（订单详情显示待支付）', onOrder2 && order2Ready, `hash=${await evalJs('location.hash')}`)

  // 订单金额按步进器的 2 件算（100 x 2），并且带上了幂等键
  const directOrderId = Number(sql(`SELECT id FROM t_order WHERE user_id = ${buyerId} ORDER BY id DESC LIMIT 1;`))
  const directRow = sql(`SELECT CONCAT(pay_amount, ',', IFNULL(request_id, '')) FROM t_order WHERE id = ${directOrderId};`)
  const [directAmount, directReqId] = directRow.split(',')
  check('立即购买订单金额 = 数量 x 单价（200.00）', directAmount === '200.00', `pay_amount=${directAmount}`)
  check('立即购买订单带幂等键 requestId', !!directReqId, `request_id=${directReqId ? 'set' : '(空)'}`)

  // 核心：直连下单必须完全没有动购物车
  const cartAfter = (await api('GET', `${PORTAL_API}/api/portal/cart`, { token: apiToken })).data
  check(
    '立即购买没有破坏购物车原有的勾选',
    cartAfter.length === 1 && cartAfter[0].checked === 0 && cartAfter[0].skuId === skuId,
    `size=${cartAfter.length} checked=${cartAfter[0]?.checked} skuId=${cartAfter[0]?.skuId}`
  )

  // ================================================================ 收尾核对
  log('')
  log('--- 收尾核对 ---')
  const finalOrder = await api('GET', `${PORTAL_API}/api/portal/order/${orderId}`, { token: (await api('POST', `${PORTAL_API}/api/portal/auth/login`, { body: { username: BUYER, password: BUYER_PWD } })).data?.token })
  check(
    '订单最终状态与收货快照正确',
    finalOrder.code === 200 && finalOrder.data.status === 3 && finalOrder.data.statusText === '已完成' && finalOrder.data.items.length === 1 && finalOrder.data.receiver === '流程测试收货人',
    `code=${finalOrder.code} statusText=${finalOrder.data?.statusText} items=${finalOrder.data?.items?.length} receiver=${finalOrder.data?.receiver}`
  )
} finally {
  // ---------------------------------------------------------------- 控制台告警
  log('')
  const uniq = [...new Set(browser.problems)]
  log(`--- 整个流程中捕获到的控制台告警/错误：${uniq.length} 条 ---`)
  for (const p of uniq.slice(0, 30)) log('   ' + p)
  check('全流程无控制台告警/错误', uniq.length === 0, `count=${uniq.length}`)

  // ---------------------------------------------------------------- 清理
  log('')
  try {
    purge('已清理本次测试数据（不影响演示数据）')
    check('测试数据清理完成', sql(`SELECT COUNT(*) FROM t_user WHERE username = '${BUYER}';`) === '0', '残留用户=0')
  } catch (e) {
    check('测试数据清理完成', false, String(e.message))
  }

  await close()
}

const allOk = results.every(Boolean)
log('')
log(allOk ? `全流程通过（${results.length}/${results.length}）` : `有失败项（${results.filter(Boolean).length}/${results.length}）`)
mkdirSync(OUT_DIR, { recursive: true })
writeFileSync(`${OUT_DIR}/flow-report.txt`, report.join('\r\n'), 'utf8')
console.log(`\n报告已写入 ${OUT_DIR}/flow-report.txt`)
process.exit(allOk ? 0 : 1)

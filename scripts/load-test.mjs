// ============================================================================
// 压测基线（读 / 下单抢库存 / 支付）
//
// 为什么需要它：前面所有验证都是"功能对不对"，没有一个数字回答"能扛多少"。
// 这个脚本给出 QPS / P50 / P95 / P99 / 错误率，并且顺便在真实并发下验证
// **并发抢库存不超卖** —— 这条在单测里只能靠 mock，压测里是真实的数据库竞争。
//
// 用法：
//   node scripts/load-test.mjs
//   LOAD_READ_CONCURRENCY=32 LOAD_READ_REQUESTS=800 node scripts/load-test.mjs
//
// 输出：控制台报告 + tmp/load/summary.txt
// ============================================================================

import { writeFileSync, mkdirSync } from 'node:fs'

const PORTAL = process.env.PORTAL_API || 'http://localhost:8090'
const ADMIN = process.env.ADMIN_API || 'http://localhost:8081'
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'

const READ_CONCURRENCY = Number(process.env.LOAD_READ_CONCURRENCY || 24)
const READ_REQUESTS = Number(process.env.LOAD_READ_REQUESTS || 400)
const BUYERS = Number(process.env.LOAD_BUYERS || 24)
const STOCK = Number(process.env.LOAD_STOCK || 8)

/** 断言阈值：本地开发机（未调优 JVM）的基线，超标说明有明显退化 */
const THRESHOLDS = {
  readP95: Number(process.env.LOAD_READ_P95_MS || 800),
  writeP95: Number(process.env.LOAD_WRITE_P95_MS || 2500)
}

const STAMP = Date.now().toString().slice(-8)
const CAT = `Load Cat ${STAMP}`
const ITEM = `Load Item ${STAMP}`
const SPEC = `LOAD-${STAMP}`
const PRICE = 20

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? '[PASS]' : '[FAIL]'} ${name}${detail ? ` :: ${detail}` : ''}`)
}

async function req(method, url, { token, body } = {}) {
  const started = performance.now()
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  let status = 0
  let json = null
  try {
    const resp = await fetch(url, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined
    })
    status = resp.status
    const text = await resp.text()
    json = text ? JSON.parse(text) : null
  } catch (e) {
    status = -1
    json = { code: -1, message: String(e.message || e) }
  }
  return { status, code: json?.code, data: json?.data, message: json?.message, ms: performance.now() - started }
}

function percentile(sorted, p) {
  if (!sorted.length) return 0
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)
  return sorted[idx]
}

function stats(latencies) {
  const sorted = [...latencies].sort((a, b) => a - b)
  const sum = sorted.reduce((a, b) => a + b, 0)
  return {
    count: sorted.length,
    avg: sum / Math.max(1, sorted.length),
    p50: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: sorted[sorted.length - 1] || 0
  }
}

/** 固定并发度的 worker 池：每个 worker 串行取任务，保证并发数可控 */
async function pool(concurrency, tasks, fn) {
  let next = 0
  const out = []
  const workers = Array.from({ length: Math.min(concurrency, tasks.length) }, async () => {
    for (;;) {
      const i = next++
      if (i >= tasks.length) return
      out[i] = await fn(tasks[i], i)
    }
  })
  await Promise.all(workers)
  return out
}

function qps(count, ms) {
  return (count / (ms / 1000)).toFixed(1)
}

const { execFileSync } = await import('node:child_process')
function query(q) {
  try {
    return execFileSync(MYSQL, ['-uroot', '-p521131', '-N', '--default-character-set=utf8mb4', '-e', `USE mall; ${q}`], {
      stdio: ['ignore', 'pipe', 'ignore']
    }).toString().trim()
  } catch {
    return ''
  }
}

function purge() {
  query(`DELETE FROM t_after_sale WHERE user_id IN (SELECT id FROM t_user WHERE username LIKE 'load${STAMP}%');`)
  query(`DELETE FROM t_payment WHERE user_id IN (SELECT id FROM t_user WHERE username LIKE 'load${STAMP}%');`)
  query(`DELETE FROM t_order_item WHERE order_id IN (SELECT id FROM t_order WHERE user_id IN (SELECT id FROM t_user WHERE username LIKE 'load${STAMP}%'));`)
  query(`DELETE FROM t_order WHERE user_id IN (SELECT id FROM t_user WHERE username LIKE 'load${STAMP}%');`)
  query(`DELETE FROM t_cart WHERE user_id IN (SELECT id FROM t_user WHERE username LIKE 'load${STAMP}%');`)
  query(`DELETE FROM t_address WHERE user_id IN (SELECT id FROM t_user WHERE username LIKE 'load${STAMP}%');`)
  query(`DELETE FROM t_user WHERE username LIKE 'load${STAMP}%';`)
  query(`DELETE s FROM t_sku s JOIN t_product p ON s.product_id = p.id WHERE p.name = '${ITEM}';`)
  query(`DELETE FROM t_product WHERE name = '${ITEM}';`)
  query(`DELETE FROM t_category WHERE name = '${CAT}';`)
}

async function main() {
  const startedAt = Date.now()
  console.log(`== 环境 ==`)
  console.log(`portal=${PORTAL} admin=${ADMIN} 并发=${READ_CONCURRENCY} 读请求=${READ_REQUESTS} 买家=${BUYERS} 库存=${STOCK}`)

  // ---------------------------------------------------------------- setup
  const adm = await req('POST', `${ADMIN}/api/v1/admin/auth/login`, {
    body: { username: 'malladmin', password: 'admin123456' }
  })
  const adminToken = adm.data?.token
  check('后台登录成功', adm.code === 200 && !!adminToken, `code=${adm.code}`)

  purge()
  const cat = await req('POST', `${ADMIN}/api/v1/admin/category`, {
    token: adminToken, body: { parentId: 0, name: CAT, sort: 100 }
  })
  const prod = await req('POST', `${ADMIN}/api/v1/admin/product`, {
    token: adminToken,
    body: {
      categoryId: cat.data, name: ITEM, subTitle: 'load', price: PRICE,
      skus: [{ skuCode: SPEC, spec: SPEC, price: PRICE, stock: STOCK }]
    }
  })
  const prodId = prod.data
  const skuId = Number(query(`SELECT id FROM t_sku WHERE product_id = ${prodId} LIMIT 1;`))
  check('压测商品就绪', !!prodId && skuId > 0, `productId=${prodId} skuId=${skuId} stock=${STOCK}`)

  // ---------------------------------------------------------------- 预热
  // JIT / 连接池冷启动会让第一批请求的延迟高一个数量级；不预热测出来的数字没有可比性
  console.log('\n== 预热（不计入统计）==')
  await pool(8, Array.from({ length: 60 }, (_, i) => i), async (i) =>
    req('GET', i % 2 === 0
      ? `${PORTAL}/api/v1/portal/product/page?pageNum=1&pageSize=10`
      : `${PORTAL}/api/v1/portal/product/${prodId}`))
  console.log('  预热 60 次完成')

  // ---------------------------------------------------------------- A) 读压测
  console.log('\n== A) 读压测（商品列表 + 详情）==')
  const readTasks = Array.from({ length: READ_REQUESTS }, (_, i) => i)
  const t1 = performance.now()
  const readResults = await pool(READ_CONCURRENCY, readTasks, async (i) => {
    const url = i % 2 === 0
      ? `${PORTAL}/api/v1/portal/product/page?pageNum=1&pageSize=10`
      : `${PORTAL}/api/v1/portal/product/${prodId}`
    return req('GET', url)
  })
  const readMs = performance.now() - t1
  const readOk = readResults.filter((r) => r.status === 200 && r.code === 200).length
  const readLat = readResults.map((r) => r.ms)
  const rs = stats(readLat)
  console.log(`  请求 ${rs.count}，成功 ${readOk}，耗时 ${readMs.toFixed(0)}ms，QPS ${qps(readOk, readMs)}`)
  console.log(`  P50 ${rs.p50.toFixed(1)}ms  P95 ${rs.p95.toFixed(1)}ms  P99 ${rs.p99.toFixed(1)}ms  max ${rs.max.toFixed(1)}ms`)

  check('A1 读请求零错误', readOk === readResults.length, `ok=${readOk}/${readResults.length}`)
  check(`A2 读 P95 < ${THRESHOLDS.readP95}ms`, rs.p95 < THRESHOLDS.readP95, `p95=${rs.p95.toFixed(1)}ms`)

  // ---------------------------------------------------------------- B) 抢库存
  console.log(`\n== B) 并发下单抢库存（${BUYERS} 买家 / 库存 ${STOCK}）==`)
  const buyers = []
  for (let i = 0; i < BUYERS; i++) {
    const username = `load${STAMP}${String(i).padStart(3, '0')}`
    await req('POST', `${PORTAL}/api/v1/portal/auth/register`, {
      body: { username, password: 'load123456', nickname: `L${i}` }
    })
    const login = await req('POST', `${PORTAL}/api/v1/portal/auth/login`, {
      body: { username, password: 'load123456' }
    })
    const token = login.data?.token
    const addr = await req('POST', `${PORTAL}/api/v1/portal/address`, {
      token,
      body: { receiver: `L${i}`, phone: '13100001111', province: 'GD', city: 'SZ', district: 'NS', detail: `Road ${i}`, isDefault: 1 }
    })
    buyers.push({ token, addressId: addr.data })
  }
  check('B0 压测买家就绪', buyers.every((b) => b.token && b.addressId), `count=${buyers.length}`)

  const t2 = performance.now()
  const orderResults = await pool(BUYERS, buyers, async (b, i) => {
    const add = await req('POST', `${PORTAL}/api/v1/portal/cart`, {
      token: b.token, body: { skuId, quantity: 1 }
    })
    // 加购阶段就因库存不足失败时，购物车里没有东西，再下单只会得到「购物车为空」，
    // 那是我脚本的噪音，不是业务错误 —— 直接记为库存不足
    if (add.code === 2003) return { addCode: 2003, orderCode: 2003, token: b.token, ms: add.ms }
    if (add.code !== 200) return { addCode: add.code, orderCode: null, token: b.token, ms: add.ms }
    const order = await req('POST', `${PORTAL}/api/v1/portal/order`, {
      token: b.token, body: { addressId: b.addressId, requestId: `load-${STAMP}-${i}` }
    })
    return { addCode: add.code, orderCode: order.code, orderId: order.data?.orderId, token: b.token, ms: add.ms + order.ms }
  })
  const orderMs = performance.now() - t2

  const created = orderResults.filter((r) => r.orderCode === 200 && r.orderId)
  const rejected = orderResults.filter((r) => r.orderCode === 2003)
  const other = orderResults.filter((r) => r.orderCode !== 200 && r.orderCode !== 2003)
  const ws = stats(orderResults.map((r) => r.ms))
  const codeDist = orderResults.reduce((m, r) => { const k = r.orderCode === null ? `add:${r.addCode}` : String(r.orderCode); m[k] = (m[k] || 0) + 1; return m }, {})
  console.log(`  成功下单 ${created.length}，库存不足 ${rejected.length}，其它错误 ${other.length}，耗时 ${orderMs.toFixed(0)}ms`)
  console.log(`  错误码分布：${JSON.stringify(codeDist)}`)
  console.log(`  P50 ${ws.p50.toFixed(1)}ms  P95 ${ws.p95.toFixed(1)}ms  P99 ${ws.p99.toFixed(1)}ms`)

  check('B1 成功下单数不超过库存（不超卖）', created.length <= STOCK, `created=${created.length} stock=${STOCK}`)
  check('B2 恰好卖出全部库存（并发没把可售库存丢掉）', created.length === STOCK, `created=${created.length} expected=${STOCK}`)
  check('B3 其余买家收到明确的库存不足（2003）', rejected.length === BUYERS - STOCK && other.length === 0,
    `rejected=${rejected.length} other=${other.length}`)

  const dbOrders = Number(query(`SELECT COUNT(*) FROM t_order WHERE user_id IN (SELECT id FROM t_user WHERE username LIKE 'load${STAMP}%');`))
  const dbLocked = Number(query(`SELECT IFNULL(locked_stock,0) FROM t_sku WHERE id = ${skuId};`))
  const dbStock = Number(query(`SELECT IFNULL(stock,0) FROM t_sku WHERE id = ${skuId};`))
  check('B4 库里订单数与成功数一致', dbOrders === created.length, `db=${dbOrders} api=${created.length}`)
  check('B5 锁定库存 = 已售出数量，库存总量未变', dbLocked === created.length && dbStock === STOCK,
    `stock=${dbStock} locked=${dbLocked}`)
  check(`B6 下单 P95 < ${THRESHOLDS.writeP95}ms`, ws.p95 < THRESHOLDS.writeP95, `p95=${ws.p95.toFixed(1)}ms`)

  // ---------------------------------------------------------------- C) 支付
  console.log('\n== C) 支付压测 ==')
  // 用下单成功的买家 token（按 orderId 回查归属，简单起见用 SQL 反查用户再登录太重，这里按下标近似）
  // 用下单时那个买家的 token：重新登录会撞上登录失败限流（多个并发登录直接 429）
  const payResults = await pool(8, created, async (c) =>
    req('POST', `${PORTAL}/api/v1/portal/order/${c.orderId}/pay`, { token: c.token }))
  const paidOk = payResults.filter((r) => r.code === 200).length
  const payDist = payResults.reduce((m, r) => { m[r.code] = (m[r.code] || 0) + 1; return m }, {})
  console.log(`  支付结果分布：${JSON.stringify(payDist)}`)
  const ps = stats(payResults.map((r) => r.ms))
  console.log(`  支付成功 ${paidOk}/${payResults.length}，P95 ${ps.p95.toFixed(1)}ms`)

  check('C1 全部订单支付成功', paidOk === payResults.length, `ok=${paidOk}/${payResults.length}`)
  const paidStock = Number(query(`SELECT IFNULL(stock,0) FROM t_sku WHERE id = ${skuId};`))
  const paidLocked = Number(query(`SELECT IFNULL(locked_stock,0) FROM t_sku WHERE id = ${skuId};`))
  const sales = Number(query(`SELECT IFNULL(sales,0) FROM t_product WHERE id = ${prodId};`))
  check('C2 支付后库存真扣减、锁定清零', paidStock === 0 && paidLocked === 0, `stock=${paidStock} locked=${paidLocked}`)
  check('C3 销量只累加一次', sales === created.length, `sales=${sales} expected=${created.length}`)

  // ---------------------------------------------------------------- 汇总
  console.log('\n== 汇总 ==')
  const pass = results.filter((r) => r.ok).length
  console.log(`断言 ${results.length} 项，通过 ${pass} 项，失败 ${results.length - pass} 项`)
  console.log(`读：${qps(readOk, readMs)} QPS，P95 ${rs.p95.toFixed(0)}ms ｜ 下单：${qps(orderResults.length, orderMs)} ops/s，P95 ${ws.p95.toFixed(0)}ms ｜ 支付：P95 ${ps.p95.toFixed(0)}ms`)

  purge()
  mkdirSync('tmp/load', { recursive: true })
  const lines = [
    `Load test ${new Date().toISOString()}`,
    `portal=${PORTAL} read=${READ_CONCURRENCY}x${READ_REQUESTS} buyers=${BUYERS} stock=${STOCK}`,
    `read: qps=${qps(readOk, readMs)} p50=${rs.p50.toFixed(1)} p95=${rs.p95.toFixed(1)} p99=${rs.p99.toFixed(1)} errors=${readResults.length - readOk}`,
    `order: ops=${qps(orderResults.length, orderMs)} p50=${ws.p50.toFixed(1)} p95=${ws.p95.toFixed(1)} p99=${ws.p99.toFixed(1)} created=${created.length} rejected=${rejected.length}`,
    `pay: ok=${paidOk}/${payResults.length} p95=${ps.p95.toFixed(1)}`,
    `total=${results.length} pass=${pass} fail=${results.length - pass} duration=${((Date.now() - startedAt) / 1000).toFixed(1)}s`,
    ...results.filter((r) => !r.ok).map((r) => `FAIL ${r.name} :: ${r.detail}`)
  ]
  writeFileSync('tmp/load/summary.txt', lines.join('\n'))

  process.exit(results.length - pass)
}

main().catch((e) => {
  console.error('压测脚本异常：', e)
  process.exit(1)
})

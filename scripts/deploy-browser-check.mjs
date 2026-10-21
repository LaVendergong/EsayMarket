/**
 * 部署后的浏览器冒烟（对着**生产构建产物 + 容器里的后端**跑）。
 *
 * 为什么需要它：`deploy-verify.sh` 全走 curl，证明不了「浏览器认不认 prod 的 Secure Cookie」。
 * prod 下 Cookie 带 `Secure`，而本地没有 HTTPS —— 浏览器只对 `localhost` 开这个口子。
 * 这条链路一旦不成立，用户"登录成功却立刻被踢回登录页"，而 curl 测试完全发现不了。
 *
 * 断言：
 *   1) 页面能加载（生产构建，没有 dev-only 路由）
 *   2) 通过 UI 真实登录成功
 *   3) localStorage 里没有 token，document.cookie 里只有 csrf（凭证确实不可被 JS 读）
 *   4) 登录后的**受保护接口**能用（证明 Secure Cookie 被浏览器接受了，而不是静默丢弃）
 *   5) 控制台无报错
 *
 * 用法：
 *   PORTAL_WEB=http://localhost:15174 PORTAL_API=http://localhost:18090 node scripts/deploy-browser-check.mjs
 */
import { launch } from './cdp.mjs'

const PORTAL_WEB = process.env.PORTAL_WEB || 'http://localhost:15174'
const PORTAL_API = process.env.PORTAL_API || 'http://localhost:18090'
const STAMP = Date.now().toString().slice(-6)
const USER = `bsmoke${STAMP}`
const PWD = 'bsmoke123456'

const results = []
const check = (name, ok, detail = '') => {
  results.push(!!ok)
  console.log(`[${ok ? 'PASS' : 'FAIL'}] ${name}${detail ? ' :: ' + detail : ''}`)
}

// 准备账号（走部署环境自己的接口）
const reg = await fetch(`${PORTAL_API}/api/v1/portal/auth/register`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: USER, password: PWD, nickname: 'B' })
}).then((r) => r.json())
check('setup: test user registered on the deployed stack', reg.code === 200, `code=${reg.code}`)

const browser = await launch({ port: 9340, profile: 'deploy-browser-profile', downloadDir: 'tmp/deploy-browser' })
const { goto, evalJs, innerText, waitFor, clickText, setInput, close } = browser

await goto(`${PORTAL_WEB}/#/login`, 3500)
const loginPage = await innerText()
check('1 login page renders from the production build', loginPage.includes('登录'), `len=${loginPage.length}`)

await setInput('input[placeholder="请输入用户名"]', USER)
await setInput('input[placeholder="请输入密码"]', PWD)
await clickText('.van-button', '登录')
const onHome = await waitFor(`location.hash.startsWith('#/home')`, 10000)
check('2 UI login succeeded against the deployed stack', onHome, `hash=${await evalJs('location.hash')}`)

const storedToken = await evalJs(`localStorage.getItem('mall_portal_token')`)
const jsCookies = (await evalJs('document.cookie || ""')) || ''
check('3 no token in localStorage', !storedToken, `token=${storedToken ? 'LEAKED' : 'none'}`)
check('3 login cookie is invisible to JS (only csrf visible)', !jsCookies.includes('mall_portal_token') && jsCookies.includes('mall_csrf'), `cookie=${jsCookies}`)

// 关键：真的发一个受保护请求。Secure Cookie 若被浏览器丢掉，这里必然 401。
const me = await evalJs(`(async () => {
  const r = await fetch('/api/v1/portal/user/me', { credentials: 'include' });
  return r.status;
})()`)
check('4 protected api works from the browser (Secure cookie was accepted)', Number(me) === 200, `http=${me}`)

// 首页应该显示已登录态（用户名/昵称）
const homeText = await innerText()
check('5 home page renders after login', homeText.includes('首页') || homeText.includes('我的'), `len=${homeText.length}`)

const uniq = [...new Set(browser.problems)]
console.log(`--- console problems: ${uniq.length} ---`)
for (const p of uniq.slice(0, 10)) console.log('   ' + p)
// 已知噪声：应用启动时会先打一次 /user/me 探登录态，未登录必然 401，浏览器会把它记成资源错误。
// 这是**预期行为**，不是问题，所以单独摘出来（其余告警仍然零容忍）。
const NOISE = /Failed to load resource.*401/
const real = uniq.filter((p) => !NOISE.test(p))
check('6 no unexpected console errors/warnings', real.length === 0, `count=${real.length} filtered=${uniq.length - real.length}`)

const passed = results.filter(Boolean).length
console.log(`\nPASS=${passed} FAIL=${results.length - passed}`)
await close()
process.exit(passed === results.length ? 0 : 1)

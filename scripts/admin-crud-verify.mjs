/**
 * 管理后台「分类 / 商品」的 UI CRUD 验证。
 *
 * 为什么单独一个脚本：p2-verify 只测了接口层，web-check 只测渲染，web-flow 只覆盖订单发货 ——
 * 后台最常用的「建分类 → 建商品 → 上下架 → 删除」这条路径此前**没有一次是从界面上点过的**。
 *
 * 覆盖：
 *   1. 分类：新增顶级分类 → 表格出现 → 编辑改名 → 禁用 → 删除
 *   2. 商品：新增（分类树选择 + SKU 行填写）→ 列表出现 → 下架 → 详情页显示下架 → 删除
 *
 * 需要：两个后端 + admin dev server（默认 5173，可用 ADMIN_WEB_URL 覆盖）+ 本机 Chrome
 * 用法：node scripts/admin-crud-verify.mjs
 */
import { writeFileSync, mkdirSync } from 'node:fs'
import { launch } from './cdp.mjs'

const ADMIN_API = process.env.ADMIN_API_URL || 'http://localhost:8081'
const ADMIN_WEB = process.env.ADMIN_WEB_URL || 'http://localhost:5173'
const OUT_DIR = 'D:/projects/mall/tmp/admin-crud'
const STAMP = Date.now().toString().slice(-6)
const CAT_NAME = `UI分类${STAMP}`
const CAT_RENAMED = `UI分类改名${STAMP}`
const PROD_NAME = `UI商品${STAMP}`
const PROD_RENAMED = `UI商品改名${STAMP}`

mkdirSync(OUT_DIR, { recursive: true })
const report = []
const results = []
const log = (s) => {
  console.log(s)
  report.push(s)
}
function check(name, ok, detail = '') {
  results.push(!!ok)
  const line = `[${ok ? 'PASS' : 'FAIL'}] ${name}${detail ? ' :: ' + detail : ''}`
  log(line)
}

const browser = await launch({ port: 9336, profile: 'admin-crud-profile', downloadDir: OUT_DIR })
const { goto, evalJs, innerText, waitFor, clickText, clickSelector, setInput, setStorage, close } = browser

/** 在「某一行」里点按钮（Element Plus 表格行） */
function clickInRow(rowText, buttonText) {
  return evalJs(
    `(() => {
      const row = [...document.querySelectorAll('.el-table__row')].find(r => (r.innerText || '').includes(${JSON.stringify(rowText)}));
      if (!row) return 'no-row';
      const btn = [...row.querySelectorAll('button')].find(b => (b.innerText || '').includes(${JSON.stringify(buttonText)}));
      if (!btn) return 'no-button';
      btn.click();
      return 'ok';
    })()`
  )
}

/** 按序号设置 el-input-number（value + input/change 事件，才能驱动 v-model） */
function setNumberByIndex(index, value) {
  return evalJs(
    `(() => {
      const inputs = [...document.querySelectorAll('input[role="spinbutton"]')];
      const el = inputs[${index}];
      if (!el) return false;
      el.value = ${JSON.stringify(String(value))};
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      // 不要调 el.blur()：el-input-number 的 blur 事件带校验参数，直接触发会报
      // [Vue warn] Invalid event arguments: event validation failed for event "blur"，
      // 而本脚本把控制台告警当失败
      return true;
    })()`
  )
}

/** 确认「表格里还有/没有某段文字」 */
const hasRow = (text) => waitFor(
  `[...document.querySelectorAll('.el-table__row')].some(r => (r.innerText || '').includes(${JSON.stringify(text)}))`,
  6000
)

// --------------------------------------------------------------------------- 准备
log('')
log('--- 准备：管理员登录并注入登录态 ---')
const loginResp = await fetch(`${ADMIN_API}/api/v1/admin/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'malladmin', password: 'admin123456' })
}).then((r) => r.json())
const token = loginResp?.data?.token
check('准备：管理员登录', !!token && loginResp.code === 200, `code=${loginResp?.code} role=${loginResp?.data?.role}`)

await goto(`${ADMIN_WEB}/`, 1500)
await setStorage({ mall_admin_token: token, mall_admin_user: JSON.stringify(loginResp.data) })

// --------------------------------------------------------------------------- 分类 CRUD
log('')
log('--- 1) 分类：新增 / 编辑 / 禁用 / 删除 ---')
await goto(`${ADMIN_WEB}/?r=1#/category`, 3500)
check('1.0 分类管理页渲染', (await innerText()).includes('分类管理'), `hash=${await evalJs('location.hash')}`)

await clickText('.el-button', '新增顶级分类')
const dlgOpen = await waitFor(`!!document.querySelector('.el-dialog')`, 4000)
check('1.1 新增分类弹窗出现', dlgOpen, `dialog=${dlgOpen}`)
await setInput('input[placeholder="分类名称"]', CAT_NAME)
await clickText('.el-dialog .el-button', '保存')
const created = await hasRow(CAT_NAME)
check('1.2 新分类出现在表格里', created, `name=${CAT_NAME}`)

const editClicked = await clickInRow(CAT_NAME, '编辑')
await waitFor(`!!document.querySelector('.el-dialog')`, 4000)
await setInput('input[placeholder="分类名称"]', CAT_RENAMED)
await clickText('.el-dialog .el-button', '保存')
const renamed = await hasRow(CAT_RENAMED)
check('1.3 编辑分类名称生效', editClicked === 'ok' && renamed, `click=${editClicked} newName=${CAT_RENAMED}`)

const rowText = (text) =>
  evalJs(
    `(() => {
      const row = [...document.querySelectorAll('.el-table__row')].find(r => (r.innerText || '').includes(${JSON.stringify(text)}));
      return row ? (row.innerText || '') : '';
    })()`
  )

const toggleClicked = await clickInRow(CAT_RENAMED, '禁用')
await new Promise((r) => setTimeout(r, 1200))
const disabledText = await rowText(CAT_RENAMED)
check('1.4 禁用分类后状态列变化', toggleClicked === 'ok' && /停用|禁用/.test(disabledText), `row="${(disabledText || '').slice(0, 60)}"`)

// 再启用回来：分类树里只列启用的分类，后面建商品要用它
const enableClicked = await clickInRow(CAT_RENAMED, '启用')
await new Promise((r) => setTimeout(r, 1200))
const enabledText = await rowText(CAT_RENAMED)
// 注意：不能只断言「不含禁用」——启用之后操作列那颗按钮的文案就变成「禁用」了，
// 会命中误判。这里精确看状态列：启用态是「正常」，且不再有「停用」。
check(
  '1.5 重新启用分类',
  enableClicked === 'ok' && /正常/.test(enabledText) && !/停用/.test(enabledText),
  `row="${((enabledText || '').replace(/\s+/g, ' ')).slice(0, 80)}"`
)

// --------------------------------------------------------------------------- 商品 CRUD
log('')
log('--- 2) 商品：新增（分类树 + SKU）/ 下架 / 删除 ---')
await goto(`${ADMIN_WEB}/?r=2#/product/new`, 3500)
check('2.0 新增商品页渲染', (await innerText()).includes('商品名称'), `hash=${await evalJs('location.hash')}`)

// 分类树选择：点开下拉再点分类名。
// 注意 EP 2.9 的 el-tree-select 里树节点的标签**没有** `.el-tree-node__label` 类（实测 count=0），
// 文本直接在 `.el-tree-node__content` 上，所以按 content 的 innerText 匹配。
await clickSelector('.el-select__wrapper')
const dropdownOpen = await waitFor(`document.querySelectorAll('.el-tree-node__content').length > 0`, 5000)
const picked = dropdownOpen ? await clickText('.el-tree-node__content', CAT_RENAMED) : false
check('2.1 分类树可以选择', picked === true, `dropdown=${dropdownOpen} picked=${picked}`)

await setInput('input[placeholder="商品名称"]', PROD_NAME)
await setNumberByIndex(0, 88) // 展示价

// 新建页默认**已经有一行 SKU 了**，只有当一行都没有时才去点「添加 SKU」。
// （第一版无条件点了一次，结果多出一个空行，保存时报「第 2 个 SKU 价格不能为空」）
const skuRows = await evalJs(`document.querySelectorAll('input[placeholder="不填按「默认」"]').length`)
if (!skuRows) await clickText('.el-button', '添加 SKU')
const skuRow = await waitFor(`!!document.querySelector('input[placeholder="不填按「默认」"]')`, 4000)
check('2.2 SKU 行就绪', skuRow && skuRows <= 1, `existingSkuRows=${skuRows}`)

await setInput('input[placeholder="不填按「默认」"]', `UI-SPEC-${STAMP}`)
// SKU 行里的「价格 / 库存」是第 1、2 个 spinbutton（第 0 个是展示价）
await setNumberByIndex(1, 66)
await setNumberByIndex(2, 9)

await clickText('.el-button', '保存')
const savedToast = await waitFor(`document.body.innerText.includes('保存成功') || location.hash.startsWith('#/product')`, 8000)
// 失败时把 ElMessage 的校验提示捞出来，否则只看到「没跳转」根本不知道为什么
const toastText = await evalJs(
  `[...document.querySelectorAll('.el-message__content')].map(e => e.innerText).join(' | ')`
)
const backToList = await hasRow(PROD_NAME)
check(
  '2.3 商品保存成功并出现在列表',
  savedToast && backToList,
  `hash=${await evalJs('location.hash')} found=${backToList} toast="${toastText}"`
)

const offClicked = await clickInRow(PROD_NAME, '下架')
await new Promise((r) => setTimeout(r, 1200))
const offRow = await evalJs(
  `(() => {
    const row = [...document.querySelectorAll('.el-table__row')].find(r => (r.innerText || '').includes(${JSON.stringify(PROD_NAME)}));
    return row ? (row.innerText || '') : '';
  })()`
)
check('2.4 列表里下架生效', offClicked === 'ok' && offRow.includes('下架'), `click=${offClicked} row="${(offRow || '').slice(0, 70)}"`)

// 详情页应显示「下架」
const prodId = await evalJs(
  `(() => {
    const row = [...document.querySelectorAll('.el-table__row')].find(r => (r.innerText || '').includes(${JSON.stringify(PROD_NAME)}));
    if (!row) return 0;
    const first = row.querySelector('td');
    return first ? Number((first.innerText || '').trim()) || 0 : 0;
  })()`
)
if (prodId) {
  await goto(`${ADMIN_WEB}/?r=3#/product/${prodId}`, 3000)
  const detailText = await innerText()
  check('2.5 商品详情页显示下架状态', detailText.includes('下架') && detailText.includes(PROD_NAME), `productId=${prodId}`)
} else {
  check('2.5 商品详情页显示下架状态', false, '取不到 productId')
}

// --------------------------------------------------------------------------- 清理
log('')
log('--- 3) 删除（界面操作）---')
await goto(`${ADMIN_WEB}/?r=4#/product`, 3000)
const delClicked = await clickInRow(PROD_NAME, '删除')
const confirmOpen = await waitFor(`!!document.querySelector('.el-message-box__btns')`, 4000)
if (confirmOpen) await clickText('.el-message-box__btns button', '确定')
await new Promise((r) => setTimeout(r, 1500))
const gone = !(await hasRow(PROD_NAME))
check('3.1 删除商品后列表里消失', delClicked === 'ok' && confirmOpen && gone, `click=${delClicked} confirm=${confirmOpen} gone=${gone}`)

await goto(`${ADMIN_WEB}/?r=5#/category`, 3000)
const catDel = await clickInRow(CAT_RENAMED, '删除')
const catConfirm = await waitFor(`!!document.querySelector('.el-message-box__btns')`, 4000)
if (catConfirm) await clickText('.el-message-box__btns button', '确定')
await new Promise((r) => setTimeout(r, 1500))
const catGone = !(await hasRow(CAT_RENAMED))
check('3.2 删除分类后列表里消失', catDel === 'ok' && catConfirm && catGone, `click=${catDel} confirm=${catConfirm} gone=${catGone}`)

// --------------------------------------------------------------------------- 控制台
log('')
const uniq = [...new Set(browser.problems)]
// 已知噪声：这个脚本用合成事件（value + input/change）去填 el-input-number，
// Element Plus 内部的 blur 事件带参数校验，会被 Vue 报一条 emit 校验告警。
// 真实用户点旋钮/键盘输入不会出现它，所以**只在本脚本**按精确模式放行，
// web-check / web-flow 仍然保持零容忍。
const KNOWN_NOISE = /Invalid event arguments: event validation failed for event "blur"/
const realProblems = uniq.filter((p) => !KNOWN_NOISE.test(p))
const noiseCount = uniq.length - realProblems.length
log(`--- 捕获到的控制台告警/错误：${uniq.length} 条（其中 ${noiseCount} 条为合成输入的已知噪声）---`)
for (const p of uniq.slice(0, 20)) log('   ' + p)
check('无真实控制台告警/错误', realProblems.length === 0, `count=${realProblems.length} filteredNoise=${noiseCount}`)

const passed = results.filter(Boolean).length
log('')
log(passed === results.length ? `CRUD 全部通过（${passed}/${results.length}）` : `有失败项（${passed}/${results.length}）`)
writeFileSync(`${OUT_DIR}/report.txt`, report.join('\r\n'), 'utf8')
console.log(`\n报告已写入 ${OUT_DIR}/report.txt`)

await close()
process.exit(passed === results.length ? 0 : 1)

/**
 * 极简 Chrome DevTools Protocol 驱动 —— 被 web-check.mjs / web-flow.mjs 共用。
 *
 * 只做两件事：
 *   1. 起一个无头 Chrome、连上它的 page target，暴露 evaluate / goto / 等待 / 点击 / 填表 这些原语；
 *   2. 全程收集 Vue 运行时告警、console.error/warning、未捕获异常（前端 bug 主要靠这个抓）。
 *
 * 不需要任何 npm 依赖：Node 22+ 自带全局 WebSocket。
 */
import { execFileSync, spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync } from 'node:fs'

const DEFAULT_CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

export async function launch({
  port = 9333,
  profileDir,
  chromePath = DEFAULT_CHROME,
  width = 420,
  height = 900,
  headless = true,
  /** 额外 Chrome 参数（例如自签证书场景的 --ignore-certificate-errors） */
  extraArgs = []
} = {}) {
  if (!existsSync(chromePath)) {
    throw new Error(`找不到 Chrome：${chromePath}`)
  }
  if (profileDir) {
    // 可能被上一次残留的 Chrome 占着，删不掉也别让整个测试挂掉（profile 复用是安全的）
    try {
      rmSync(profileDir, { recursive: true, force: true })
    } catch {
      /* ignore */
    }
    mkdirSync(profileDir, { recursive: true })
  }

  const args = [
    headless ? '--headless=new' : '--new-window',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    `--window-size=${width},${height}`,
    `--remote-debugging-port=${port}`,
    ...extraArgs
  ]
  if (profileDir) args.push(`--user-data-dir=${profileDir}`)
  args.push('about:blank')

  const chrome = spawn(chromePath, args, { stdio: 'ignore' })

  // ---- 等 DevTools 端点起来，拿到 page target
  let target = null
  for (let i = 0; i < 60 && !target; i++) {
    try {
      const list = await fetch(`http://127.0.0.1:${port}/json/list`).then((r) => r.json())
      target = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
    } catch {
      /* 还没起来 */
    }
    if (!target) await sleep(250)
  }
  if (!target) throw new Error('Chrome DevTools 端点没起来')

  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true })
    ws.addEventListener('error', rej, { once: true })
  })

  let msgId = 0
  const pending = new Map()
  const problems = []

  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data)
    if (msg.id !== undefined) {
      pending.get(msg.id)?.(msg)
      pending.delete(msg.id)
      return
    }
    if (msg.method === 'Runtime.consoleAPICalled') {
      const { type, args: a } = msg.params
      if (type === 'error' || type === 'warning') {
        problems.push(`[console.${type}] ${a.map((x) => x.value ?? x.description ?? x.type).join(' ')}`)
      }
    }
    if (msg.method === 'Runtime.exceptionThrown') {
      const d = msg.params.exceptionDetails
      problems.push(`[exception] ${d.exception?.description || d.text}`)
    }
    if (msg.method === 'Log.entryAdded' && ['error', 'warning'].includes(msg.params.entry.level)) {
      const e = msg.params.entry
      problems.push(`[log.${e.level}] ${e.text}${e.url ? ' | url=' + e.url : ''}`)
    }
  })

  const send = (method, params = {}) =>
    new Promise((resolve) => {
      const id = ++msgId
      pending.set(id, resolve)
      ws.send(JSON.stringify({ id, method, params }))
    })

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Log.enable')

  /** 在页面里求值；表达式必须是「一个表达式」（用 IIFE 包起来即可） */
  async function evalJs(expression) {
    const r = await send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true
    })
    if (r.result?.exceptionDetails) {
      // 页面里抛异常时，也希望测试直接看到原因
      throw new Error('页面执行出错: ' + (r.result.exceptionDetails.exception?.description || ''))
    }
    return r.result?.result?.value
  }

  async function goto(url, waitMs = 1500) {
    await send('Page.navigate', { url })
    await sleep(waitMs)
  }

  async function innerText() {
    return ((await evalJs('document.body.innerText')) ?? '').replace(/\s+/g, ' ').trim()
  }

  /** 轮询表达式直到为真；返回是否成功（失败信息由调用方决定怎么报） */
  async function waitFor(expression, timeoutMs = 8000, intervalMs = 200) {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      try {
        if (await evalJs(expression)) return true
      } catch {
        /* 页面正在跳转时求值可能失败，继续等 */
      }
      if (Date.now() > deadline) return false
      await sleep(intervalMs)
    }
  }

  /** 点击「innerText 含 text」的第一个 selector 元素；找不到返回 false */
  function clickText(selector, text) {
    return evalJs(
      `(() => {
        const list = [...document.querySelectorAll(${JSON.stringify(selector)})];
        const el = list.find((e) => (e.innerText || '').includes(${JSON.stringify(text)}));
        if (!el) return false;
        el.click();
        return true;
      })()`
    )
  }

  /** 点击一个具体 selector（取第一个） */
  function clickSelector(selector) {
    return evalJs(
      `(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el) return false; el.click(); return true; })()`
    )
  }

  /** 先找到 innerText 含 rowText 的行，再点该行里 innerText 含 btnText 的元素 */
  function clickIn(rowSelector, rowText, btnSelector, btnText) {
    return evalJs(
      `(() => {
        const rows = [...document.querySelectorAll(${JSON.stringify(rowSelector)})];
        const row = rows.find((r) => (r.innerText || '').includes(${JSON.stringify(rowText)}));
        if (!row) return false;
        const btn = [...row.querySelectorAll(${JSON.stringify(btnSelector)})]
          .find((b) => (b.innerText || '').includes(${JSON.stringify(btnText)}));
        if (!btn) return false;
        btn.click();
        return true;
      })()`
    )
  }

  /** 给 input/textarea 赋值 —— 必须走原生 setter 再派发 input，否则 Vue 的 v-model 收不到 */
  function setInput(selector, value) {
    return evalJs(
      `(() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        if (!el) return false;
        const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(value)});
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        el.dispatchEvent(new Event('blur', { bubbles: true }));
        return true;
      })()`
    )
  }

  /**
   * 往当前站点的 localStorage 写登录态。
   * 注意：hash 路由下写完后必须再整页导航一次（带个 ?seed=N 破缓存），否则 router 守卫读到的还是旧值。
   */
  async function setStorage(entries) {
    const js = Object.entries(entries)
      .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
      .join('')
    return evalJs(`(() => { ${js} return true; })()`)
  }

  async function close() {
    try {
      ws.close()
    } catch {
      /* ignore */
    }
    // Chrome 会派生一堆子进程，只 kill 主进程会残留（实测跑几次后攒了 9 个），
    // 残留进程会占住 profile 目录 / 调试端口，让下一次 launch 随机失败。
    // 注意：这里只对「本次自己起的那个 chrome pid」杀进程树，
    // 和 §8 第 2 条禁止的 `taskkill /IM java.exe` 是两回事——按 PID 精确杀是安全的。
    try {
      if (process.platform === 'win32') {
        execFileSync('taskkill', ['/PID', String(chrome.pid), '/T', '/F'], { stdio: 'ignore' })
      } else {
        chrome.kill('SIGKILL')
      }
    } catch {
      chrome.kill()
    }
  }

  return {
    send,
    evalJs,
    goto,
    innerText,
    waitFor,
    clickText,
    clickSelector,
    clickIn,
    setInput,
    setStorage,
    problems,
    close,
    sleep
  }
}

import { createApp } from 'vue'
import {
  ElAside,
  ElButton,
  ElCard,
  ElConfigProvider,
  ElContainer,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElHeader,
  ElIcon,
  ElImage,
  ElInput,
  ElInputNumber,
  ElLoading,
  ElMain,
  ElMenu,
  ElMenuItem,
  ElOption,
  ElPagination,
  ElRadio,
  ElRadioGroup,
  ElResult,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTreeSelect
} from 'element-plus'
// 样式仍用整份：按组件拆 CSS 要手工维护、漏一个就是「能跑但外观崩」，而 CSS 是独立文件、
// gzip 后只有几十 kB，不值得为此冒险。这次要解决的是 1MB 的 JS 主包。
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { installGlobalErrorHandler } from './utils/errorHandler'
import './styles.css'

const app = createApp(App)

// 按需注册（取代原来的 app.use(ElementPlus)）：没用到的组件会被 Rollup tree-shake 掉。
// 每个组件的 install 会连带注册它的子组件（例如 ElTable 会带上 ElTableColumn），
// 但**指令不算**，v-loading 得单独注册。
;[
  ElAside,
  ElButton,
  ElCard,
  ElConfigProvider,
  ElContainer,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElHeader,
  ElIcon,
  ElImage,
  ElInput,
  ElInputNumber,
  ElMain,
  ElMenu,
  ElMenuItem,
  ElOption,
  ElPagination,
  ElRadio,
  ElRadioGroup,
  ElResult,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTreeSelect,
  // 指令：以前 app.use(ElementPlus) 时代是自动带的
  ElLoading
].forEach((plugin) => app.use(plugin))

// 必须在 mount 之前装：任何组件抛出的未捕获异常都要有兜底，否则整页白屏
installGlobalErrorHandler(app)

app.use(router).mount('#app')

import { createApp } from 'vue'
import {
  ActionBar,
  ActionBarButton,
  ActionBarIcon,
  Button,
  Card,
  Cell,
  CellGroup,
  Checkbox,
  DropdownItem,
  DropdownMenu,
  Empty,
  Field,
  Form,
  Image as VanImage,
  Rate,
  List,
  Loading,
  NavBar,
  Popup,
  PullRefresh,
  Radio,
  RadioGroup,
  Search,
  Stepper,
  SubmitBar,
  Swipe,
  SwipeCell,
  SwipeItem,
  Switch,
  Tab,
  Tabbar,
  TabbarItem,
  Tabs,
  Tag,
  Uploader
} from 'vant'
// 样式仍用整份：按组件拆 CSS 要手工维护、漏一个就是「能跑但外观崩」，而 CSS 是独立文件、
// gzip 后只有几十 kB，不值得为此冒险。这次要解决的是主包 JS 体积。
import 'vant/lib/index.css'
import App from './App.vue'
import router from './router'
import { installGlobalErrorHandler } from './utils/errorHandler'
import './styles.css'

const app = createApp(App)

// 按需注册（取代原来的 app.use(Vant)）：没用到的组件会被 Rollup tree-shake 掉。
// 注意 Vant 导出的 `Image` 与全局 DOM 的 Image 同名，上面用 `Image as VanImage` 避开。
;[
  ActionBar,
  ActionBarButton,
  ActionBarIcon,
  Button,
  Card,
  Cell,
  CellGroup,
  Checkbox,
  DropdownItem,
  DropdownMenu,
  Empty,
  Field,
  Form,
  VanImage,
  Rate,
  List,
  Loading,
  NavBar,
  Popup,
  PullRefresh,
  Radio,
  RadioGroup,
  Search,
  Stepper,
  SubmitBar,
  Swipe,
  SwipeCell,
  SwipeItem,
  Switch,
  Tab,
  Tabbar,
  TabbarItem,
  Tabs,
  Tag,
  Uploader
].forEach((plugin) => app.use(plugin))

// 必须在 mount 之前装：任何组件抛出的未捕获异常都要有兜底，否则整页白屏
installGlobalErrorHandler(app)

app.use(router).mount('#app')

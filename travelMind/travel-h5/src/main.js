/**
 * 前端应用入口文件
 * 职责：创建 Vue 应用实例，注册全局插件（Vant UI 组件库、路由），挂载到 DOM
 */
import { createApp } from 'vue'
// import './style.css'
import App from './App.vue'           // 根组件
import Vant from 'vant'               // Vant 移动端 UI 组件库
import 'vant/lib/index.css'           // Vant 全局样式
import router from './router'         // 路由实例
import './styles/common.css'          // 项目全局公共样式

// 创建 Vue 应用实例
const app = createApp(App)

// 注册 Vant 组件库（全局可用所有 van-* 组件，如 van-button、van-field 等）
app.use(Vant)

// 注册路由插件（提供 <router-view> 出口和 useRouter 等能力）
app.use(router)

// 将应用挂载到 index.html 中 id="app" 的 DOM 节点上
app.mount('#app')

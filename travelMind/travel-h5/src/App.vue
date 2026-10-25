<script setup>
/**
 * 应用根组件
 * 职责：
 * 1. 提供 <router-view> 作为路由页面的渲染出口
 * 2. 在首页/对话/我的三个主页面底部显示底部导航栏（TabBar）
 *    在行程详情页（Detail）不显示底部导航，保持沉浸式阅读体验
 */
import { ref } from 'vue'
import { useRoute } from 'vue-router'

// 当前路由信息，用于判断当前页面是否需要显示底部导航
const route = useRoute()

// 底部导航当前激活项的索引（0=首页, 1=对话, 2=我的）
const active = ref(0)
</script>

<template>
  <div class="app-container">
    <!-- 路由出口：根据当前 URL 渲染对应的页面组件 -->
    <router-view></router-view>

    <!-- 底部导航栏：仅在 Home / Chat / Profile 三个主页面显示 -->
    <!-- route 属性表示点击 tab 时自动进行路由跳转 -->
    <van-tabbar v-if="['Home', 'Chat','Profile'].includes(route.name)" route v-model="active">
      <van-tabbar-item to="/" icon="home-o">首页</van-tabbar-item>
      <van-tabbar-item to="/chat" icon="chat-o">对话</van-tabbar-item>
      <van-tabbar-item to="/profile" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>

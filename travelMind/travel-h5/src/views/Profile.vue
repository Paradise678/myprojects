<template>
  <!--
    我的页面（个人中心）
    目前为静态展示页，"我的收藏/历史记录/设置"均为占位功能（点击提示开发中）。
    后续可接入真实用户系统后扩展。
  -->
  <div class="profile-container">
    <van-nav-bar title="我的" left-text="" left-arrow="false" />

    <!-- 用户信息区域：头像 + 昵称 + 描述 -->
    <div class="user-info">
      <van-image :src="userAvatar" round class="avatar" />
      <div class="user-details">
        <h2 class="user-name">{{ userStore.username || "游客" }}</h2>
        <p class="user-desc">欢迎使用智能旅游助手</p>
      </div>
    </div>

    <!-- 功能菜单：我的服务 -->
    <div class="menu-section">
      <h3 class="menu-title">我的服务</h3>
      <van-cell-group>
        <van-cell
          title="历史行程"
          is-link
          :icon="'history'"
          @click="goHistory"
        />
        <van-cell
          title="设置"
          is-link
          :icon="'settings'"
          @click="showToast('功能开发中')"
        />
      </van-cell-group>
    </div>

    <!-- 关于菜单 -->
    <div class="menu-section">
      <h3 class="menu-title">关于</h3>
      <van-cell-group>
        <van-cell title="关于我们" is-link @click="showAboutDialog" />
        <van-cell title="版本信息" value="v1.0.0" />
      </van-cell-group>
    </div>

    <!-- 退出登录 -->
    <div class="logout-section">
      <van-button round block type="danger" plain @click="handleLogout">
        退出登录
      </van-button>
    </div>

    <!-- 关于我们弹窗 -->
    <van-dialog
      v-model:show="aboutDialogVisible"
      title="关于我们"
      show-cancel-button
    >
      <div class="about-content">
        <p>智能旅游助手 v1.0.0</p>
        <p class="mt-2">基于 AI 技术的智能旅游规划平台</p>
        <p class="mt-2">为您提供个性化的旅游行程推荐和实时旅游咨询服务</p>
        <p class="mt-4 text-center">© 2024 智能旅游助手</p>
      </div>
    </van-dialog>
  </div>
</template>

<script setup>
import { ref } from "vue";
import { useRouter } from "vue-router";
import { showToast, showConfirmDialog } from "vant";
import { useUserStore } from "../store/user";

const router = useRouter();
const userStore = useUserStore();

// 用户头像（默认图）
const userAvatar = "https://img.yzcdn.cn/vant/cat.jpeg";

// 关于我们弹窗的显示状态
const aboutDialogVisible = ref(false);

// 显示关于我们弹窗
const showAboutDialog = () => {
  aboutDialogVisible.value = true;
};

// 跳转历史行程页
const goHistory = () => {
  router.push("/history");
};

/**
 * 退出登录：二次确认后清空登录态（token/username/会话ID），跳转登录页
 */
const handleLogout = async () => {
  try {
    await showConfirmDialog({
      title: "退出登录",
      message: "确定要退出当前账号吗？",
    });
    userStore.logout();
    showToast("已退出登录");
    router.replace("/login");
  } catch (e) {
    // 用户点击取消，不做任何操作
  }
};
</script>

<style scoped>
.profile-container {
  padding-bottom: 50px;
}

.user-info {
  display: flex;
  align-items: center;
  padding: 30px 20px;
  background: linear-gradient(135deg, #1989fa 0%, #36cbcb 100%);
  color: white;
}

.avatar {
  width: 80px;
  height: 80px;
  border: 3px solid rgba(255, 255, 255, 0.3);
}

.user-details {
  margin-left: 20px;
}

.user-name {
  font-size: 20px;
  font-weight: 600;
  margin-bottom: 5px;
}

.user-desc {
  font-size: 14px;
  opacity: 0.9;
}

.menu-section {
  margin-top: 15px;
  background-color: white;
  border-radius: 12px;
  margin: 15px 10px 0;
  overflow: hidden;
}

.menu-title {
  font-size: 14px;
  color: #646566;
  padding: 12px 15px;
  border-bottom: 1px solid #f0f0f0;
}

.logout-section {
  margin: 24px 16px 0;
}

.about-content {
  text-align: center;
  line-height: 1.6;
}

.mt-2 {
  margin-top: 8px;
}

.mt-4 {
  margin-top: 16px;
}

.text-center {
  text-align: center;
}
</style>

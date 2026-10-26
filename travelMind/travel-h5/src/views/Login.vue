<template>
  <!--
      登录 / 注册页
      业务流程：
        1. 默认展示登录表单，点击底部链接可切换为注册模式
        2. 提交后调用后端 /api/auth/login 或 /api/auth/register
        3. 成功后把 token + username 存入 Pinia（localStorage 持久化），跳回来源页或首页
    -->
  <div class="login-container">
    <van-nav-bar :title="isRegister ? '注册' : '登录'" />

    <div class="login-header">
      <h2>{{ isRegister ? "创建账号" : "欢迎回来" }}</h2>
      <p>{{ isRegister ? "注册后即可保存你的行程规划" : "登录智能旅游助手" }}</p>
    </div>

    <van-form @submit="handleSubmit" class="login-form">
      <van-cell-group inset>
        <van-field
          v-model="form.username"
          name="username"
          label="用户名"
          placeholder="请输入用户名"
          :rules="[{ required: true, message: '请输入用户名' }]"
        />
        <van-field
          v-model="form.password"
          type="password"
          name="password"
          label="密码"
          placeholder="请输入密码"
          :rules="[
            { required: true, message: '请输入密码' },
            { validator: validatePassword, message: '密码至少 6 位' },
          ]"
        />
        <!-- 注册模式才显示确认密码 -->
        <van-field
          v-if="isRegister"
          v-model="form.confirmPassword"
          type="password"
          name="confirmPassword"
          label="确认密码"
          placeholder="请再次输入密码"
          :rules="[
            { required: true, message: '请再次输入密码' },
            { validator: validateConfirm, message: '两次输入的密码不一致' },
          ]"
        />
      </van-cell-group>
      <div class="submit-btn">
        <van-button
          round
          block
          type="primary"
          native-type="submit"
          :loading="isLoading"
        >
          {{ isRegister ? "注册并登录" : "登录" }}
        </van-button>
      </div>
    </van-form>

    <!-- 登录 / 注册模式切换 -->
    <div class="switch-mode" @click="isRegister = !isRegister">
      {{ isRegister ? "已有账号？去登录" : "没有账号？去注册" }}
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import { showToast } from "vant";
import { authPost } from "../utils/request";
import { useUserStore } from "../store/user";

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();

// 当前是否为注册模式（false = 登录模式）
const isRegister = ref(false);
// 提交按钮 loading 状态
const isLoading = ref(false);

// 表单数据
const form = reactive({
  username: "",
  password: "",
  confirmPassword: "",
});

// 密码长度校验（注册模式才需要严格校验，登录时不限制格式）
const validatePassword = (val) => !isRegister.value || val.length >= 6;
// 确认密码一致性校验
const validateConfirm = (val) => val === form.password;

/**
 * 提交登录 / 注册
 * 成功后写入登录态，并跳转：优先回 redirect 指定的来源页，否则回首页
 */
const handleSubmit = async () => {
  isLoading.value = true;
  try {
    const url = isRegister.value ? "register" : "login";
    const res = await authPost(url, {
      username: form.username,
      password: form.password,
    });
    // 后端返回 { token, username }（已被响应拦截器解包）
    userStore.login(res.token, res.username);
    showToast(isRegister.value ? "注册成功" : "登录成功");
    router.replace(route.query.redirect || "/");
  } catch (e) {
    // 业务失败（如密码错误、用户名已存在）由响应拦截器 reject，message 在 e.message
    showToast(e.message || "操作失败，请稍后重试");
  } finally {
    isLoading.value = false;
  }
};
</script>

<style scoped>
.login-container {
  min-height: 100vh;
  background: #f7f8fa;
}

.login-header {
  padding: 40px 24px 20px;
}

.login-header h2 {
  margin: 0 0 8px;
  font-size: 24px;
  color: #323233;
}

.login-header p {
  margin: 0;
  font-size: 14px;
  color: #969799;
}

.login-form {
  margin-top: 12px;
}

.submit-btn {
  margin: 24px 16px 0;
}

.switch-mode {
  margin-top: 20px;
  text-align: center;
  font-size: 14px;
  color: #1989fa;
  cursor: pointer;
}
</style>

<template>
  <!--
      历史行程页
      业务流程：
        1. 页面挂载后调用 GET /api/travel/plans（需登录，axios 拦截器自动带 Token）
        2. 后端按当前用户 ID 查询 tm_travel_plan，按创建时间倒序返回
        3. 列表展示城市/天数/预算/创建时间，点击可展开查看完整行程 JSON 详情
    -->
  <div class="page-container">
    <div class="page-header">
      <van-nav-bar
        title="历史行程"
        left-arrow
        left-text="返回"
        fixed
        @click-left="onBack"
      />
    </div>
    <div class="page-content">
      <!-- 加载中 -->
      <div v-if="isLoading" class="loading-container">
        <van-loading size="48px" type="spinner">加载中...</van-loading>
      </div>
      <!-- 空状态：还没有历史行程 -->
      <van-empty v-else-if="plans.length === 0" description="暂无历史行程，去首页规划一次旅行吧">
        <van-button type="primary" @click="goHome">去规划</van-button>
      </van-empty>
      <!-- 行程列表 -->
      <div v-else class="plan-list">
        <div
          v-for="plan in plans"
          :key="plan.id"
          class="card plan-card"
          @click="viewDetail(plan)"
        >
          <div class="plan-header">
            <span class="plan-city">{{ plan.city }}</span>
            <span class="plan-days">{{ plan.days }}天</span>
          </div>
          <div class="plan-meta">
            <span class="plan-budget">预算 {{ plan.totalBudget }}元</span>
            <span class="plan-time">{{ formatTime(plan.createTime) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { showToast } from "vant";
import { get } from "../utils/request";

const router = useRouter();

// 加载状态
const isLoading = ref(true);
// 历史行程列表（后端返回的 TravelPlan 实体数组）
const plans = ref([]);

// 返回上一页
const onBack = () => {
  router.back();
};

// 跳转首页
const goHome = () => {
  router.replace("/");
};

/**
 * 格式化创建时间：把 ISO 时间串裁剪为 YYYY-MM-DD HH:mm
 * @param {string} time - 后端返回的 LocalDateTime 序列化字符串
 */
const formatTime = (time) => {
  if (!time) return "";
  return time.replace("T", " ").substring(0, 16);
};

/**
 * 查看行程详情：把该次规划的完整数据存入 sessionStorage，
 * 跳转到详情页（以 history 模式展示，不重新调用 AI 生成）
 * @param {object} plan - 行程记录（含 planJson）
 */
const viewDetail = (plan) => {
  try {
    sessionStorage.setItem("historyPlan", plan.planJson);
    router.push({
      path: "/detail",
      query: { scene: "history" },
    });
  } catch (e) {
    showToast("行程数据异常，无法查看");
  }
};

// 页面挂载时拉取历史行程列表（401 时拦截器会自动跳登录页）
onMounted(async () => {
  try {
    plans.value = await get("plans");
  } catch (e) {
    showToast(e.message || "加载失败，请稍后重试");
  } finally {
    isLoading.value = false;
  }
});
</script>

<style scoped>
.page-header {
  height: 46px;
}
.loading-container {
  display: flex;
  justify-content: center;
  padding-top: 80px;
}
.plan-list {
  padding-bottom: 16px;
}
.plan-card {
  margin-bottom: 12px;
  cursor: pointer;
}
.plan-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.plan-city {
  font-size: 17px;
  font-weight: 600;
  color: #323233;
}
.plan-days {
  font-size: 14px;
  color: #1989fa;
  background: #ecf5ff;
  padding: 2px 10px;
  border-radius: 10px;
}
.plan-meta {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #969799;
}
</style>

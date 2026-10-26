<template>
  <!--
      行程详情页
      业务流程：
        1. 从首页跳转过来，URL 上携带 city / budget / days 三个 query 参数
        2. 页面挂载后（onMounted）读取参数，调用后端 /recommend 接口
        3. 后端调用大模型生成行程 JSON，返回后渲染到页面
        4. 页面展示：概览（城市+天数+总预算）、每日行程折叠面板、预算明细、温馨提示、注意事项
        5. 底部固定"咨询 AI 助手"按钮，可跳转到对话页继续咨询
    -->
  <div class="page-container">
    <div class="page-header">
      <!-- 顶部导航栏：标题为"城市名 + 行程规划"，左侧返回按钮 -->
      <van-nav-bar
        fixed
        left-text="返回"
        left-arrow
        @click-left="onBack"
        :title="formData.city + '行程规划'"
      />
    </div>
    <div class="page-content">
      <!-- 状态1：加载中（正在调用 AI 生成行程） -->
      <div v-if="isLoading" class="loading-container">
        <van-loading size="48ox" type="spinner">
          正在生成旅游规划...
        </van-loading>
      </div>
      <!-- 状态2：生成失败，显示错误信息 + 重试按钮 -->
      <div v-else-if="errorMsg">
        <van-empty :description="errorMsg">
          <van-button type="primary" @click="fetchTripData">重试</van-button>
        </van-empty>
      </div>
      <!-- 状态3：生成成功，展示完整行程 -->
      <template v-else-if="tripData && tripData.success !== false">
        <!-- 概览卡片：城市、天数、总预算 -->
        <div class="card overview-card">
          <div class="trip-header">
            <h2>{{ tripData.city }} · {{ tripData.days }}天行程</h2>
            <div class="trip-budget">预算：{{ tripData.totalBudget }}元</div>
          </div>
        </div>
        <!-- 每日行程：折叠面板，每天一个折叠项 -->
        <van-collapse v-model="activeDays" class="trip-collapse">
          <van-collapse-item
            v-for="day in tripData.dailyItinerary"
            :key="day.days"
            :title="'第' + day.day + '天'"
            :name="day.days"
          >
            <div class="day-schedule">
              <!-- 上午安排 -->
              <div class="schedule-section">
                <div class="section-label morning">上午</div>
                <SpotItem :data="day.morning" />
              </div>
              <!-- 下午安排 -->
              <div class="schedule-section">
                <div class="section-label afternoon">下午</div>
                <SpotItem :data="day.afternoon" />
              </div>
              <!-- 晚上安排 -->
              <div class="schedule-section">
                <div class="section-label evening">晚上</div>
                <SpotItem :data="day.evening" />
              </div>
            </div>
          </van-collapse-item>
        </van-collapse>
        <!-- 预算明细卡片 -->
        <div class="card budget-card" v-if="tripData.budgetBreakdown">
          <div class="section-title">预算明细</div>
          <BudgetTable
            :data="tripData.budgetBreakdown"
            :total="tripData.totalBudget"
          />
        </div>
        <!-- 温馨提示列表 -->
        <div
          class="card tips-card"
          v-if="tripData.tips && tripData.tips.length"
        >
          <div class="section-title">温馨提示</div>
          <ul class="tips-list">
            <li v-for="(tip, index) in tripData.tips" :key="index">
              {{ tip }}
            </li>
          </ul>
        </div>
        <!-- 注意事项列表 -->
        <div
          class="card warnings-card"
          v-if="tripData.warnings && tripData.warnings.length"
        >
          <div class="section-title">注意事项</div>
          <ul class="warnings-list">
            <li v-for="(warning, index) in tripData.warnings" :key="index">
              {{ warning }}
            </li>
          </ul>
        </div>
      </template>
    </div>
    <!-- 底部固定按钮：跳转 AI 对话页咨询，携带城市作为上下文 -->
    <div class="detail-footer" v-if="tripData && tripData.success !== false">
      <van-button
        type="primary"
        size="large"
        round
        @click="goToChat"
        class="primary-button"
      >
        咨询 AI 助手
      </van-button>
    </div>
  </div>
</template>
<script setup>
import { onMounted, reactive, ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import { post } from "../utils/request";
import SpotItem from "../components/SpotItem.vue";
import BudgetTable from "../components/BudgetTable.vue";

const router = useRouter();
const route = useRoute();

// 加载状态：控制页面显示 loading 还是内容
const isLoading = ref(true);

// 表单数据：从 URL query 读取的城市、预算、天数
const formData = reactive({
  city: "",
  budget: null,
  days: null,
});

// 折叠面板当前展开的天（数组，支持多天同时展开）
const activeDays = ref([]);

// 后端返回的行程数据
const tripData = ref(null);

// 错误信息（生成失败时显示）
const errorMsg = ref("");

// 返回上一页
const onBack = () => {
  router.back();
};

// 跳转到 AI 对话页，并把当前城市带过去作为初始咨询上下文
const goToChat = () => {
  router.push({
    path: "/chat",
    query: {
      scene: "detail",
      city: formData.city,
    },
  });
};

/**
 * 调用后端生成行程规划
 * 请求 POST /recommend，参数为城市、预算、天数
 * 响应拦截器已自动解包，res 即为后端 data 字段里的 TravelRecommendVO；
 * 业务失败（success=false，含限流 429）会被拦截器 reject，走 catch 分支展示错误。
 */
const fetchTripData = async () => {
  try {
    const res = await post("recommend", {
      city: formData.city,
      budget: formData.budget,
      days: formData.days,
    });
    isLoading.value = false;
    tripData.value = res;
  } catch (e) {
    isLoading.value = false;
    // 失败则把后端的错误信息展示出来（含限流提示、AI 生成失败等）
    errorMsg.value = e.message || "行程生成失败，请稍后重试";
  }
};

// 页面挂载时：两种进入方式
// 1. 历史模式（scene=history）：从 sessionStorage 读取已保存的行程 JSON，直接展示，不再调 AI
// 2. 规划模式：从 URL 读取参数，校验完整后立即发起行程生成请求
onMounted(() => {
  if (route.query.scene === "history") {
    const cached = sessionStorage.getItem("historyPlan");
    if (cached) {
      try {
        const plan = JSON.parse(cached);
        formData.city = plan.city;
        formData.budget = plan.totalBudget;
        formData.days = plan.days;
        tripData.value = plan;
      } catch (e) {
        errorMsg.value = "行程数据解析失败";
      }
    } else {
      errorMsg.value = "未找到行程数据";
    }
    isLoading.value = false;
    return;
  }

  formData.city = route.query.city;
  formData.budget = route.query.budget;
  formData.days = route.query.days;

  if (formData.city && formData.budget && formData.days) {
    fetchTripData();
  }
});
</script>

<style scoped>
.page-header {
  height: 46px;
}
.overview-card {
  margin-bottom: 16px;
}

.trip-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.trip-header h2 {
  font-size: 20px;
  color: #323233;
  margin: 0;
}

.trip-budget {
  font-size: 16px;
  color: #ee0a24;
  font-weight: 600;
}

.trip-collapse {
  margin-bottom: 16px;
}

.day-schedule {
  padding: 8px 0;
}

.schedule-section {
  margin-bottom: 16px;
}

.schedule-section:last-child {
  margin-bottom: 0;
}

.section-label {
  font-size: 14px;
  font-weight: 600;
  padding: 4px 8px;
  border-radius: 4px;
  display: inline-block;
  margin-bottom: 8px;
}

.section-label.morning {
  background: #fff7e6;
  color: #fa8c16;
}

.section-label.afternoon {
  background: #e6f7ff;
  color: #1890ff;
}

.section-label.evening {
  background: #f6ffed;
  color: #52c41a;
}

.budget-card,
.tips-card,
.warnings-card {
  margin-bottom: 16px;
}

.tips-list,
.warnings-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.tips-list li,
.warnings-list li {
  padding: 8px 0;
  color: #666;
  font-size: 14px;
  border-bottom: 1px solid #f5f5f5;
}

.tips-list li:last-child,
.warnings-list li:last-child {
  border-bottom: none;
}

.detail-footer {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 12px 16px;
  background: #fff;
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.05);
  max-width: 750px;
  margin: 0 auto;
}

.error-card {
  text-align: center;
  padding: 40px 16px;
}
</style>

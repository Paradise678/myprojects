package org.example.traveljavaserver.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 旅行工具集（LangChain4j Function Calling）
 * 大模型在生成行程规划前，会按需自主调用以下工具获取实时信息：
 *   1. queryWeather      —— 目的地天气（当前为模拟数据，预留真实天气 API 替换点）
 *   2. queryAttractions  —— 目的地热门景点及门票（内置热门城市数据）
 *   3. checkBudget       —— 预算校验（纯逻辑计算，评估预算是否充足并给出分配建议）
 *
 * 每次工具调用都会打印 [Tool] 日志，便于观察多步推理链的执行过程。
 */
@Component
public class TravelTools {

    /** 内置热门城市景点数据：景点名/门票/建议游览时长 */
    private static final Map<String, List<String>> ATTRACTION_DATA = Map.of(
            "北京", List.of(
                    "故宫博物院 | 门票60元 | 建议4小时",
                    "八达岭长城 | 门票40元 | 建议半天",
                    "颐和园 | 门票30元 | 建议3小时",
                    "天坛公园 | 门票15元 | 建议2小时",
                    "南锣鼓巷 | 免费 | 建议2小时"),
            "上海", List.of(
                    "外滩 | 免费 | 建议2小时",
                    "上海迪士尼度假区 | 门票475元起 | 建议1天",
                    "豫园 | 门票40元 | 建议2小时",
                    "东方明珠 | 门票199元起 | 建议3小时",
                    "田子坊 | 免费 | 建议2小时"),
            "杭州", List.of(
                    "西湖 | 免费 | 建议半天",
                    "灵隐寺 | 门票75元（含飞来峰） | 建议3小时",
                    "西溪国家湿地公园 | 门票80元 | 建议3小时",
                    "宋城 | 门票320元（含演出） | 建议半天",
                    "河坊街 | 免费 | 建议2小时"),
            "成都", List.of(
                    "大熊猫繁育研究基地 | 门票55元 | 建议3小时",
                    "武侯祠 | 门票50元 | 建议2小时",
                    "宽窄巷子 | 免费 | 建议2小时",
                    "都江堰 | 门票80元 | 建议3小时",
                    "青城山 | 门票80元 | 建议半天"),
            "西安", List.of(
                    "秦始皇兵马俑博物馆 | 门票120元 | 建议半天",
                    "西安城墙 | 门票54元 | 建议2小时",
                    "大雁塔 | 门票40元 | 建议2小时",
                    "回民街 | 免费 | 建议2小时",
                    "华清宫 | 门票120元 | 建议3小时")
    );

    /**
     * 天气查询工具
     * 当前为确定性模拟数据（按城市名哈希选取天气场景），
     * 生产环境可替换为和风天气/高德等真实天气 API，方法签名保持不变。
     */
    @Tool("查询目的地城市近期天气情况，用于安排行程时考虑天气因素")
    public String queryWeather(@P("目的地城市名称，如：北京、上海、杭州") String city) {
        System.out.println("[Tool] queryWeather 被调用, city=" + city);
        String[] scenes = {
                "以晴到多云为主，气温 18~26℃，适宜户外游览",
                "多云转晴，气温 15~24℃，早晚微凉建议带薄外套",
                "偶有阵雨，气温 20~28℃，建议随身携带雨具",
                "晴天为主，气温 22~30℃，注意防晒补水"
        };
        int idx = Math.abs(city.hashCode()) % scenes.length;
        return city + "近期天气：" + scenes[idx];
    }

    /**
     * 景点查询工具
     * 内置 5 个热门城市数据；未收录城市返回通用提示，引导模型基于自身知识规划。
     */
    @Tool("查询目的地城市的热门景点、门票价格和建议游览时长")
    public String queryAttractions(@P("目的地城市名称，如：北京、上海、杭州") String city) {
        System.out.println("[Tool] queryAttractions 被调用, city=" + city);
        List<String> attractions = ATTRACTION_DATA.get(city);
        if (attractions == null) {
            return "暂未收录" + city + "的景点数据，请基于你的知识推荐该城市的经典景点。";
        }
        return city + "热门景点：\n" + String.join("\n", attractions);
    }

    /**
     * 预算校验工具（纯逻辑计算）
     * 按经验比例评估预算是否充足，并给出各项费用分配建议，
     * 供模型在生成 budgetBreakdown 时参考，避免规划超出用户承受能力。
     */
    @Tool("校验旅行预算是否充足，返回评估结论和费用分配建议")
    public String checkBudget(@P("用户总预算（元）") double totalBudget,
                              @P("旅行天数") int days,
                              @P("预估总花费（元）") double estimatedCost) {
        System.out.println("[Tool] checkBudget 被调用, totalBudget=" + totalBudget
                + ", days=" + days + ", estimatedCost=" + estimatedCost);
        double diff = totalBudget - estimatedCost;
        String verdict;
        if (diff < 0) {
            verdict = "预算超支 " + String.format("%.0f", -diff) + " 元，建议压缩住宿或餐饮标准，或减少收费景点";
        } else if (diff < totalBudget * 0.1) {
            verdict = "预算基本打平，剩余 " + String.format("%.0f", diff) + " 元，注意预留应急资金";
        } else {
            verdict = "预算充足，剩余 " + String.format("%.0f", diff) + " 元可支配";
        }
        // 经验分配比例：住宿30% 餐饮30% 交通20% 门票15% 其他5%
        return "预算评估：" + verdict
                + "。参考分配（按总预算 " + String.format("%.0f", totalBudget) + " 元/" + days + " 天）："
                + "住宿约 " + String.format("%.0f", totalBudget * 0.30) + " 元，"
                + "餐饮约 " + String.format("%.0f", totalBudget * 0.30) + " 元，"
                + "交通约 " + String.format("%.0f", totalBudget * 0.20) + " 元，"
                + "门票约 " + String.format("%.0f", totalBudget * 0.15) + " 元，"
                + "其他约 " + String.format("%.0f", totalBudget * 0.05) + " 元。";
    }
}


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `travelmind` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `travelmind`;
DROP TABLE IF EXISTS `tm_travel_plan`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tm_travel_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `city` varchar(64) NOT NULL,
  `days` int NOT NULL,
  `total_budget` double NOT NULL,
  `plan_json` text NOT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `tm_travel_plan` WRITE;
/*!40000 ALTER TABLE `tm_travel_plan` DISABLE KEYS */;
INSERT INTO `tm_travel_plan` VALUES (1,1,'西安',2,2500,'{\"success\":true,\"city\":\"西安\",\"days\":3,\"totalBudget\":3500.0,\"dailyItinerary\":[{\"day\":1,\"date\":\"第1天\",\"morning\":{\"spot\":\"西安城墙\",\"duration\":\"2小时\",\"transportation\":\"地铁2号线至永宁门站，步行至南门登城\",\"description\":\"西安城墙是中国现存最完整的古代城垣，始建于明洪武年间，全长13.74公里。建议从南门（永宁门）登城，可租借自行车环城骑行，俯瞰古城内外风貌，感受千年古都的雄浑气势。城墙上视野开阔，是拍照打卡的绝佳位置。\",\"ticket\":\"54元\"},\"afternoon\":{\"spot\":\"大雁塔\",\"duration\":\"2小时\",\"transportation\":\"地铁2号线转4号线至大雁塔站\",\"description\":\"大雁塔是唐代高僧玄奘为保存天竺经卷而建造的佛塔，距今已有1300余年历史，是西安的标志性建筑。塔身七层，可登塔远眺曲江新区全景。塔下大慈恩寺内古木参天，梵音袅袅，感受盛唐佛教文化的深厚底蕴。\",\"ticket\":\"40元\"},\"evening\":{\"spot\":\"大唐不夜城\",\"duration\":\"2小时\",\"transportation\":\"步行（大雁塔南广场直通）\",\"description\":\"大唐不夜城是以盛唐文化为主题的步行街，夜晚灯火璀璨，金碧辉煌。街上有不倒翁小姐姐、提线木偶等沉浸式演出，还有各类文创店铺和美食摊位，是体验\'长安夜\'的最佳去处，拍照效果极佳。\",\"ticket\":\"免费\"}},{\"day\":2,\"date\":\"第2天\",\"morning\":{\"spot\":\"秦始皇兵马俑博物馆\",\"duration\":\"4小时\",\"transportation\":\"地铁1号线至纺织城站，转游5/306路公交至兵马俑站\",\"description\":\"秦始皇兵马俑被誉为\'世界第八大奇迹\'，是秦始皇陵的陪葬坑，共出土陶俑陶马近万件。一号坑规模最大，军阵气势恢宏；二号坑兵种丰富；三号坑为军事指挥部。每个陶俑面容各异，栩栩如生，令人叹为观止，是来西安必看的顶级景点。\",\"ticket\":\"120元\"},\"afternoon\":{\"spot\":\"华清宫\",\"duration\":\"3小时\",\"transportation\":\"游5/306路公交至华清池站\",\"description\":\"华清宫是唐代皇家温泉行宫，因唐玄宗与杨贵妃的爱情故事闻名于世。可参观海棠汤（杨贵妃沐浴处）、莲花汤等御汤遗址，远眺骊山风光。景区内还保留有西安事变旧址五间厅，历史厚重感十足。\",\"ticket\":\"120元\"},\"evening\":{\"spot\":\"回民街美食之旅\",\"duration\":\"2小时\",\"transportation\":\"游5/306路返回市区，地铁2号线至钟楼站步行前往\",\"description\":\"回民街是西安最负盛名的美食街区，青石铺路、绿树成荫，浓郁的西北风情扑面而来。可品尝羊肉泡馍、肉夹馍、凉皮、biangbiang面、甑糕等地道西安小吃，还可购买石榴汁、核桃酥等特产，是吃货的天堂。\",\"ticket\":\"免费\"}},{\"day\":3,\"date\":\"第3天\",\"morning\":{\"spot\":\"陕西历史博物馆\",\"duration\":\"3小时\",\"transportation\":\"地铁2号线至小寨站，步行前往\",\"description\":\"陕西历史博物馆被誉为\'古都明珠，华夏宝库\'，馆藏文物37万余件，涵盖从远古到近代的完整历史脉络。镇馆之宝包括兽首玛瑙杯、鎏金舞马衔杯银壶等珍贵文物，是了解中华文明演进的最佳窗口。建议提前在官网预约免费门票。\",\"ticket\":\"免费（基础陈列）/30元（珍宝馆）\"},\"afternoon\":{\"spot\":\"钟鼓楼广场\",\"duration\":\"2小时\",\"transportation\":\"地铁2号线至钟楼站\",\"description\":\"钟楼和鼓楼是西安的地标性建筑，位于城市正中心。钟楼建于明洪武十七年，是中国现存最大的钟楼；鼓楼与钟楼遥相呼应，古时用于晨钟暮鼓报时。广场周边商业繁华，可登楼俯瞰东西南北四条大街交汇的壮观景象。\",\"ticket\":\"钟鼓楼联票30元\"},\"evening\":{\"spot\":\"永兴坊告别晚餐\",\"duration\":\"2小时\",\"transportation\":\"地铁1号线至朝阳门站步行前往\",\"description\":\"永兴坊是唐代魏征府邸旧址上建设的非遗美食街区，比回民街更加精致。可品尝摔碗酒、子长煎饼、安康蒸面等陕西各地特色美食，还有皮影戏、华阴老腔等非遗表演，为三天的西安之行画上圆满句号。\",\"ticket\":\"免费\"}}],\"budgetBreakdown\":{\"accommodation\":1050.0,\"food\":1050.0,\"transportation\":700.0,\"tickets\":394.0,\"other\":306.0},\"tips\":[\"西安近期偶有阵雨，气温20~28℃，建议随身携带雨具和防晒用品\",\"陕西历史博物馆免费门票需提前在官网或公众号预约，旺季名额紧张建议提前3天预约\",\"兵马俑景区较大，建议请景区官方讲解员（约100元），否则容易走马观花\",\"回民街和永兴坊美食众多，建议少量多样品尝，留肚子多尝几种\",\"西安地铁和公交支持支付宝/微信扫码乘车，出行便利\",\"城墙骑行建议选傍晚时分，避开正午烈日，夕阳下拍照更美\"],\"warnings\":[\"兵马俑景区外有大量\'野导游\'和仿制纪念品摊贩，请选择官方讲解和正规商店\",\"回民街部分商家价格偏高且分量有限，建议先问价再点餐\",\"华清宫后山骊山索道为自费项目，如需登顶需额外付费\",\"钟鼓楼登楼排队时间较长，如时间紧张可在广场拍照即可\"],\"error\":null,\"rawResponse\":null}','2026-10-25 21:06:21'),(3,2,'杭州',2,3000,'{\"success\":true,\"city\":\"杭州\",\"days\":3,\"totalBudget\":3000.0,\"dailyItinerary\":[{\"day\":1,\"date\":\"第1天\",\"morning\":{\"spot\":\"西湖\",\"duration\":\"3小时\",\"transportation\":\"地铁1号线至龙翔桥站，步行前往\",\"description\":\"西湖是杭州的标志性景点，世界文化遗产。建议从断桥残雪出发，沿白堤步行至孤山，途经平湖秋月、曲院风荷等经典景观，感受苏东坡笔下\'欲把西湖比西子\'的绝美意境。可乘船游览三潭印月，湖光山色尽收眼底。\",\"ticket\":\"免费\"},\"afternoon\":{\"spot\":\"灵隐寺\",\"duration\":\"3小时\",\"transportation\":\"公交7路至灵隐寺站\",\"description\":\"灵隐寺是江南著名古刹，始建于东晋，距今已有千年历史。寺内有大雄宝殿、天王殿等宏伟建筑，还可游览飞来峰石窟造像，欣赏精美的佛教石刻艺术，感受禅意幽深的佛教文化。\",\"ticket\":\"75元（含飞来峰）\"},\"evening\":{\"spot\":\"河坊街夜游\",\"duration\":\"2小时\",\"transportation\":\"公交7路转地铁1号线至定安路站\",\"description\":\"河坊街是杭州历史文化街区，夜晚灯火辉煌，可品尝杭州特色小吃如西湖醋鱼、龙井虾仁、叫花鸡等，还可购买丝绸、茶叶等伴手礼，感受老杭州的市井烟火气。\",\"ticket\":\"免费\"}},{\"day\":2,\"date\":\"第2天\",\"morning\":{\"spot\":\"西溪国家湿地公园\",\"duration\":\"3小时\",\"transportation\":\"地铁5号线至西溪湿地北站\",\"description\":\"西溪湿地是国家5A级景区，以\'曲水弯环、群山四绕\'闻名。建议乘坐摇橹船穿行于水道间，观赏芦苇荡、柿基鱼塘等湿地生态景观，体验\'西溪且留下\'的诗意栖居。\",\"ticket\":\"80元\"},\"afternoon\":{\"spot\":\"宋城\",\"duration\":\"4小时\",\"transportation\":\"公交356路至宋城站\",\"description\":\"宋城是以宋代文化为主题的大型景区，再现了古都临安的繁华盛景。园区内有王员外家小姐抛绣球、穿越快闪秀等互动表演，最精彩的是大型歌舞《宋城千古情》，震撼视听，被誉为\'一生必看的演出\'。\",\"ticket\":\"320元（含演出）\"},\"evening\":{\"spot\":\"西湖音乐喷泉\",\"duration\":\"1小时\",\"transportation\":\"公交4路至湖滨站\",\"description\":\"湖滨步行街附近的西湖音乐喷泉是夜间热门打卡点，水柱随音乐律动起舞，灯光与湖水交相辉映，绚烂壮观。观赏后可漫步湖滨路，享受杭州的夜生活氛围。\",\"ticket\":\"免费\"}},{\"day\":3,\"date\":\"第3天\",\"morning\":{\"spot\":\"雷峰塔\",\"duration\":\"2小时\",\"transportation\":\"公交52路至净慈寺站\",\"description\":\"雷峰塔因《白蛇传》传说闻名天下，登塔可俯瞰西湖全景和杭州城市风貌。塔内展示了地宫出土文物和精美木雕，了解雷峰塔千年兴废的历史变迁，感受\'雷峰夕照\'的壮美意境。\",\"ticket\":\"40元\"},\"afternoon\":{\"spot\":\"龙井茶园\",\"duration\":\"3小时\",\"transportation\":\"公交27路至龙井茶室站\",\"description\":\"前往龙井村品茶赏景，漫步茶园梯田，了解中国十大名茶之首——西湖龙井的采摘与炒制工艺。可在茶农家品一杯正宗龙井，远眺茶山叠翠，体验杭州独特的茶文化，购买正宗龙井茶作为伴手礼。\",\"ticket\":\"免费\"},\"evening\":{\"spot\":\"武林广场购物休闲\",\"duration\":\"2小时\",\"transportation\":\"公交27路转地铁1号线至武林广场站\",\"description\":\"武林广场是杭州核心商圈，周边有杭州大厦、银泰百货等大型商场。可在此购买丝绸、杭扇等杭州特产，享用告别晚餐，为三天的杭州之行画上圆满句号。\",\"ticket\":\"免费\"}}],\"budgetBreakdown\":{\"accommodation\":900.0,\"food\":900.0,\"transportation\":600.0,\"tickets\":515.0,\"other\":85.0},\"tips\":[\"杭州近期天气多云转晴，气温15~24℃，早晚微凉建议携带薄外套\",\"西湖景区面积较大，建议租借公共自行车或乘坐景区电瓶车游览，节省体力\",\"宋城千古情演出场次有限，建议提前在官网预约场次\",\"龙井村购茶建议货比三家，选择有正规标识的茶叶\",\"杭州地铁和公交支持支付宝扫码乘车，出行非常便利\"],\"warnings\":[\"西湖景区节假日人流量极大，建议早出发避开高峰\",\"西溪湿地部分区域水位较高，注意安全不要靠近深水区\",\"河坊街部分商家价格偏高，购买特产时请谨慎比价\",\"雷峰塔电梯排队时间较长，建议走楼梯登塔以节省时间\"],\"error\":null,\"rawResponse\":null}','2026-10-25 21:19:55');
/*!40000 ALTER TABLE `tm_travel_plan` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `tm_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tm_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(32) NOT NULL,
  `password` varchar(100) NOT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `tm_user` WRITE;
/*!40000 ALTER TABLE `tm_user` DISABLE KEYS */;
INSERT INTO `tm_user` VALUES (1,'tester','$2a$10$3LfQ0nElX1x0nlxBdceA.OQoWNPHoIg.qSIltZrssn8.2Q/7ymGe.','2026-10-25 21:04:57'),(2,'p5user','$2a$10$lOapvVPsiZc.xqzgTfBU5enbeoaO9NQn0/468o7/3LSK1asqSGMVK','2026-10-25 21:17:58'),(3,'webtest01','$2a$10$opqwPRLroNCU9ZEqONH3Cu9TkzajVGye.s1L60ZHvocn2SegW.iBq','2026-10-25 21:29:11');
/*!40000 ALTER TABLE `tm_user` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


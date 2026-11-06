# 篮界 · 篮球馆预约系统 v2

篮球馆（4 片全场）订场 + 教练 + 拼场 + 会员 + **AI 前台**的手机端系统，AI 全栈求职作品集。

- 产品说明与冻结基线：`docs/product-brief.md`
- 接口清单：`docs/api.md`
- 原型（视觉与交互基准）：ZCode 可视化目录 `lanjie-demo-v3.html`

## 技术栈

Spring Boot 3.5 · MyBatis-Plus · MySQL 8 · Redis · JWT · Vue 3 + Vite + TypeScript（手机端优先）

## 怎么跑起来

### 依赖

本机 MySQL 8（3306）+ Redis（6379）；有 Docker 也可以 `docker compose up -d` 起这两个。

1. 建库并执行表结构：

   ```bash
   mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS lanjie DEFAULT CHARSET utf8mb4"
   mysql -uroot -p lanjie < sql/schema.sql
   ```

2. 数据库账号密码写进 `backend/src/main/resources/application-dev.yml`（或用环境变量 `DB_USER` / `DB_PASSWORD`）

3. 起后端（8082）：

   ```bash
   cd backend && mvn spring-boot:run
   ```

4. 起手机端（5179，`host: true` 支持局域网真机预览）：

   ```bash
   cd frontend-mobile && npm install && npm run dev
   ```

5. 手机或浏览器打开 `http://localhost:5179`；演示环境验证码固定 **123456**（见 `application.yml` 的 `lanjie.sms.dev-fixed-code`）。
6. 店长：`13800000000 / admin123`；前台值班：`13800000001 / front123`（退款、改价、财务、审计需店长）；教练账号：`13900000001 / coach123`（依此类推）。
7. AI：默认 mock（离线/CI）；接真模型设 `AI_PROVIDER=glm` 与 `GLM_API_KEY`。

## 已实现（W1–W3）

- 注册登录双方式：密码 + 短信验证码（验证码登录自动注册）
- JWT 30 分钟 + refresh token 7 天轮换（哈希落库，旧 token 立即作废）、登出撤销
- 验证码：60 秒限频、5 次错误锁 15 分钟、只存哈希
- 手机端壳：登录/注册/首页/我的 + 底部导航，401 自动续期重试
- v2 核心表 14 张（用户/会话/验证码/场馆/营业时间/节假日/场地/价格规则/格子/订单/明细/支付单/状态历史/幂等键）
- 场地时段：价格规则（平日/周末/节假日 × 高低峰）、格子 30 天滚动生成（唯一键幂等）、价格快照不追溯
- 订场表：真格子 + 连选（同场连续 ≤4 格）+ 底部结算条
- 锁位 15 分钟：Redis 快速失败 + DB 条件更新兜底，双窗抢同一场只有一个成功
- 支付：模拟微信网关（生产换验签回调，状态机一致），回调按支付单状态幂等，迟到支付不会落在已取消订单上
- 订单：状态机留痕、待支付超时自动取消（扫库兜底双保险）、日限 2 单、未支付可取消释放
- 售后（W4）：储值钱包（充值赠送/余额支付/流水对账）、退款原路阶梯（≥24h 全退 / 2–24h 退 70% / <2h 不退）、改期多退少补（开场前 2h、限 1 次）、爽约封禁（3 次/7 天禁订 7 天）、一次性核销码（时间窗 + ADMIN 权限）
- 幂等键：写接口防重复提交，重放返回首次响应
- 教练域/团课（W5–W6）：教练档期滚动生成、私教双资源锁、课包 95/90/85 折、教练端改档期/请假/课表/分成、团课 4 人开班 8 人上限候补递补自动取消
- 拼场（W7）：发起付全场、份子钱平台托管、开赛结算、取消全退
- 会员营销（W8）：四档会员充值升级、折扣与券择优、积分抵扣与完成发放、全退返券返积分
- AI 前台（W9–W10）：mock/GLM 双模型（`AI_PROVIDER`/`GLM_API_KEY`）、12 工具白名单、锁位需用户确认凭证、规则库引用、会话/工具/工单落库、SSE、40 条评测门禁（注入 100% 拦截）
- 单元 + 集成测试 64 个（含 AI 评测门禁、6 线程并发抢场、越权隔离、E2E 旅程），CI 见 `.github/workflows/ci.yml`

## 约定

- 金额一律整数分（`*_cents`）；时间存本地 Asia/Shanghai
- 日志带 `X-Trace-Id`，响应头同步带出
- Swagger：`http://localhost:8082/swagger-ui.html`

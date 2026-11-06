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

## 已实现（W1）

- 注册登录双方式：密码 + 短信验证码（验证码登录自动注册）
- JWT 30 分钟 + refresh token 7 天轮换（哈希落库，旧 token 立即作废）、登出撤销
- 验证码：60 秒限频、5 次错误锁 15 分钟、只存哈希
- 手机端壳：登录/注册/首页/我的 + 底部导航，401 自动续期重试
- v2 核心表 9 张（用户/会话/验证码/场馆/营业时间/节假日/场地/价格规则/格子）
- 单元测试（JWT/验证码/认证服务）+ 注册登录全链路集成测试；CI 见 `.github/workflows/ci.yml`

## 约定

- 金额一律整数分（`*_cents`）；时间存本地 Asia/Shanghai
- 日志带 `X-Trace-Id`，响应头同步带出
- Swagger：`http://localhost:8082/swagger-ui.html`

# 篮界 v2 API 清单

> 共 68 个接口，按主线分 6 组；`状态` 列标记当前进度（W1 只交付认证组）。
> 通用约定：响应统一 `Result{code, message, data}`，`code=0` 成功；
> 需登录的接口带 `Authorization: Bearer <accessToken>`；写接口建议带 `Idempotency-Key` 头（防重复提交）。

## 第 1 组 认证 + 我（8）——状态：✅ W1 已实现

| 接口 | 说明 |
|---|---|
| POST `/api/v1/auth/sms-code` | 发验证码（scene：REGISTER/LOGIN/RESET_PASSWORD），60s 限频 |
| POST `/api/v1/auth/register` | 注册；手机号+验证码必填，密码可选 |
| POST `/api/v1/auth/login` | 登录；grantType=PASSWORD/SMS，验证码登录自动注册 |
| POST `/api/v1/auth/refresh` | 刷新；refresh token 轮换，旧的立即作废 |
| POST `/api/v1/auth/logout` | 登出；撤销 refresh token |
| GET `/api/v1/me` | 我的信息（会员档/积分后续补入） |
| PATCH `/api/v1/me` | 改昵称/头像 |
| GET `/api/v1/me/overview` | 我的页聚合（W3/W4 随钱包订单补齐） |

## 第 2 组 场地时段 + 订场（11）

> 进度：11 个全部 ✅（时段 4 个 W2；锁位/支付/预览/列表/详情 W3；改期与已支付订单取消退款 W4）。
> 核销相关：`GET /bookings/{id}/checkin-token`（用户）与 `POST /admin/bookings/{id}/checkin`（前台）✅ W4，见第 6 组。

| 接口 | 说明 |
|---|---|
| GET `/api/v1/venue` | 场馆信息 |
| GET `/api/v1/courts` | 场地列表 |
| GET `/api/v1/slots?date=&courtId=` | 某日某场格子（状态+价格快照） |
| GET `/api/v1/slots/calendar?from=&to=` | 一周日历 |
| POST `/api/v1/bookings/holds` | 锁位 15 分钟；校验日限 2 单/爽约封禁 |
| POST `/api/v1/bookings/{id}/checkout-preview` | 下单确认：折扣券选优 + 积分抵扣后的应付明细 |
| POST `/api/v1/bookings/{id}/pay` | 支付（钱包/模拟微信），回调按流水号幂等 |
| POST `/api/v1/bookings/{id}/cancel` | 取消；退款额服务端按阶梯计算 |
| POST `/api/v1/bookings/{id}/reschedule` | 改期；先锁新格补差价再释放旧格 |
| GET `/api/v1/bookings?status=` | 订单列表（详情含当前可退金额、核销 token） |
| GET `/api/v1/bookings/{id}` | 订单详情 |

## 第 3 组 钱包 / 会员 / 通知（10）

> 进度：钱包 4 个 ✅ W4（余额/流水/充值档/充值）；会员/券/课包 W8，通知 W8。

| 接口 | 说明 |
|---|---|
| GET `/api/v1/wallet` | 余额 |
| GET `/api/v1/wallet/transactions` | 流水（带余额快照） |
| GET `/api/v1/recharge-plans` | 充值档位 |
| POST `/api/v1/wallet/recharge` | 充值（模拟微信） |
| GET `/api/v1/me/member` | 会员档 + 积分账户 |
| GET `/api/v1/me/points/transactions` | 积分流水 |
| GET `/api/v1/me/coupons` | 我的券 |
| GET `/api/v1/me/packages` | 我的课包 |
| GET `/api/v1/notifications` | 站内通知 |
| POST `/api/v1/notifications/read` | 标已读 |

## 第 4 组 教练 / 团课（13）——排期 W5/W6

| 接口 | 说明 |
|---|---|
| GET `/api/v1/coaches` | 教练列表 |
| GET `/api/v1/coaches/{id}` | 教练详情+评价 |
| GET `/api/v1/coaches/{id}/slots?date=` | 教练档期 |
| POST `/api/v1/coach-bookings/holds` | 锁私教（教练格+可选场地格原子锁） |
| POST `/api/v1/coach-packages` | 购买 5/10/20 课包 |
| GET `/api/v1/group-classes?date=` | 团课列表 |
| POST `/api/v1/group-classes/{id}/enroll` | 报名（满员转候补） |
| DELETE `/api/v1/group-classes/{id}/enroll` | 取消报名/放弃候补 |
| GET `/api/v1/coach/me/schedule` | 教练端：每周规则 |
| PUT `/api/v1/coach/me/schedule` | 教练端：改规则（只影响未售格子） |
| POST `/api/v1/coach/me/time-offs` | 教练端：请假（关格子+列受影响单） |
| DELETE `/api/v1/coach/me/time-offs/{id}` | 教练端：取消请假 |
| GET `/api/v1/coach/me/lessons` | 教练端：课表 + 分成 |

## 第 5 组 拼场 / AI（9）——排期 W7/W9

| 接口 | 说明 |
|---|---|
| GET `/api/v1/pickup-games?date=&format=` | 拼场大厅 |
| POST `/api/v1/pickup-games` | 发起（先付全场） |
| GET `/api/v1/pickup-games/{id}` | 详情+参与者 |
| POST `/api/v1/pickup-games/{id}/join` | 加入（份子钱托管） |
| DELETE `/api/v1/pickup-games/{id}/join` | 退出 |
| POST `/api/v1/ai/sessions` | 开会话 |
| POST `/api/v1/ai/sessions/{id}/messages` | 发消息（SSE 流式，工具步骤可见） |
| POST `/api/v1/ai/messages/{id}/feedback` | 点赞/点踩 |
| POST `/api/v1/ai/sessions/{id}/handoff` | 转人工 |

## 第 6 组 管理端（17）

> 进度：扫码核销 ✅ W4；其余 W10/W11。

| 接口 | 说明 |
|---|---|
| GET `/api/v1/admin/dashboard` | 今日看板 |
| GET `/api/v1/admin/bookings` | 订单列表 |
| POST `/api/v1/admin/bookings/{id}/checkin` | 扫码核销 |
| POST `/api/v1/admin/bookings/{id}/force-cancel` | 强制取消（全退+审计） |
| GET `/api/v1/admin/refunds` | 退款记录 |
| POST `/api/v1/admin/refunds/{id}/retry` | 退款重试 |
| GET `/api/v1/admin/users` | 用户列表 |
| POST `/api/v1/admin/users/{id}/ban` | 封禁/解封 |
| GET `/api/v1/admin/price-rules` | 价格规则 |
| PUT `/api/v1/admin/price-rules` | 改价（只影响未生成格子） |
| POST `/api/v1/admin/slots/close` | 闭馆（先列受影响订单确认） |
| POST `/api/v1/admin/slots/generate` | 批量生成格子 |
| POST `/api/v1/admin/coaches` | 教练入驻/编辑 |
| POST `/api/v1/admin/coupons/grant` | 发券 |
| GET `/api/v1/admin/ai/handoffs` | 转人工工单 |
| POST `/api/v1/admin/ai/handoffs/{id}/resolve` | 处理工单 |
| GET `/api/v1/admin/audit-logs` | 审计日志 |

## 错误码分段

| 段 | 含义 |
|---|---|
| 0 | 成功 |
| 400xx | 参数/通用 |
| 401xx | 认证与会话 |
| 403xx | 权限 |
| 404xx | 订单与资源 |
| 409xx | 并发冲突 |
| 50000 | 系统错误 |

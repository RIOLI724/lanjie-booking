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

## 第 6 组 管理端（当前已实现 32）

> 进度：核销 / 看板 / 排期 / 订单处置 / 教练排班请假 / 团课 / 退款 / 用户 / AI 运营均已实现；批量价格规则、审计日志查询、财务流水属 P1 后续。

| 接口 | 说明 |
|---|---|
| GET `/api/v1/admin/today` | 前台工作台：待核销、AI 转人工、待退款、今日摘要 |
| GET `/api/v1/admin/schedule?date=` | 排期网格（4 片场 × 时段） |
| GET `/api/v1/admin/orders?date=&status=` | 当天场地订单列表 |
| GET `/api/v1/admin/orders/{id}` | 订单详情：明细、状态流水、支付/退款记录 |
| POST `/api/v1/admin/orders/{id}/force-cancel` | 强制取消：全额退款 + 审计 |
| POST `/api/v1/admin/slots/{slotId}/close` | 单格关场；占用中先返回受影响订单，`force=true` 全退并取消 |
| POST `/api/v1/admin/slots/{slotId}/reopen` | 重新开放已关时段 |
| PATCH `/api/v1/admin/slots/{slotId}/price` | 单格改价（仅可订状态） |
| POST `/api/v1/admin/bookings/{id}/checkin` | 扫码核销 |
| GET `/api/v1/admin/refunds` | 退款记录 |
| GET `/api/v1/admin/users` | 用户列表 |
| POST `/api/v1/admin/users/{id}/ban` | 封禁/解封 |
| GET `/api/v1/admin/customer?phone=` | 客户 360：订单一览、余额、课包 |
| GET/POST `/api/v1/admin/coaches` | 教练列表 / 新增入驻（开账号、默认全天可约） |
| PUT `/api/v1/admin/coaches/{id}` | 编辑资料、课时价、抽成、在岗/停用 |
| GET/PUT `/api/v1/admin/coaches/{id}/schedule` | 周排班查询 / 整组替换并重建未来档期 |
| POST `/api/v1/admin/coaches/{id}/time-off` | 代请假；已售课程先返回受影响清单，`force=true` 全退 |
| DELETE `/api/v1/admin/coaches/{id}/time-offs/{offId}` | 撤销请假并重开时段 |
| GET `/api/v1/admin/coaches/{id}/slots?date=` | 某天可约档期（团课排班用） |
| GET `/api/v1/admin/coaches/{id}/services` | 教练服务（私教/团课） |
| GET `/api/v1/admin/group-classes?date=` | 团课列表（含已取消/已完成） |
| GET `/api/v1/admin/group-classes/{id}` | 团课详情 + 报名/候补名单 |
| POST `/api/v1/admin/group-classes` | 创建团课（占用教练档期与场地） |
| POST `/api/v1/admin/group-classes/{id}/cancel` | 取消整班：全员全退、候补取消、释放资源 |
| POST `/api/v1/admin/coupons/grant` | 发券 |
| GET `/api/v1/admin/ai/summary` | AI 质量摘要 |
| GET `/api/v1/admin/ai/handoffs` | 转人工工单 |
| GET `/api/v1/admin/ai/handoffs/{id}/detail` | 工单对话详情 |
| POST `/api/v1/admin/ai/handoffs/{id}/resolve` | 处理工单 |
| GET/POST `/api/v1/admin/ai/evals` | 评测历史 / 重跑评测 |

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

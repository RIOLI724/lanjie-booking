-- =====================================================================
-- 篮界·篮球馆预约系统 v2 核心表
-- 执行方式（本机）：mysql -u<user> -p lanjie < sql/schema.sql
-- 约定：金额一律整数分（*_cents）；时间存本地时间 Asia/Shanghai
-- =====================================================================

-- 用户 ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id`                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `phone`                     VARCHAR(20)     NOT NULL COMMENT '手机号',
    `password_hash`             VARCHAR(100)    NULL COMMENT 'BCrypt 哈希；验证码登录用户可为空',
    `nickname`                  VARCHAR(50)     NOT NULL DEFAULT '' COMMENT '昵称',
    `avatar_url`                VARCHAR(255)    NULL COMMENT '头像',
    `role`                      VARCHAR(20)     NOT NULL DEFAULT 'CUSTOMER' COMMENT 'CUSTOMER/COACH/ADMIN',
    `status`                    VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/FROZEN',
    `member_level_id`           BIGINT UNSIGNED NULL COMMENT '会员等级，会员模块启用',
    `cumulative_recharge_cents` BIGINT          NOT NULL DEFAULT 0 COMMENT '累计充值（分，只算本金）',
    `created_at`                DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_phone` (`phone`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户';

-- 登录会话（refresh token 轮换）-------------------------------------------
CREATE TABLE IF NOT EXISTS `user_session`
(
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`            BIGINT UNSIGNED NOT NULL,
    `refresh_token_hash` VARCHAR(64)     NOT NULL COMMENT 'refresh token 的 SHA-256，明文不落库',
    `device`             VARCHAR(100)    NULL COMMENT '设备标识',
    `expires_at`         DATETIME        NOT NULL,
    `revoked_at`         DATETIME        NULL COMMENT '轮换或登出时撤销',
    `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_token` (`refresh_token_hash`),
    KEY `idx_session_user` (`user_id`, `expires_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '登录会话';

-- 短信验证码 --------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sms_code`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `phone`      VARCHAR(20)     NOT NULL,
    `scene`      VARCHAR(20)     NOT NULL COMMENT 'REGISTER/LOGIN/RESET_PASSWORD',
    `code_hash`  VARCHAR(64)     NOT NULL COMMENT '验证码 SHA-256',
    `attempts`   INT             NOT NULL DEFAULT 0 COMMENT '校验失败次数，5 次锁定',
    `expires_at` DATETIME        NOT NULL COMMENT '5 分钟有效',
    `used_at`    DATETIME        NULL,
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_sms_phone_scene` (`phone`, `scene`, `used_at`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '短信验证码';

-- 场馆 ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `venue`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `name`       VARCHAR(100)    NOT NULL,
    `address`    VARCHAR(255)    NOT NULL DEFAULT '',
    `phone`      VARCHAR(20)     NOT NULL DEFAULT '',
    `open_time`  TIME            NOT NULL DEFAULT '09:00:00',
    `close_time` TIME            NOT NULL DEFAULT '22:00:00',
    `timezone`   VARCHAR(40)     NOT NULL DEFAULT 'Asia/Shanghai',
    `status`     VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '场馆';

-- 每周营业时间 -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `venue_hours`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `venue_id`   BIGINT UNSIGNED NOT NULL,
    `weekday`    TINYINT         NOT NULL COMMENT '1=周一 … 7=周日',
    `open_time`  TIME            NOT NULL,
    `close_time` TIME            NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_hours_venue_weekday` (`venue_id`, `weekday`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '每周营业时间';

-- 节假日 / 调休 ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `holiday`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `holiday_date` DATE           NOT NULL,
    `date_type`   VARCHAR(20)     NOT NULL COMMENT 'HOLIDAY=休 / WORKDAY=班',
    `name`        VARCHAR(50)     NOT NULL DEFAULT '',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_holiday_date` (`holiday_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '节假日/调休';

-- 场地 ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `court`
(
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `venue_id`         BIGINT UNSIGNED NOT NULL,
    `name`             VARCHAR(50)     NOT NULL COMMENT '如 1 号场',
    `type`             VARCHAR(20)     NOT NULL DEFAULT 'FULL' COMMENT 'FULL/HALF',
    `parent_id`        BIGINT UNSIGNED NULL COMMENT '半场所属整场，整场为 NULL',
    `base_price_cents` INT             NOT NULL DEFAULT 0 COMMENT '基础价（分/小时）',
    `status`           VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/MAINTENANCE/DISABLED',
    `sort_order`       INT             NOT NULL DEFAULT 0,
    `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_court_venue` (`venue_id`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '场地';

-- 价格规则 ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `price_rule`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `court_id`    BIGINT UNSIGNED NULL COMMENT 'NULL=全馆默认',
    `day_type`    VARCHAR(20)     NOT NULL COMMENT 'WEEKDAY/WEEKEND/HOLIDAY',
    `start_time`  TIME            NOT NULL,
    `end_time`    TIME            NOT NULL,
    `price_cents` INT             NOT NULL COMMENT '价格（分/小时）',
    `priority`    INT             NOT NULL DEFAULT 0 COMMENT '命中多条时取大值',
    `status`      VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_rule_court_day` (`court_id`, `day_type`, `priority`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '价格规则';

-- 可售时段（格子）----------------------------------------------------------
CREATE TABLE IF NOT EXISTS `slot`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `venue_id`        BIGINT UNSIGNED NOT NULL,
    `court_id`        BIGINT UNSIGNED NOT NULL,
    `slot_date`       DATE            NOT NULL,
    `start_at`        DATETIME        NOT NULL COMMENT '开始（含）',
    `end_at`          DATETIME        NOT NULL COMMENT '结束（不含）',
    `price_cents`     INT             NOT NULL COMMENT '生成时的价格快照',
    `status`          VARCHAR(20)     NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/HELD/BOOKED/CLOSED',
    `hold_token`      VARCHAR(64)     NULL COMMENT '锁位凭证',
    `hold_expires_at` DATETIME        NULL,
    `owner_type`      VARCHAR(20)     NULL COMMENT 'BOOKING/GROUP_CLASS',
    `owner_id`        BIGINT UNSIGNED NULL,
    `version`         INT             NOT NULL DEFAULT 0 COMMENT '乐观锁',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_slot_court_start` (`court_id`, `start_at`),
    KEY `idx_slot_date` (`slot_date`, `court_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '可售时段（1 小时格子）';

-- 订单（所有消费类型的聚合：订场/私教/团课/拼场份子钱）-----------------------
CREATE TABLE IF NOT EXISTS `booking`
(
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `booking_no`       VARCHAR(32)     NOT NULL COMMENT '业务单号 BJ+日期+随机',
    `user_id`          BIGINT UNSIGNED NOT NULL,
    `venue_id`         BIGINT UNSIGNED NOT NULL,
    `type`             VARCHAR(20)     NOT NULL DEFAULT 'COURT' COMMENT 'COURT/COACH/GROUP/PICKUP',
    `status`           VARCHAR(20)     NOT NULL DEFAULT 'PENDING_PAYMENT'
                                       COMMENT 'PENDING_PAYMENT/CONFIRMED/COMPLETED/CANCELLED/NO_SHOW',
    `list_amount_cents` INT            NOT NULL DEFAULT 0 COMMENT '原价合计',
    `discount_cents`   INT             NOT NULL DEFAULT 0 COMMENT '优惠合计（W8 启用）',
    `payable_cents`    INT             NOT NULL DEFAULT 0 COMMENT '应付',
    `paid_cents`       INT             NOT NULL DEFAULT 0 COMMENT '实付',
    `contact_name`     VARCHAR(50)     NULL,
    `contact_phone`    VARCHAR(20)     NULL,
    `hold_expires_at`  DATETIME        NULL COMMENT '锁位过期时间（15 分钟）',
    `checked_in_at`    DATETIME        NULL COMMENT '核销时间（W4）',
    `cancel_reason`    VARCHAR(100)    NULL,
    `reschedule_count` INT             NOT NULL DEFAULT 0,
    `package_id`       BIGINT UNSIGNED NULL COMMENT '课包核销的订单指向所消耗的课包',
    `checkin_token`    VARCHAR(64)     NULL COMMENT '一次性核销码，核销成功立即作废',
    `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_booking_no` (`booking_no`),
    KEY `idx_booking_user` (`user_id`, `created_at`),
    KEY `idx_booking_status_hold` (`status`, `hold_expires_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单主表';

-- 订单明细（一个订单可含多个格子/教练课/团课席位）----------------------------
CREATE TABLE IF NOT EXISTS `booking_item`
(
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `booking_id`         BIGINT UNSIGNED NOT NULL,
    `item_type`          VARCHAR(20)     NOT NULL COMMENT 'COURT_SLOT/COACH_SESSION/GROUP_SEAT/PICKUP_SHARE',
    `ref_id`             BIGINT UNSIGNED NOT NULL COMMENT 'slot_id / coach_slot_id / class_id / game_id',
    `title`              VARCHAR(100)    NOT NULL COMMENT '展示标题，如 1号场 11-08 19:00-20:00',
    `start_at`           DATETIME        NOT NULL,
    `end_at`             DATETIME        NOT NULL,
    `unit_price_cents`   INT             NOT NULL DEFAULT 0,
    `amount_cents`       INT             NOT NULL DEFAULT 0,
    `refund_amount_cents` INT            NOT NULL DEFAULT 0 COMMENT '已退金额（W4）',
    `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_item_booking` (`booking_id`),
    KEY `idx_item_ref` (`item_type`, `ref_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单明细';

-- 支付单（一次支付一条；改期差价可再开一条）----------------------------------
CREATE TABLE IF NOT EXISTS `pay_order`
(
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `pay_no`             VARCHAR(32)     NOT NULL COMMENT '支付单号 PY+…',
    `booking_id`         BIGINT UNSIGNED NULL,
    `user_id`            BIGINT UNSIGNED NOT NULL,
    `purpose`            VARCHAR(20)     NOT NULL DEFAULT 'BOOKING' COMMENT 'BOOKING/RECHARGE/RESCHEDULE_DIFF',
    `biz_ref`            BIGINT UNSIGNED NULL COMMENT '业务引用：BOOKING/RESCHEDULE_DIFF→booking_id，RECHARGE→plan_id',
    `channel`            VARCHAR(20)     NOT NULL COMMENT 'WALLET/WECHAT_MOCK',
    `amount_cents`       INT             NOT NULL,
    `status`             VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PAID/CLOSED/REFUNDED',
    `third_party_txn_id` VARCHAR(64)     NULL COMMENT '第三方流水号（幂等锚点）',
    `paid_at`            DATETIME        NULL,
    `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pay_no` (`pay_no`),
    KEY `idx_pay_booking` (`booking_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '支付单';

-- 订单状态流转留痕 -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS `booking_status_history`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `booking_id`  BIGINT UNSIGNED NOT NULL,
    `from_status` VARCHAR(20)     NULL,
    `to_status`   VARCHAR(20)     NOT NULL,
    `actor`       VARCHAR(30)     NOT NULL DEFAULT 'USER' COMMENT 'USER/ADMIN/SYSTEM/AI',
    `reason`      VARCHAR(100)    NULL,
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_bsh_booking` (`booking_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单状态历史';

-- 幂等键（写接口防重复提交/重放）---------------------------------------------
CREATE TABLE IF NOT EXISTS `idempotency_key`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `idem_key`      VARCHAR(64)     NOT NULL,
    `user_id`       BIGINT UNSIGNED NOT NULL,
    `endpoint`      VARCHAR(100)    NOT NULL,
    `request_hash`  CHAR(64)        NOT NULL COMMENT '请求体 SHA-256，内容不同则拒绝',
    `response_json` TEXT            NULL COMMENT '首次成功的响应，重放直接返回',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idem` (`idem_key`, `user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '幂等键';

-- 储值钱包 -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wallet_account`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT UNSIGNED NOT NULL,
    `balance_cents` BIGINT          NOT NULL DEFAULT 0 COMMENT '余额（含充值赠送，不退现）',
    `version`       INT             NOT NULL DEFAULT 0,
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wallet_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '储值钱包';

-- 钱包流水（唯一键兜底幂等，带余额快照可对账）--------------------------------
CREATE TABLE IF NOT EXISTS `wallet_txn`
(
    `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`             BIGINT UNSIGNED NOT NULL,
    `type`                VARCHAR(30)     NOT NULL COMMENT 'RECHARGE/RECHARGE_BONUS/PAY/REFUND/ADJUST',
    `amount_cents`        BIGINT          NOT NULL COMMENT '正入负出',
    `balance_after_cents` BIGINT          NOT NULL COMMENT '余额快照',
    `biz_type`            VARCHAR(30)     NOT NULL COMMENT 'PAY_ORDER/REFUND_ORDER',
    `biz_id`              BIGINT UNSIGNED NOT NULL,
    `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_txn_biz` (`user_id`, `type`, `biz_type`, `biz_id`),
    KEY `idx_txn_user` (`user_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '钱包流水';

-- 退款单（原路：钱包退钱包，微信退微信）--------------------------------------
CREATE TABLE IF NOT EXISTS `refund_order`
(
    `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `refund_no`            VARCHAR(32)     NOT NULL,
    `pay_order_id`         BIGINT UNSIGNED NULL COMMENT '原支付单',
    `booking_id`           BIGINT UNSIGNED NULL,
    `user_id`              BIGINT UNSIGNED NOT NULL,
    `amount_cents`         INT             NOT NULL,
    `reason`               VARCHAR(100)    NULL,
    `channel`              VARCHAR(20)     NOT NULL COMMENT '与原支付同路',
    `status`               VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
    `third_party_refund_id` VARCHAR(64)    NULL,
    `created_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `completed_at`         DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_refund_no` (`refund_no`),
    KEY `idx_refund_booking` (`booking_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '退款单';

-- 充值档位 -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `recharge_plan`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `amount_cents` INT            NOT NULL,
    `bonus_cents` INT             NOT NULL DEFAULT 0,
    `title`       VARCHAR(50)     NOT NULL DEFAULT '',
    `status`      VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    `sort`        INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '充值档位';

-- 爽约记录（7 天内 3 次触发禁订）----------------------------------------------
CREATE TABLE IF NOT EXISTS `no_show_record`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT UNSIGNED NOT NULL,
    `booking_id` BIGINT UNSIGNED NOT NULL,
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_no_show_booking` (`booking_id`),
    KEY `idx_no_show_user` (`user_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '爽约记录';

-- 封禁（爽约触发，禁订 7 天）--------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_ban`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT UNSIGNED NOT NULL,
    `reason`     VARCHAR(100)    NOT NULL,
    `start_at`   DATETIME        NOT NULL,
    `end_at`     DATETIME        NOT NULL,
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ban_user` (`user_id`, `end_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户封禁';

-- 教练 -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `coach`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '教练登录账号',
    `display_name`    VARCHAR(50)     NOT NULL,
    `avatar_url`      VARCHAR(255)    NULL,
    `bio`             VARCHAR(255)    NOT NULL DEFAULT '',
    `specialties`     VARCHAR(100)    NOT NULL DEFAULT '' COMMENT '专项标签',
    `rating`          DECIMAL(2, 1)   NOT NULL DEFAULT 5.0,
    `rating_count`    INT             NOT NULL DEFAULT 0,
    `base_price_cents` INT            NOT NULL COMMENT '私教单节价（分）',
    `commission_rate` DECIMAL(4, 3)   NOT NULL DEFAULT 0.200 COMMENT '平台抽成比例',
    `status`          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    `sort_order`      INT             NOT NULL DEFAULT 0,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_coach_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教练';

-- 教练每周可约规则 -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `coach_schedule_rule`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `coach_id`   BIGINT UNSIGNED NOT NULL,
    `weekday`    TINYINT         NOT NULL COMMENT '1=周一 … 7=周日',
    `start_time` TIME            NOT NULL,
    `end_time`   TIME            NOT NULL,
    `status`     VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (`id`),
    KEY `idx_rule_coach` (`coach_id`, `weekday`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教练每周可约规则';

-- 教练档期（1 小时一格，真正被锁/被卖）----------------------------------------
CREATE TABLE IF NOT EXISTS `coach_slot`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `coach_id`        BIGINT UNSIGNED NOT NULL,
    `start_at`        DATETIME        NOT NULL,
    `end_at`          DATETIME        NOT NULL,
    `status`          VARCHAR(20)     NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/HELD/BOOKED/CLOSED',
    `hold_token`      VARCHAR(64)     NULL,
    `hold_expires_at` DATETIME        NULL,
    `owner_type`      VARCHAR(20)     NULL COMMENT 'BOOKING/GROUP_CLASS',
    `owner_id`        BIGINT UNSIGNED NULL,
    `version`         INT             NOT NULL DEFAULT 0,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_coach_slot_start` (`coach_id`, `start_at`),
    KEY `idx_coach_slot_date` (`coach_id`, `start_at`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教练档期';

-- 教练请假（关联时段关闭）------------------------------------------------------
CREATE TABLE IF NOT EXISTS `coach_time_off`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `coach_id`   BIGINT UNSIGNED NOT NULL,
    `start_at`   DATETIME        NOT NULL,
    `end_at`     DATETIME        NOT NULL,
    `reason`     VARCHAR(100)    NOT NULL DEFAULT '',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_timeoff_coach` (`coach_id`, `start_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教练请假';

-- 教练服务商品（私教/团课）-----------------------------------------------------
CREATE TABLE IF NOT EXISTS `coach_service`
(
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `coach_id`       BIGINT UNSIGNED NOT NULL,
    `type`           VARCHAR(20)     NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE/GROUP',
    `title`          VARCHAR(50)     NOT NULL,
    `duration_min`   INT             NOT NULL DEFAULT 60,
    `price_cents`    INT             NOT NULL COMMENT '单节价（含场地时已含）',
    `includes_court` TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否含场地',
    `min_students`   INT             NOT NULL DEFAULT 1,
    `max_students`   INT             NOT NULL DEFAULT 1,
    `status`         VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_service_coach` (`coach_id`, `type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教练服务商品';

-- 课包（5/10/20 节，95/90/85 折，12 个月有效）---------------------------------
CREATE TABLE IF NOT EXISTS `coach_package`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`           BIGINT UNSIGNED NOT NULL,
    `coach_id`          BIGINT UNSIGNED NOT NULL,
    `service_id`        BIGINT UNSIGNED NOT NULL,
    `total_sessions`    INT             NOT NULL,
    `used_sessions`     INT             NOT NULL DEFAULT 0,
    `remaining_sessions` INT            NOT NULL,
    `price_cents`       INT             NOT NULL,
    `pay_order_id`      BIGINT UNSIGNED NULL,
    `expires_at`        DATETIME        NOT NULL COMMENT '购买日起 12 个月',
    `status`            VARCHAR(20)     NOT NULL DEFAULT 'PENDING_PAYMENT'
                                        COMMENT 'PENDING_PAYMENT/ACTIVE/EXHAUSTED/EXPIRED/REFUNDED',
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_pkg_user` (`user_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教练课包';

-- 团课场次（4 人开班、上限 8 人）-----------------------------------------------
CREATE TABLE IF NOT EXISTS `group_class`
(
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `coach_id`       BIGINT UNSIGNED NOT NULL,
    `service_id`     BIGINT UNSIGNED NOT NULL,
    `coach_slot_id`  BIGINT UNSIGNED NULL COMMENT '教练档期（owner_type=GROUP_CLASS 占用）',
    `court_slot_id`  BIGINT UNSIGNED NULL COMMENT '场地格（含场地时占用）',
    `title`          VARCHAR(100)    NOT NULL,
    `start_at`       DATETIME        NOT NULL,
    `end_at`         DATETIME        NOT NULL,
    `min_students`   INT             NOT NULL DEFAULT 4,
    `max_students`   INT             NOT NULL DEFAULT 8,
    `price_cents`    INT             NOT NULL COMMENT '人均价',
    `enrolled_count` INT             NOT NULL DEFAULT 0,
    `status`         VARCHAR(20)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/FULL/CANCELLED/COMPLETED',
    `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_class_coach` (`coach_id`, `start_at`),
    KEY `idx_class_status` (`status`, `start_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '团课场次';

-- 团课报名/候补 ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `group_enrollment`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `class_id`      BIGINT UNSIGNED NOT NULL,
    `user_id`       BIGINT UNSIGNED NOT NULL,
    `booking_id`    BIGINT UNSIGNED NULL COMMENT '确认席位的订单',
    `status`        VARCHAR(20)     NOT NULL DEFAULT 'CONFIRMED' COMMENT 'CONFIRMED/WAITLIST/CANCELLED',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_enroll_class_user` (`class_id`, `user_id`),
    KEY `idx_enroll_class` (`class_id`, `status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '团课报名';

-- 拼场（发起人付全场，份子钱托管，开赛结算）------------------------------------
CREATE TABLE IF NOT EXISTS `pickup_game`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `initiator_id`    BIGINT UNSIGNED NOT NULL,
    `booking_id`      BIGINT UNSIGNED NOT NULL COMMENT '场地的订单（发起人付全场）',
    `slot_id`         BIGINT UNSIGNED NOT NULL,
    `format`          VARCHAR(20)     NOT NULL COMMENT '3v3/4v4/5v5',
    `total_players`   INT             NOT NULL,
    `joined_players`  INT             NOT NULL DEFAULT 1,
    `price_per_person_cents` INT      NOT NULL,
    `note`            VARCHAR(100)    NOT NULL DEFAULT '',
    `status`          VARCHAR(20)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/FULL/CANCELLED/COMPLETED',
    `settled`         TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '份子钱是否已结算给发起人',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_game_status` (`status`, `created_at`),
    KEY `idx_game_slot` (`slot_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '拼场';

-- 拼场参与者（份子钱订单挂在 booking type=PICKUP）------------------------------
CREATE TABLE IF NOT EXISTS `pickup_participant`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `game_id`    BIGINT UNSIGNED NOT NULL,
    `user_id`    BIGINT UNSIGNED NOT NULL,
    `booking_id` BIGINT UNSIGNED NULL COMMENT '份子钱订单',
    `status`     VARCHAR(20)     NOT NULL DEFAULT 'PENDING_PAYMENT'
                                 COMMENT 'PENDING_PAYMENT/CONFIRMED/WAITLIST/CANCELLED',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_participant_game_user` (`game_id`, `user_id`),
    KEY `idx_participant_game` (`game_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '拼场参与者';

-- 会员等级 ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `member_level`
(
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `code`           VARCHAR(20)     NOT NULL,
    `name`           VARCHAR(20)     NOT NULL,
    `threshold_cents` BIGINT         NOT NULL COMMENT '累计充值门槛',
    `discount_pct`   INT             NOT NULL DEFAULT 100 COMMENT '折扣百分位，95 = 95 折',
    `points_rate`    DECIMAL(3, 1)   NOT NULL DEFAULT 1.0 COMMENT '积分倍率',
    `sort`           INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '会员等级';

-- 优惠券模板 / 用户券 -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS `coupon_template`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `name`              VARCHAR(50)     NOT NULL,
    `type`              VARCHAR(20)     NOT NULL DEFAULT 'FIXED' COMMENT 'FIXED/PERCENT',
    `value_cents`       INT             NOT NULL DEFAULT 0 COMMENT '满减面额',
    `percent`           INT             NOT NULL DEFAULT 100 COMMENT '折扣百分位（PERCENT 用）',
    `max_discount_cents` INT            NOT NULL DEFAULT 0 COMMENT '折扣上限（PERCENT 用，0=不限）',
    `min_amount_cents`  INT             NOT NULL DEFAULT 0 COMMENT '门槛（按原价）',
    `scope`             VARCHAR(20)     NOT NULL DEFAULT 'COURT' COMMENT 'ALL/COURT/COACH/GROUP/PICKUP',
    `valid_from`        DATETIME        NULL,
    `valid_to`          DATETIME        NULL,
    `total_quantity`    INT             NOT NULL DEFAULT 0 COMMENT '0=不限',
    `issued_quantity`   INT             NOT NULL DEFAULT 0,
    `per_user_limit`    INT             NOT NULL DEFAULT 1,
    `status`            VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '优惠券模板';

CREATE TABLE IF NOT EXISTS `user_coupon`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT UNSIGNED NOT NULL,
    `template_id` BIGINT UNSIGNED NOT NULL,
    `status`      VARCHAR(20)     NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/USED/EXPIRED',
    `received_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `used_at`     DATETIME        NULL,
    `booking_id`  BIGINT UNSIGNED NULL,
    `expires_at`  DATETIME        NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_coupon_user` (`user_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户优惠券';

-- 积分账户 / 流水 ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `points_account`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`      BIGINT UNSIGNED NOT NULL,
    `balance`      INT             NOT NULL DEFAULT 0,
    `total_earned` INT             NOT NULL DEFAULT 0,
    `total_spent`  INT             NOT NULL DEFAULT 0,
    `version`      INT             NOT NULL DEFAULT 0,
    `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_points_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '积分账户';

CREATE TABLE IF NOT EXISTS `points_transaction`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT UNSIGNED NOT NULL,
    `type`          VARCHAR(20)     NOT NULL COMMENT 'EARN/SPEND/REFUND/ADJUST',
    `points`        INT             NOT NULL COMMENT '正得负耗',
    `balance_after` INT             NOT NULL,
    `biz_type`      VARCHAR(30)     NOT NULL,
    `biz_id`        BIGINT UNSIGNED NOT NULL,
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_points_txn` (`user_id`, `type`, `biz_type`, `biz_id`),
    KEY `idx_points_user` (`user_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '积分流水';

-- AI 会话 / 消息 / 工具调用 / 转人工 / 反馈 ------------------------------------
CREATE TABLE IF NOT EXISTS `ai_session`
(
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`        BIGINT UNSIGNED NOT NULL,
    `status`         VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CLOSED/HANDOFF',
    `model`          VARCHAR(40)     NOT NULL DEFAULT 'mock',
    `prompt_version` VARCHAR(20)     NOT NULL DEFAULT 'v1',
    `started_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `ended_at`       DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_ai_session_user` (`user_id`, `started_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 会话';

CREATE TABLE IF NOT EXISTS `ai_message`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `session_id` BIGINT UNSIGNED NOT NULL,
    `role`       VARCHAR(20)     NOT NULL COMMENT 'USER/ASSISTANT/TOOL/SYSTEM',
    `content`    TEXT            NOT NULL,
    `ui_json`    TEXT            NULL COMMENT '结构化卡片（方案/锁位/订单）',
    `tokens_in`  INT             NOT NULL DEFAULT 0,
    `tokens_out` INT             NOT NULL DEFAULT 0,
    `latency_ms` INT             NOT NULL DEFAULT 0,
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ai_msg_session` (`session_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 消息';

CREATE TABLE IF NOT EXISTS `ai_tool_call`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `session_id`  BIGINT UNSIGNED NOT NULL,
    `message_id`  BIGINT UNSIGNED NULL,
    `tool_name`   VARCHAR(40)     NOT NULL,
    `args_json`   TEXT            NULL,
    `result_json` TEXT            NULL,
    `status`      VARCHAR(20)     NOT NULL DEFAULT 'OK' COMMENT 'OK/ERROR/DENIED',
    `error`       VARCHAR(200)    NULL,
    `latency_ms`  INT             NOT NULL DEFAULT 0,
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ai_tool_session` (`session_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 工具调用';

CREATE TABLE IF NOT EXISTS `ai_handoff`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `session_id`  BIGINT UNSIGNED NOT NULL,
    `user_id`     BIGINT UNSIGNED NOT NULL,
    `reason`      VARCHAR(30)     NOT NULL COMMENT 'USER_REQUEST/PAYMENT/COMPLAINT/OUT_OF_SCOPE/REPEATED_FAILURE',
    `summary`     VARCHAR(255)    NOT NULL DEFAULT '',
    `status`      VARCHAR(20)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED',
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `resolved_at` DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_handoff_status` (`status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '转人工工单';

-- 规则库（RAG 源）---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `knowledge_doc`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `title`      VARCHAR(100)    NOT NULL,
    `category`   VARCHAR(30)     NOT NULL COMMENT 'REFUND/RESCHEDULE/NOSHOW/PACKAGE/GROUP/PICKUP/VENUE/AI',
    `content`    TEXT            NOT NULL,
    `version`    INT             NOT NULL DEFAULT 1,
    `status`     VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '规则库文档';

CREATE TABLE IF NOT EXISTS `knowledge_chunk`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `doc_id`     BIGINT UNSIGNED NOT NULL,
    `seq`        INT             NOT NULL DEFAULT 0,
    `content`    VARCHAR(500)    NOT NULL,
    `keywords`   VARCHAR(200)    NOT NULL DEFAULT '' COMMENT '命中关键词，逗号分隔',
    PRIMARY KEY (`id`),
    KEY `idx_chunk_doc` (`doc_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '规则库切块';

-- AI 评测（金标集 / 运行 / 结果）------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_eval_case`
(
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `name`               VARCHAR(60)     NOT NULL,
    `category`           VARCHAR(30)     NOT NULL COMMENT 'BOOKING/COACH/POLICY/HANDOFF/INJECTION/LANGUAGE',
    `input`              VARCHAR(300)    NOT NULL,
    `expected_tools`     VARCHAR(200)    NOT NULL DEFAULT '' COMMENT '逗号分隔的必含工具',
    `expected_contains`  VARCHAR(200)    NOT NULL DEFAULT '' COMMENT '答案必须包含（| 分隔任一）',
    `expected_handoff`   TINYINT(1)      NOT NULL DEFAULT 0,
    `enabled`            TINYINT(1)      NOT NULL DEFAULT 1,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 评测金标集';

CREATE TABLE IF NOT EXISTS `ai_eval_run`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `model`       VARCHAR(40)     NOT NULL,
    `prompt_version` VARCHAR(20)  NOT NULL,
    `total`       INT             NOT NULL DEFAULT 0,
    `passed`      INT             NOT NULL DEFAULT 0,
    `failed`      INT             NOT NULL DEFAULT 0,
    `cost_cents`  INT             NOT NULL DEFAULT 0,
    `report_json` TEXT            NULL,
    `started_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at` DATETIME        NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 评测运行';

CREATE TABLE IF NOT EXISTS `ai_eval_result`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `run_id`      BIGINT UNSIGNED NOT NULL,
    `case_id`     BIGINT UNSIGNED NOT NULL,
    `passed`      TINYINT(1)      NOT NULL,
    `actual_tools` VARCHAR(200)   NOT NULL DEFAULT '',
    `actual_answer` VARCHAR(500)  NOT NULL DEFAULT '',
    `latency_ms`  INT             NOT NULL DEFAULT 0,
    `error`       VARCHAR(200)    NULL,
    PRIMARY KEY (`id`),
    KEY `idx_eval_result_run` (`run_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 评测结果';

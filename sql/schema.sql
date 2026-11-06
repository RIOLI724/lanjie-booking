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

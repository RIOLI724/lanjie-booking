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

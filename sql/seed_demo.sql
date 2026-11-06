-- 演示数据（交付演示用，非测试数据；请在干净库上执行一次）
-- 用法：mysql -uroot -p lanjie < sql/seed_demo.sql

-- 演示顾客（密码统一 123456，可直接密码登录；也可用验证码 123456）
INSERT INTO user (phone, nickname, role, status, cumulative_recharge_cents, password_hash)
VALUES ('13800001001', '小林', 'CUSTOMER', 'ACTIVE', 0,
        '$2a$10$LzUbktyZrQ/yDiHRymviMu6Ir4PoWCkr.tHW9wUxGhXkJQxh39.u2');
SET @u1 = LAST_INSERT_ID();
INSERT INTO user (phone, nickname, role, status, cumulative_recharge_cents, password_hash)
VALUES ('13800001002', '阿杰', 'CUSTOMER', 'ACTIVE', 0,
        '$2a$10$LzUbktyZrQ/yDiHRymviMu6Ir4PoWCkr.tHW9wUxGhXkJQxh39.u2');
SET @u2 = LAST_INSERT_ID();
INSERT INTO user (phone, nickname, role, status, cumulative_recharge_cents, password_hash)
VALUES ('13800001003', '小周', 'CUSTOMER', 'ACTIVE', 0,
        '$2a$10$LzUbktyZrQ/yDiHRymviMu6Ir4PoWCkr.tHW9wUxGhXkJQxh39.u2');
SET @u3 = LAST_INSERT_ID();

INSERT INTO wallet_account (user_id, balance_cents, version)
VALUES (@u1, 24000, 0), (@u2, 0, 0), (@u3, 12000, 0);

-- 今天三个格子（只取还没开场的，避免演示时出现“过去时段仍待核销”）
SET @s1 = (SELECT id FROM slot WHERE slot_date = CURDATE() AND status = 'AVAILABLE'
           AND start_at >= NOW() ORDER BY start_at LIMIT 0, 1);
SET @s2 = (SELECT id FROM slot WHERE slot_date = CURDATE() AND status = 'AVAILABLE'
           AND start_at >= NOW() ORDER BY start_at LIMIT 1, 1);
SET @s3 = (SELECT id FROM slot WHERE slot_date = CURDATE() AND status = 'AVAILABLE'
           AND start_at >= NOW() ORDER BY start_at LIMIT 2, 1);
SET @s4 = (SELECT id FROM slot WHERE slot_date = CURDATE() AND HOUR(start_at) = 19
           AND status = 'AVAILABLE' AND start_at >= NOW() ORDER BY id LIMIT 1);
SET @s5 = (SELECT id FROM slot WHERE slot_date = CURDATE() AND HOUR(start_at) = 20
           AND status = 'AVAILABLE' AND start_at >= NOW() ORDER BY id LIMIT 1);
SET @s4 = COALESCE(@s4, (SELECT id FROM slot WHERE slot_date = CURDATE() AND status = 'AVAILABLE'
                         AND start_at >= NOW() AND id NOT IN (@s1, @s2, @s3) ORDER BY start_at LIMIT 1));
SET @s5 = COALESCE(@s5, (SELECT id FROM slot WHERE slot_date = CURDATE() AND status = 'AVAILABLE'
                         AND start_at >= NOW() AND id NOT IN (@s1, @s2, @s3, @s4) ORDER BY start_at LIMIT 1));

-- 小林：已支付，到店核销
INSERT INTO booking (booking_no, user_id, venue_id, type, status, list_amount_cents, discount_cents,
                     payable_cents, paid_cents, created_at)
SELECT CONCAT('DEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '1'), @u1, s.venue_id, 'COURT', 'CONFIRMED',
       s.price_cents, 0, s.price_cents, s.price_cents, NOW()
FROM slot s WHERE s.id = @s1;
SET @b1 = LAST_INSERT_ID();
INSERT INTO booking_item (booking_id, item_type, ref_id, title, start_at, end_at, unit_price_cents, amount_cents)
SELECT @b1, 'COURT_SLOT', s.id,
       CONCAT(c.name, ' ', DATE_FORMAT(s.start_at, '%m-%d %H:%i'), '-', DATE_FORMAT(s.end_at, '%H:%i')),
       s.start_at, s.end_at, s.price_cents, s.price_cents
FROM slot s JOIN court c ON c.id = s.court_id WHERE s.id = @s1;
UPDATE slot SET status = 'BOOKED', owner_type = 'BOOKING', owner_id = @b1, version = version + 1 WHERE id = @s1;

-- 阿杰：已支付，未到场（待核销）
INSERT INTO booking (booking_no, user_id, venue_id, type, status, list_amount_cents, discount_cents,
                     payable_cents, paid_cents, created_at)
SELECT CONCAT('DEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '2'), @u2, s.venue_id, 'COURT', 'CONFIRMED',
       s.price_cents, 0, s.price_cents, s.price_cents, NOW()
FROM slot s WHERE s.id = @s2;
SET @b2 = LAST_INSERT_ID();
INSERT INTO booking_item (booking_id, item_type, ref_id, title, start_at, end_at, unit_price_cents, amount_cents)
SELECT @b2, 'COURT_SLOT', s.id,
       CONCAT(c.name, ' ', DATE_FORMAT(s.start_at, '%m-%d %H:%i'), '-', DATE_FORMAT(s.end_at, '%H:%i')),
       s.start_at, s.end_at, s.price_cents, s.price_cents
FROM slot s JOIN court c ON c.id = s.court_id WHERE s.id = @s2;
UPDATE slot SET status = 'BOOKED', owner_type = 'BOOKING', owner_id = @b2, version = version + 1 WHERE id = @s2;

-- 小周：待支付（锁位中）
INSERT INTO booking (booking_no, user_id, venue_id, type, status, list_amount_cents, discount_cents,
                     payable_cents, paid_cents, hold_expires_at, created_at)
SELECT CONCAT('DEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '3'), @u3, s.venue_id, 'COURT', 'PENDING_PAYMENT',
       s.price_cents, 0, s.price_cents, 0, DATE_ADD(NOW(), INTERVAL 15 MINUTE), NOW()
FROM slot s WHERE s.id = @s3;
SET @b3 = LAST_INSERT_ID();
INSERT INTO booking_item (booking_id, item_type, ref_id, title, start_at, end_at, unit_price_cents, amount_cents)
SELECT @b3, 'COURT_SLOT', s.id,
       CONCAT(c.name, ' ', DATE_FORMAT(s.start_at, '%m-%d %H:%i'), '-', DATE_FORMAT(s.end_at, '%H:%i')),
       s.start_at, s.end_at, s.price_cents, s.price_cents
FROM slot s JOIN court c ON c.id = s.court_id WHERE s.id = @s3;
UPDATE slot SET status = 'HELD', hold_expires_at = DATE_ADD(NOW(), INTERVAL 15 MINUTE),
    owner_type = 'BOOKING', owner_id = @b3, version = version + 1 WHERE id = @s3;

-- 对应支付单（财务流水与订单详情演示）
INSERT INTO pay_order (pay_no, booking_id, user_id, purpose, biz_ref, channel, amount_cents,
                       status, third_party_txn_id, paid_at, created_at)
SELECT CONCAT('PYDEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '1'), @b1, @u1, 'BOOKING', @b1,
       'WECHAT_MOCK', s.price_cents, 'PAID', 'MOCKPAYDEMO1', NOW(), NOW()
FROM slot s WHERE s.id = @s1;
INSERT INTO pay_order (pay_no, booking_id, user_id, purpose, biz_ref, channel, amount_cents,
                       status, third_party_txn_id, paid_at, created_at)
SELECT CONCAT('PYDEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '2'), @b2, @u2, 'BOOKING', @b2,
       'WECHAT_MOCK', s.price_cents, 'PAID', 'MOCKPAYDEMO2', NOW(), NOW()
FROM slot s WHERE s.id = @s2;

-- 订单状态流转（订单详情演示）
INSERT INTO booking_status_history (booking_id, from_status, to_status, actor, reason, created_at)
VALUES (@b1, NULL, 'PENDING_PAYMENT', 'USER', '锁位', NOW()),
       (@b1, 'PENDING_PAYMENT', 'CONFIRMED', 'SYSTEM', '支付成功', NOW()),
       (@b2, NULL, 'PENDING_PAYMENT', 'USER', '锁位', NOW()),
       (@b2, 'PENDING_PAYMENT', 'CONFIRMED', 'SYSTEM', '支付成功', NOW()),
       (@b3, NULL, 'PENDING_PAYMENT', 'USER', '锁位', NOW());

-- 演示退款：小周付了 20:00 的场，开场前取消，全额原路退回
INSERT INTO booking (booking_no, user_id, venue_id, type, status, list_amount_cents, discount_cents,
                     payable_cents, paid_cents, cancel_reason, created_at)
SELECT CONCAT('DEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '6'), @u3, s.venue_id, 'COURT', 'CANCELLED',
       s.price_cents, 0, s.price_cents, s.price_cents, 'USER_CANCEL', NOW()
FROM slot s WHERE s.id = @s5;
SET @b6 = LAST_INSERT_ID();
INSERT INTO booking_item (booking_id, item_type, ref_id, title, start_at, end_at, unit_price_cents, amount_cents)
SELECT @b6, 'COURT_SLOT', s.id,
       CONCAT(c.name, ' ', DATE_FORMAT(s.start_at, '%m-%d %H:%i'), '-', DATE_FORMAT(s.end_at, '%H:%i')),
       s.start_at, s.end_at, s.price_cents, s.price_cents
FROM slot s JOIN court c ON c.id = s.court_id WHERE s.id = @s5;
INSERT INTO pay_order (pay_no, booking_id, user_id, purpose, biz_ref, channel, amount_cents,
                       status, third_party_txn_id, paid_at, created_at)
SELECT CONCAT('PYDEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '6'), @b6, @u3, 'BOOKING', @b6,
       'WECHAT_MOCK', s.price_cents, 'REFUNDED', 'MOCKPAYDEMO6', NOW(), NOW()
FROM slot s WHERE s.id = @s5;
INSERT INTO refund_order (refund_no, booking_id, user_id, amount_cents, reason, channel, status,
                          third_party_refund_id, created_at, completed_at)
SELECT CONCAT('RFDEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '6'), @b6, @u3, s.price_cents,
       '开场前取消，全额退款', 'WECHAT_MOCK', 'SUCCESS', 'MOCKRFDEMO6', NOW(), NOW()
FROM slot s WHERE s.id = @s5;
INSERT INTO booking_status_history (booking_id, from_status, to_status, actor, reason, created_at)
VALUES (@b6, NULL, 'PENDING_PAYMENT', 'USER', '锁位', NOW()),
       (@b6, 'PENDING_PAYMENT', 'CONFIRMED', 'SYSTEM', '支付成功', NOW()),
       (@b6, 'CONFIRMED', 'CANCELLED', 'USER', '开场前取消，全额退款', NOW());

-- 一条真实的 AI 转人工（小林问退款）
INSERT INTO ai_session (user_id, status, model, prompt_version, started_at, ended_at)
VALUES (@u1, 'HANDOFF', 'mock', 'v1', NOW(), NOW());
SET @sess = LAST_INSERT_ID();
INSERT INTO ai_message (session_id, role, content, created_at)
VALUES (@sess, 'USER', '我临时有事，今晚的场能退吗？', NOW()),
       (@sess, 'ASSISTANT', '按场馆规则：开场前 24 小时以上取消全额退款；2–24 小时退 70%；不足 2 小时不退。如需办理，我帮你转前台。', NOW());
INSERT INTO ai_handoff (session_id, user_id, reason, summary, status, created_at)
VALUES (@sess, @u1, 'PAYMENT', '顾客要求退今晚的场地费', 'OPEN', NOW());

-- 演示拼场：小林发起 3v3，小周已加入，份子钱由平台托管
INSERT INTO booking (booking_no, user_id, venue_id, type, status, list_amount_cents, discount_cents,
                     payable_cents, paid_cents, created_at)
SELECT CONCAT('DEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '4'), @u1, s.venue_id, 'PICKUP', 'CONFIRMED',
       s.price_cents, 0, s.price_cents, s.price_cents, NOW()
FROM slot s WHERE s.id = @s4;
SET @b4 = LAST_INSERT_ID();
INSERT INTO booking_item (booking_id, item_type, ref_id, title, start_at, end_at, unit_price_cents, amount_cents)
SELECT @b4, 'COURT_SLOT', s.id,
       CONCAT(c.name, ' 拼场 3v3 ', DATE_FORMAT(s.start_at, '%m-%d %H:%i'), '-', DATE_FORMAT(s.end_at, '%H:%i')),
       s.start_at, s.end_at, s.price_cents, s.price_cents
FROM slot s JOIN court c ON c.id = s.court_id WHERE s.id = @s4;
UPDATE slot SET status = 'BOOKED', owner_type = 'BOOKING', owner_id = @b4, version = version + 1 WHERE id = @s4;
SET @share = GREATEST(1, FLOOR((SELECT price_cents FROM slot WHERE id = @s4) / 6));
INSERT INTO pickup_game (initiator_id, booking_id, slot_id, format, total_players, joined_players,
                         price_per_person_cents, note, status, settled, created_at)
VALUES (@u1, @b4, @s4, '3v3', 6, 2, @share, '周末轻松打，欢迎新手', 'OPEN', 0, NOW());
SET @game = LAST_INSERT_ID();
INSERT INTO pickup_participant (game_id, user_id, booking_id, status, created_at)
VALUES (@game, @u1, @b4, 'CONFIRMED', NOW());
INSERT INTO booking (booking_no, user_id, venue_id, type, status, list_amount_cents, discount_cents,
                     payable_cents, paid_cents, created_at)
SELECT CONCAT('DEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '5'), @u3, s.venue_id, 'PICKUP', 'CONFIRMED',
       @share, 0, @share, @share, NOW()
FROM slot s WHERE s.id = @s4;
SET @b5 = LAST_INSERT_ID();
INSERT INTO booking_item (booking_id, item_type, ref_id, title, start_at, end_at, unit_price_cents, amount_cents)
SELECT @b5, 'PICKUP_SHARE', @game, '拼场份子钱 3v3', s.start_at, s.end_at, @share, @share
FROM slot s WHERE s.id = @s4;
INSERT INTO pickup_participant (game_id, user_id, booking_id, status, created_at)
VALUES (@game, @u3, @b5, 'CONFIRMED', NOW());
INSERT INTO pay_order (pay_no, booking_id, user_id, purpose, biz_ref, channel, amount_cents,
                       status, third_party_txn_id, paid_at, created_at)
SELECT CONCAT('PYDEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '4'), @b4, @u1, 'BOOKING', @b4,
       'WECHAT_MOCK', s.price_cents, 'PAID', 'MOCKPAYDEMO4', NOW(), NOW()
FROM slot s WHERE s.id = @s4;
INSERT INTO pay_order (pay_no, booking_id, user_id, purpose, biz_ref, channel, amount_cents,
                       status, third_party_txn_id, paid_at, created_at)
VALUES (CONCAT('PYDEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '5'), @b5, @u3, 'BOOKING', @b5,
        'WECHAT_MOCK', @share, 'PAID', 'MOCKPAYDEMO5', NOW(), NOW());

-- 演示：小林持有一张新人券
INSERT INTO user_coupon (user_id, template_id, status, received_at, expires_at)
SELECT @u1, id, 'UNUSED', NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY)
FROM coupon_template WHERE name = '新人场地券' LIMIT 1;

-- 演示：前台近期操作留痕
INSERT INTO admin_audit_log (actor_id, actor_name, action, target_type, target_id, detail, created_at)
SELECT id, nickname, 'CUSTOMER_GRANT_COUPON', 'USER', @u1, '发券：新人场地券', NOW()
FROM user WHERE phone = '13800000000';
INSERT INTO admin_audit_log (actor_id, actor_name, action, target_type, target_id, detail, created_at)
SELECT id, nickname, 'SLOT_PRICE', 'SLOT', @s2, '改价 10000 → 12000 分', NOW()
FROM user WHERE phone = '13800000000';

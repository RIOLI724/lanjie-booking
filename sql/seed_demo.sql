-- 演示数据（交付演示用，非测试数据；请在干净库上执行一次）
-- 用法：mysql -uroot -p lanjie < sql/seed_demo.sql

-- 演示顾客
INSERT INTO user (phone, nickname, role, status, cumulative_recharge_cents)
VALUES ('13800001001', '小林', 'CUSTOMER', 'ACTIVE', 0);
SET @u1 = LAST_INSERT_ID();
INSERT INTO user (phone, nickname, role, status, cumulative_recharge_cents)
VALUES ('13800001002', '阿杰', 'CUSTOMER', 'ACTIVE', 0);
SET @u2 = LAST_INSERT_ID();
INSERT INTO user (phone, nickname, role, status, cumulative_recharge_cents)
VALUES ('13800001003', '小周', 'CUSTOMER', 'ACTIVE', 0);
SET @u3 = LAST_INSERT_ID();

INSERT INTO wallet_account (user_id, balance_cents, version)
VALUES (@u1, 24000, 0), (@u2, 0, 0), (@u3, 12000, 0);

-- 今天三个格子
SET @s1 = (SELECT id FROM slot WHERE slot_date = CURDATE() AND status = 'AVAILABLE' ORDER BY start_at LIMIT 0, 1);
SET @s2 = (SELECT id FROM slot WHERE slot_date = CURDATE() AND status = 'AVAILABLE' ORDER BY start_at LIMIT 1, 1);
SET @s3 = (SELECT id FROM slot WHERE slot_date = CURDATE() AND status = 'AVAILABLE' ORDER BY start_at LIMIT 2, 1);

-- 小林：已到场
INSERT INTO booking (booking_no, user_id, venue_id, type, status, list_amount_cents, discount_cents,
                     payable_cents, paid_cents, checked_in_at, created_at)
SELECT CONCAT('DEMO', DATE_FORMAT(NOW(), '%m%d%H%i%s'), '1'), @u1, s.venue_id, 'COURT', 'CONFIRMED',
       s.price_cents, 0, s.price_cents, s.price_cents, NOW(), NOW()
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

-- 一条退款记录
INSERT INTO refund_order (refund_no, booking_id, user_id, amount_cents, reason, channel, status,
                          third_party_refund_id, created_at, completed_at)
VALUES ('RFDEMO0001', @b1, @u1, 2800, '用户取消', 'WALLET', 'SUCCESS', 'MOCKRFDEMO1', NOW(), NOW());

-- 一条真实的 AI 转人工（小林问退款）
INSERT INTO ai_session (user_id, status, model, prompt_version, started_at, ended_at)
VALUES (@u1, 'HANDOFF', 'mock', 'v1', NOW(), NOW());
SET @sess = LAST_INSERT_ID();
INSERT INTO ai_message (session_id, role, content, created_at)
VALUES (@sess, 'USER', '我临时有事，今晚的场能退吗？', NOW()),
       (@sess, 'ASSISTANT', '按场馆规则：开场前 24 小时以上取消全额退款；2–24 小时退 70%；不足 2 小时不退。如需办理，我帮你转前台。', NOW());
INSERT INTO ai_handoff (session_id, user_id, reason, summary, status, created_at)
VALUES (@sess, @u1, 'PAYMENT', '顾客要求退今晚的场地费', 'OPEN', NOW());

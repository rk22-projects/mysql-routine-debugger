-- MySQL Routine Debugger demo database
--
-- This script is safe to run repeatedly, but it deliberately drops and
-- recreates the dedicated demo schema and all data inside it.
--
-- Suggested debugger entry point:
--   demo_debug_journey
--
-- Example invocation from a second SQL connection:
--   SET @demo_result = NULL;
--   CALL mysql_routine_debugger_demo.demo_debug_journey(7, @demo_result);
--   SELECT @demo_result AS returned_value;

DROP DATABASE IF EXISTS mysql_routine_debugger_demo;

CREATE DATABASE mysql_routine_debugger_demo
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE mysql_routine_debugger_demo;

-- Stored-program literals retain the connection collation from creation time.
-- Match it to the database before recreating the routines below.
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE demo_items (
    item_id  INT          NOT NULL PRIMARY KEY,
    category VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    weight   INT          NOT NULL,
    enabled  BOOLEAN      NOT NULL DEFAULT TRUE
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

INSERT INTO demo_items (item_id, category, weight, enabled) VALUES
    (1, 'alpha', 3, TRUE),
    (2, 'beta',  5, TRUE),
    (3, 'gamma', 8, TRUE),
    (4, 'alpha', 2, FALSE),
    (5, 'beta',  7, TRUE),
    (6, 'other', 4, TRUE);

DELIMITER $$

-- Called by demo_debug_journey. Put a breakpoint inside this procedure to
-- demonstrate Step Into (F7) and Step Out (Ctrl+F7).
CREATE PROCEDURE demo_calculate_score(
    IN  p_seed     INT,
    IN  p_strategy VARCHAR(20),
    OUT p_score    INT
)
SQL SECURITY INVOKER
READS SQL DATA
BEGIN
    DECLARE v_done         BOOLEAN DEFAULT FALSE;
    DECLARE v_item_id      INT DEFAULT 0;
    DECLARE v_category     VARCHAR(20) DEFAULT '';
    DECLARE v_weight       INT DEFAULT 0;
    DECLARE v_total        INT DEFAULT 0;
    DECLARE v_counter      INT DEFAULT 0;
    DECLARE v_repeat_count INT DEFAULT 0;
    DECLARE v_bonus        INT DEFAULT 0;

    DECLARE item_cursor CURSOR FOR
        SELECT item_id, category, weight
          FROM demo_items
         WHERE enabled = TRUE
         ORDER BY item_id;

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = TRUE;

    -- A WHILE loop with nested IF/ELSEIF/ELSE branches.
    WHILE v_counter < 3 DO
        SET v_counter = v_counter + 1;

        IF MOD(p_seed + v_counter, 3) = 0 THEN
            SET v_total = v_total + 9;
        ELSEIF MOD(p_seed + v_counter, 2) = 0 THEN
            SET v_total = v_total + 4;
        ELSE
            SET v_total = v_total + 1;
        END IF;
    END WHILE;

    -- A REPEAT loop. It always executes at least once.
    REPEAT
        SET v_repeat_count = v_repeat_count + 1;
        SET v_total = v_total + v_repeat_count;
    UNTIL v_repeat_count >= 2
    END REPEAT;

    -- Cursor processing inside a labelled LOOP.
    OPEN item_cursor;

    item_loop: LOOP
        FETCH item_cursor INTO v_item_id, v_category, v_weight;

        IF v_done THEN
            LEAVE item_loop;
        END IF;

        -- A procedural CASE statement with deliberately different branches.
        CASE v_category COLLATE utf8mb4_unicode_ci
            WHEN _utf8mb4'alpha' COLLATE utf8mb4_unicode_ci THEN
                SET v_bonus = v_weight * 2;
            WHEN _utf8mb4'beta' COLLATE utf8mb4_unicode_ci THEN
                SET v_bonus = v_weight + 10;
            WHEN _utf8mb4'gamma' COLLATE utf8mb4_unicode_ci THEN
                SET v_bonus = v_weight DIV 2;
            ELSE
                SET v_bonus = 1;
        END CASE;

        IF v_item_id = p_seed THEN
            SET v_total = v_total + 25;
        ELSE
            SET v_total = v_total + v_bonus;
        END IF;
    END LOOP;

    CLOSE item_cursor;

    -- A searched CASE expression exercises a different form of CASE.
    SET v_total = v_total +
        CASE
            WHEN UPPER(COALESCE(p_strategy, _utf8mb4'NORMAL')) COLLATE utf8mb4_unicode_ci
                 = _utf8mb4'FAST' COLLATE utf8mb4_unicode_ci THEN 100
            WHEN UPPER(COALESCE(p_strategy, _utf8mb4'NORMAL')) COLLATE utf8mb4_unicode_ci
                 = _utf8mb4'SAFE' COLLATE utf8mb4_unicode_ci THEN 50
            ELSE 10
        END;

    -- The value is deterministic but intentionally meaningless.
    SET p_score = v_total + COALESCE(p_seed, 0);
END$$

-- Main demo routine. Start debugging here, then step into the CALL below.
CREATE PROCEDURE demo_debug_journey(
    IN  p_input  INT,
    OUT p_result VARCHAR(255)
)
SQL SECURITY INVOKER
READS SQL DATA
BEGIN
    DECLARE v_normalized   INT DEFAULT 0;
    DECLARE v_strategy     VARCHAR(20) DEFAULT 'NORMAL';
    DECLARE v_helper_score INT DEFAULT 0;
    DECLARE v_countdown    INT DEFAULT 3;
    DECLARE v_checksum     INT DEFAULT 0;
    DECLARE v_label        VARCHAR(40) DEFAULT '';

    IF p_input IS NULL THEN
        SET v_normalized = 0;
        SET v_label = 'null-input';
    ELSEIF p_input < 0 THEN
        SET v_normalized = ABS(p_input);
        SET v_label = 'negative-input';
    ELSE
        SET v_normalized = p_input;
        SET v_label = 'regular-input';
    END IF;

    CASE MOD(v_normalized, 3)
        WHEN 0 THEN
            SET v_strategy = 'FAST';
        WHEN 1 THEN
            SET v_strategy = 'SAFE';
        ELSE
            SET v_strategy = 'NORMAL';
    END CASE;

    -- Primary Step Into / Step Out demonstration point.
    CALL demo_calculate_score(v_normalized, v_strategy, v_helper_score);

    -- More statements remain after returning from the helper, making Step Out
    -- visibly return to this routine instead of immediately completing it.
    WHILE v_countdown > 0 DO
        SET v_checksum = v_checksum + (v_helper_score MOD (v_countdown + 2));
        SET v_countdown = v_countdown - 1;
    END WHILE;

    REPEAT
        SET v_checksum = v_checksum + 1;
    UNTIL v_checksum >= 6
    END REPEAT;

    IF v_helper_score > 150 THEN
        SET v_label = CONCAT(v_label, '-high');
    ELSE
        SET v_label = CONCAT(v_label, '-standard');
    END IF;

    SET p_result = CONCAT(
        'DEMO-',
        UPPER(v_strategy),
        '-SCORE-', v_helper_score,
        '-CHECK-', v_checksum,
        '-', v_label
    );

    -- Convenient visible result in SQL clients that do not display OUT params.
    SELECT p_result AS demo_result,
           v_helper_score AS helper_score,
           v_checksum AS checksum;
END$$

DELIMITER ;

-- Optional smoke test:
-- SET @demo_result = NULL;
-- CALL demo_debug_journey(7, @demo_result);
-- SELECT @demo_result AS returned_value;

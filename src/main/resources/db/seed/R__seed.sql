-- =====================================================================
-- Development seed -- a warehouse with something in it
--
-- WHERE THIS LIVES, AND WHY NOT IN db/migration
--
-- A repeatable migration under db/migration runs in every environment.
-- In the test one, AbstractIntegrationTest truncates it away before each
-- case, so it would be dead weight there and a live risk to the
-- repository slices that count rows. In a production one it would be
-- fabricated data in a real database.
--
-- So it sits in db/seed, which only the dev profile adds to
-- spring.flyway.locations. compose.yaml activates that profile, which is
-- what makes `docker compose up --build` on a clean clone show a working
-- warehouse instead of an empty one.
--
-- WHY SQL AND NOT A CommandLineRunner
--
-- A Java seeder calling the services could not create state the API
-- would refuse, which is a real advantage. It loses to this: the seed
-- has to write movements with believable occurred_at values spread over
-- past weeks, and V4's ordering rule validates a movement against *now*,
-- which makes backdated history awkward to drive through the API. A seed
-- is history; SQL writes history directly.
--
-- WHY R__ RATHER THAN A VERSION NUMBER
--
-- The seed is not a schema change. Repeatable means editing it re-applies
-- it on the next start instead of needing a new file each time.
-- =====================================================================

DO $$
DECLARE
    v_producer_serra   BIGINT;
    v_producer_vale    BIGINT;
    v_warehouse        BIGINT;
    v_pos_a            BIGINT;
    v_pos_b            BIGINT;
    v_pos_c            BIGINT;
    v_lot_2023         BIGINT;
    v_lot_2024         BIGINT;
    v_lot_2025         BIGINT;
    v_shipment         BIGINT;
BEGIN
    -- The whole seed is skipped if anything is already here. A repeatable
    -- migration re-runs whenever its checksum changes, and stock_movement
    -- refuses UPDATE and DELETE (V4) -- so inserting a second copy of the
    -- history would be permanent. Guarding the whole block is simpler to
    -- read than a conflict clause on every statement, and the guard is
    -- the honest statement of intent: this seeds an empty database.
    IF EXISTS (SELECT 1 FROM producer) THEN
        RAISE NOTICE 'Seed skipped: the database already holds data.';
        RETURN;
    END IF;

    -- -----------------------------------------------------------------
    -- Accounts
    --
    -- Two, so the role split can actually be tried: log in as the
    -- operator and POST /api/lots comes back 403.
    --
    -- These passwords are published in a public repository. They exist so
    -- a reader can open /docs and press buttons; they are as much a
    -- credential as "password" on a demo screen. The seed never runs
    -- outside the dev profile, which is the point.
    -- -----------------------------------------------------------------
    INSERT INTO app_user (username, password_hash, display_name, role) VALUES
        ('warehouse.admin',
         '$2a$10$yRgbGIUv820of0cvGJ5zeeiNKdXYD9M9QkAR.tZrLNbDQ.varVmGa',  -- admin123
         'Warehouse Administrator', 'ADMIN'),
        ('floor.operator',
         '$2a$10$pV7s/q7QRdDDdF86c3awouNdRDLnZ2hnrRwj1DFjjVcF.xHlKUGI6',  -- operator123
         'Floor Operator', 'OPERATOR');

    -- -----------------------------------------------------------------
    -- Registry
    -- -----------------------------------------------------------------
    INSERT INTO producer (code, name, city, state)
         VALUES ('COP-001', 'Cooperativa Serra Alta', 'Guaxupe', 'MG')
      RETURNING id INTO v_producer_serra;

    INSERT INTO producer (code, name, city, state)
         VALUES ('COP-002', 'Fazenda Vale Verde', 'Patrocinio', 'MG')
      RETURNING id INTO v_producer_vale;

    INSERT INTO warehouse (code, name, city, state)
         VALUES ('WH1', 'Armazem Central', 'Guaxupe', 'MG')
      RETURNING id INTO v_warehouse;

    INSERT INTO storage_position (warehouse_id, aisle, bay, level, code, capacity_kg)
         VALUES (v_warehouse, '01', '01', '01', 'WH1-A01-B01-L01', 60000.000)
      RETURNING id INTO v_pos_a;

    INSERT INTO storage_position (warehouse_id, aisle, bay, level, code, capacity_kg)
         VALUES (v_warehouse, '02', '01', '01', 'WH1-A02-B01-L01', 60000.000)
      RETURNING id INTO v_pos_b;

    -- Deliberately small, so the capacity invariant can be tripped on
    -- purpose from Swagger rather than only read about in the README.
    INSERT INTO storage_position (warehouse_id, aisle, bay, level, code, capacity_kg)
         VALUES (v_warehouse, '03', '01', '01', 'WH1-A03-B01-L01', 1000.000)
      RETURNING id INTO v_pos_c;

    -- -----------------------------------------------------------------
    -- Lots
    --
    -- Three crop years, so the FIFO suggestion has something to order,
    -- and three different moistures so the weighted blend differs
    -- visibly from a simple average.
    -- -----------------------------------------------------------------
    INSERT INTO lot (code, producer_id, crop_year, net_weight_kg, bags,
                     moisture_percent, screen_size, defect_type, cup_quality,
                     received_on, status)
         VALUES ('LOT-2023-001', v_producer_serra, 2023, 12000.000, 200,
                 11.50, '17/18', 'T6', 'HARD', CURRENT_DATE - 420, 'STORED')
      RETURNING id INTO v_lot_2023;

    INSERT INTO lot (code, producer_id, crop_year, net_weight_kg, bags,
                     moisture_percent, screen_size, defect_type, cup_quality,
                     received_on, status)
         VALUES ('LOT-2024-001', v_producer_vale, 2024, 18000.000, 300,
                 12.80, '15/16', 'T4/5', 'SOFT', CURRENT_DATE - 210, 'STORED')
      RETURNING id INTO v_lot_2024;

    INSERT INTO lot (code, producer_id, crop_year, net_weight_kg, bags,
                     moisture_percent, screen_size, defect_type, cup_quality,
                     received_on, status)
         VALUES ('LOT-2025-001', v_producer_serra, 2025, 9000.000, 150,
                 10.90, '17/18', 'T6', 'STRICTLY_SOFT', CURRENT_DATE - 30, 'STORED')
      RETURNING id INTO v_lot_2025;

    -- -----------------------------------------------------------------
    -- Ledger
    --
    -- In chronological order, and dated in the past, so the lot statement
    -- reads like a history rather than like three rows written at once.
    -- Each lot is received in full, then part of the 2023 crop is moved
    -- to a second position -- which is what makes the picking suggestion
    -- return two lines for one lot and gives a transfer to look at.
    -- -----------------------------------------------------------------
    INSERT INTO stock_movement (type, lot_id, target_position_id, weight_kg, occurred_at, reason)
         VALUES ('INBOUND', v_lot_2023, v_pos_a, 12000.000, now() - INTERVAL '60 days', 'Receiving');

    INSERT INTO stock_movement (type, lot_id, target_position_id, weight_kg, occurred_at, reason)
         VALUES ('INBOUND', v_lot_2024, v_pos_b, 18000.000, now() - INTERVAL '45 days', 'Receiving');

    INSERT INTO stock_movement (type, lot_id, source_position_id, target_position_id,
                                weight_kg, occurred_at, reason)
         VALUES ('TRANSFER', v_lot_2023, v_pos_a, v_pos_b, 4000.000,
                 now() - INTERVAL '20 days', 'Consolidating the 2023 crop');

    INSERT INTO stock_movement (type, lot_id, target_position_id, weight_kg, occurred_at, reason)
         VALUES ('INBOUND', v_lot_2025, v_pos_a, 9000.000, now() - INTERVAL '10 days', 'Receiving');

    -- -----------------------------------------------------------------
    -- A shipment left in DRAFT
    --
    -- So the reservation is visible without composing one first: the 2024
    -- lot shows as RESERVED, and the picking suggestion already excludes
    -- the 5,000 kg this draft has claimed. Confirming it from Swagger is
    -- then a one-click demonstration of the partial-dispatch rule -- the
    -- lot goes back to STORED, not SHIPPED, because 13,000 kg remain.
    -- -----------------------------------------------------------------
    INSERT INTO shipment (code, status, destination, scheduled_for)
         VALUES ('SHP-2026-001', 'DRAFT', 'Port of Santos', CURRENT_DATE + 21)
      RETURNING id INTO v_shipment;

    INSERT INTO shipment_item (shipment_id, lot_id, source_position_id, weight_kg)
         VALUES (v_shipment, v_lot_2024, v_pos_b, 5000.000);

    UPDATE lot SET status = 'RESERVED' WHERE id = v_lot_2024;

    RAISE NOTICE 'Seed applied: 2 users, 2 producers, 1 warehouse, 3 positions, 3 lots, 4 movements, 1 draft shipment.';
END $$;

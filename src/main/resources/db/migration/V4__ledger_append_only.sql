-- =====================================================================
-- V4 - Make the ledger append-only for real
--
-- "Never UPDATE, never DELETE" has been the first rule of this project
-- since V2, and until now it was enforced by convention alone: every
-- column mapped updatable = false, no setters on the entity, no delete
-- exposed on the repository. All of that binds the application and
-- nothing else. A psql session, a migration, a second service or a fix
-- typed by hand at two in the morning could still rewrite history.
--
-- A rule that the database does not enforce is a rule the database does
-- not have. This one now does.
-- =====================================================================

CREATE OR REPLACE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'stock_movement is append-only: % is not allowed (movement id %)',
        TG_OP, OLD.id
        -- Class 23 so the JDBC driver reports an integrity violation and
        -- the API answers 409, the same as any other constraint, instead
        -- of leaking a raw 500.
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION reject_ledger_mutation() IS
    'Refuses any row-level UPDATE or DELETE. A movement recorded in error is corrected by recording its opposite, which is what leaves an audit trail worth having.';

CREATE TRIGGER trg_stock_movement_append_only
    BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- -------------------------------------------------------------------
-- What this deliberately does NOT block: TRUNCATE.
--
-- TRUNCATE fires only statement-level TRUNCATE triggers, never this
-- row-level one, so it still works -- and that is on purpose. It is not
-- a rewrite of a row's history but a wipe of the whole table, it needs
-- table-owner rights that no application account should hold, and the
-- integration tests reset the database with it between cases.
--
-- Blocking it would buy protection against an actor who already owns the
-- schema, at the price of the test harness. The threat this trigger
-- exists for is the ordinary UPDATE or DELETE that looks reasonable in
-- the moment.
-- -------------------------------------------------------------------

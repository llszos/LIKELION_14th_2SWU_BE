-- #118: apply after V3 (#116); V1 and V2 belong to #131 and #122.
-- Existing rows retain their data and receive NULL for this optional field.
ALTER TABLE situations
    ADD COLUMN custom_situation_type VARCHAR(15) NULL;

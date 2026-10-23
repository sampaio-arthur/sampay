-- Run by the Postgres entrypoint only when the volume is empty (first start).
-- One database per service; the next ones are added here when their owning service exists.
-- Once the volume exists, a new database needs a manual CREATE DATABASE or a "down -v".
CREATE DATABASE ledger_db;

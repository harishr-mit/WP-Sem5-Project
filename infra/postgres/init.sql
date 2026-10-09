-- Database per service (PROJECT.md §3). Runs once, on an empty data volume.
-- All five are created now so later phases never need `docker compose down -v`.
-- Dev-only passwords (same as the role name); each service's application.yml defaults to them.

CREATE ROLE auth_svc LOGIN PASSWORD 'auth_svc';
CREATE DATABASE auth_db OWNER auth_svc;

CREATE ROLE event_svc LOGIN PASSWORD 'event_svc';
CREATE DATABASE event_db OWNER event_svc;

CREATE ROLE pass_svc LOGIN PASSWORD 'pass_svc';
CREATE DATABASE pass_db OWNER pass_svc;

CREATE ROLE checkin_svc LOGIN PASSWORD 'checkin_svc';
CREATE DATABASE checkin_db OWNER checkin_svc;

CREATE ROLE notification_svc LOGIN PASSWORD 'notification_svc';
CREATE DATABASE notification_db OWNER notification_svc;

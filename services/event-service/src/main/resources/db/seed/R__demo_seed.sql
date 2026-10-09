-- Demo events so Swagger has something to search on first start (docs/plans/phase-1.md §4).
-- Repeatable migration: re-runs whenever this file changes, so every statement is idempotent.
-- Not loaded in tests (application-test.yml limits Flyway to db/migration). Sample data only.

INSERT INTO event (code, title, description, category_id, venue_id, department, capacity,
                   starts_at, ends_at, registration_opens_at, registration_closes_at, status,
                   created_at, created_by, updated_at, updated_by)
SELECT e.code, e.title, e.description, c.id, v.id, e.department, e.capacity,
       e.starts_at, e.ends_at, e.reg_opens, e.reg_closes, e.status,
       now(), 'seed', now(), 'seed'
FROM (VALUES
    ('TECHFEST26', 'TechFest Hackathon',
     '24-hour team hackathon. Bring a laptop; food and Wi-Fi provided.',
     'TECHNICAL', 'Innovation Centre Lab', 'CSE', 100,
     TIMESTAMPTZ '2026-11-14 09:00+05:30', TIMESTAMPTZ '2026-11-15 09:00+05:30',
     TIMESTAMPTZ '2026-10-20 09:00+05:30', TIMESTAMPTZ '2026-11-12 18:00+05:30', 'REGISTRATION_OPEN'),
    ('RANG26', 'Rang: Cultural Night',
     'Music, dance and drama from every hostel.',
     'CULTURAL', 'Open Air Theatre', NULL, 2500,
     TIMESTAMPTZ '2026-11-21 18:00+05:30', TIMESTAMPTZ '2026-11-21 23:00+05:30',
     TIMESTAMPTZ '2026-10-25 09:00+05:30', TIMESTAMPTZ '2026-11-20 18:00+05:30', 'PUBLISHED'),
    ('AIWS26', 'Applied AI Workshop',
     'Three hands-on sessions: data prep, model training, deployment.',
     'WORKSHOP', 'Innovation Centre Lab', 'CSE', 60,
     TIMESTAMPTZ '2026-12-05 10:00+05:30', TIMESTAMPTZ '2026-12-05 17:00+05:30',
     TIMESTAMPTZ '2026-11-01 09:00+05:30', TIMESTAMPTZ '2026-12-03 18:00+05:30', 'REGISTRATION_OPEN'),
    ('ROBOWARS26', 'RoboWars',
     'Combat robotics arena, 15 kg class.',
     'TECHNICAL', 'Sports Complex', 'MECH', 800,
     TIMESTAMPTZ '2026-11-15 14:00+05:30', TIMESTAMPTZ '2026-11-15 20:00+05:30',
     NULL, NULL, 'DRAFT'),
    ('CRICKET26', 'Inter-Department Cricket Final',
     'CSE vs ECE, final of the inter-department league.',
     'SPORTS', 'Sports Complex', NULL, 1500,
     TIMESTAMPTZ '2026-12-12 15:00+05:30', TIMESTAMPTZ '2026-12-12 19:00+05:30',
     NULL, NULL, 'PUBLISHED'),
    ('SPACETALK26', 'Seminar: Careers in Space Tech',
     'Guest lecture followed by Q&A.',
     'SEMINAR', 'Library Seminar Hall', 'AERO', 180,
     TIMESTAMPTZ '2027-01-09 11:00+05:30', TIMESTAMPTZ '2027-01-09 13:00+05:30',
     TIMESTAMPTZ '2026-12-01 09:00+05:30', TIMESTAMPTZ '2027-01-08 18:00+05:30', 'PUBLISHED')
) AS e(code, title, description, category_code, venue_name, department, capacity,
       starts_at, ends_at, reg_opens, reg_closes, status)
JOIN category c ON c.code = e.category_code
JOIN venue v ON v.name = e.venue_name
ON CONFLICT (code) DO NOTHING;

INSERT INTO event_session (event_id, title, starts_at, ends_at)
SELECT ev.id, s.title, s.starts_at, s.ends_at
FROM (VALUES
    ('TECHFEST26', 'Day 1: Kick-off and build', TIMESTAMPTZ '2026-11-14 09:00+05:30', TIMESTAMPTZ '2026-11-14 23:59+05:30'),
    ('TECHFEST26', 'Day 2: Demos and judging',  TIMESTAMPTZ '2026-11-15 00:00+05:30', TIMESTAMPTZ '2026-11-15 09:00+05:30'),
    ('AIWS26', 'Session 1: Data preparation',   TIMESTAMPTZ '2026-12-05 10:00+05:30', TIMESTAMPTZ '2026-12-05 12:00+05:30'),
    ('AIWS26', 'Session 2: Model training',     TIMESTAMPTZ '2026-12-05 13:00+05:30', TIMESTAMPTZ '2026-12-05 15:00+05:30'),
    ('AIWS26', 'Session 3: Deployment',         TIMESTAMPTZ '2026-12-05 15:15+05:30', TIMESTAMPTZ '2026-12-05 17:00+05:30')
) AS s(event_code, title, starts_at, ends_at)
JOIN event ev ON ev.code = s.event_code
ON CONFLICT (event_id, starts_at) DO NOTHING;

-- Demo events so Swagger has something to search on first start (docs/plans/phase-1.md §4).
-- Repeatable migration: re-runs whenever this file changes, so every statement is idempotent.
-- Not loaded in tests (application-test.yml limits Flyway to db/migration). Sample data only.
-- Organizers are the seeded accounts of auth-service (R__demo_users.sql): A = Anitha Rao, B = Bharath Kumar.

INSERT INTO event (code, title, description, category_id, venue_id, department, capacity,
                   starts_at, ends_at, registration_opens_at, registration_closes_at, status,
                   organizer_id, organizer_name, created_at, created_by, updated_at, updated_by)
SELECT e.code, e.title, e.description, c.id, v.id, e.department, e.capacity,
       e.starts_at, e.ends_at, e.reg_opens, e.reg_closes, e.status,
       CASE WHEN e.code IN ('ROBOWARS26', 'CRICKET26') THEN '00000000-0000-4000-8000-000000000003'::uuid
            ELSE '00000000-0000-4000-8000-000000000002'::uuid END,
       CASE WHEN e.code IN ('ROBOWARS26', 'CRICKET26') THEN 'Bharath Kumar' ELSE 'Anitha Rao' END,
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
     TIMESTAMPTZ '2026-12-01 09:00+05:30', TIMESTAMPTZ '2027-01-08 18:00+05:30', 'PUBLISHED'),
    -- Phase 3 demo: one seat, so the two seeded students show CONFIRMED, WAITLISTED #1 and a promotion.
    ('MINIQUIZ26', 'Tech Quiz (demo: 1 seat)',
     'A one-seat quiz to demonstrate the waitlist: register two students, then cancel the first.',
     'TECHNICAL', 'Library Seminar Hall', NULL, 1,
     TIMESTAMPTZ '2026-11-28 15:00+05:30', TIMESTAMPTZ '2026-11-28 17:00+05:30',
     TIMESTAMPTZ '2026-10-01 09:00+05:30', TIMESTAMPTZ '2026-11-27 18:00+05:30', 'REGISTRATION_OPEN')
) AS e(code, title, description, category_code, venue_name, department, capacity,
       starts_at, ends_at, reg_opens, reg_closes, status)
JOIN category c ON c.code = e.category_code
JOIN venue v ON v.name = e.venue_name
ON CONFLICT (code) DO NOTHING;

-- Phase 3 eligibility (V4). The seeded students are Priya (CSE, 1st year) and Arun (AERO, 1st year):
-- Priya may join the AI workshop, Arun may not; neither may join the space-tech seminar (years 2-4).
INSERT INTO event_eligible_department (event_id, department_code)
SELECT ev.id, d.code
FROM (VALUES ('AIWS26', 'CSE'), ('AIWS26', 'IT'), ('AIWS26', 'AIDS')) AS d(event_code, code)
JOIN event ev ON ev.code = d.event_code
ON CONFLICT DO NOTHING;

UPDATE event SET min_year = 2, max_year = 4 WHERE code = 'SPACETALK26' AND min_year IS NULL;

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

INSERT INTO event_gate (event_id, code, name)
SELECT ev.id, g.code, g.name
FROM (VALUES
    ('TECHFEST26', 'MAIN',  'Main Gate'),
    ('TECHFEST26', 'NORTH', 'North Gate'),
    ('RANG26',     'OAT',   'Open Air Theatre Entrance'),
    ('AIWS26',     'LAB',   'Innovation Centre Lab Door')
) AS g(event_code, code, name)
JOIN event ev ON ev.code = g.event_code
ON CONFLICT (event_id, code) DO NOTHING;

-- Phase 4: pass colours and one event without a static QR (exit criterion 5 of docs/plans/phase-4.md).
UPDATE event SET pass_color = '#1D4E89' WHERE code = 'TECHFEST26' AND pass_color IS NULL;
UPDATE event SET pass_color = '#5B2A86' WHERE code = 'RANG26' AND pass_color IS NULL;
UPDATE event SET static_qr_allowed = FALSE WHERE code = 'AIWS26';

-- Phase 5: check-in settings (docs/plans/phase-5.md §6). RANG26 lets people step out and back in; AIWS26 gives a
-- certificate for 2 of its 3 sessions. Everything else: single entry, all sessions.
UPDATE event SET reentry_allowed = TRUE WHERE code = 'RANG26';
UPDATE event SET certificate_min_sessions = 2 WHERE code = 'AIWS26';

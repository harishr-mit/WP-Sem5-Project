-- Demo accounts (repeatable, idempotent; switched off in tests). Every password is `Passw0rd!` (dev only).
-- Staff use the reserved .test TLD, so these addresses can't belong to anyone; the seed bypasses the sign-up domain
-- rule on purpose. The fixed ids are referenced by event-service (V3 back-fills seed events to organizer A).

INSERT INTO app_user (id, email, canonical_email, full_name, department_code, year_of_study, register_number,
                      password_hash, email_verified_at, enabled, created_at, created_by, updated_at, updated_by)
SELECT u.id::uuid, u.email, u.email, u.full_name, u.department, u.year_of_study, u.register_number,
       '{bcrypt}$2a$10$ROLDbllstk6lg0i.Vy8VhOMKc2kSLTt4RD4tFyZn5aqbKnvq985Xe',
       now(), TRUE, now(), 'seed', now(), 'seed'
FROM (VALUES
    ('00000000-0000-4000-8000-000000000001', 'admin@mitpass.test',             'MITPass Admin',  'CSE',  NULL::smallint, NULL),
    ('00000000-0000-4000-8000-000000000002', 'organizer.a@mitpass.test',       'Anitha Rao',     'CSE',  NULL,           NULL),
    ('00000000-0000-4000-8000-000000000003', 'organizer.b@mitpass.test',       'Bharath Kumar',  'MECH', NULL,           NULL),
    ('00000000-0000-4000-8000-000000000004', 'volunteer@mitpass.test',         'Karthik Selvam', 'ECE',  3,              NULL),
    ('00000000-0000-4000-8000-000000000005', '2026509001@student.annauniv.edu', 'Priya Raman',   'CSE',  1,              '2026509001'),
    ('00000000-0000-4000-8000-000000000006', '2026505014@student.annauniv.edu', 'Arun Prakash',  'AERO', 1,              '2026505014')
) AS u(id, email, full_name, department, year_of_study, register_number)
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_role (user_id, role_id)
SELECT ur.user_id::uuid, r.id
FROM (VALUES
    ('00000000-0000-4000-8000-000000000001', 'ADMIN'),
    ('00000000-0000-4000-8000-000000000002', 'ORGANIZER'),
    ('00000000-0000-4000-8000-000000000002', 'VOLUNTEER'),   -- organizers are volunteers too (RL11)
    ('00000000-0000-4000-8000-000000000003', 'ORGANIZER'),
    ('00000000-0000-4000-8000-000000000003', 'VOLUNTEER'),
    ('00000000-0000-4000-8000-000000000004', 'STUDENT'),
    ('00000000-0000-4000-8000-000000000004', 'VOLUNTEER'),
    ('00000000-0000-4000-8000-000000000005', 'STUDENT'),
    ('00000000-0000-4000-8000-000000000006', 'STUDENT')
) AS ur(user_id, role_name)
JOIN role r ON r.name = ur.role_name
ON CONFLICT DO NOTHING;

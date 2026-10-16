-- REVIEW-LATER RL11 (owner, 2026-10-16): every organizer is also a volunteer. New role changes add VOLUNTEER in
-- UserAdminService (RoleName.withImplied); this gives it to the organizers that already exist.
INSERT INTO user_role (user_id, role_id)
SELECT ur.user_id, v.id
FROM user_role ur
JOIN role o ON o.id = ur.role_id AND o.name = 'ORGANIZER'
CROSS JOIN role v
WHERE v.name = 'VOLUNTEER'
ON CONFLICT DO NOTHING;

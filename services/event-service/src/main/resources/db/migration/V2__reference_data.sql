-- Reference data present in every environment. Placeholder names; the owner may swap in real campus ones
-- (in a new migration once this one has been applied anywhere that matters).

INSERT INTO category (code, name) VALUES
    ('TECHNICAL', 'Technical'),
    ('CULTURAL',  'Cultural'),
    ('SPORTS',    'Sports'),
    ('WORKSHOP',  'Workshop'),
    ('SEMINAR',   'Seminar'),
    ('CLUB',      'Club activity');

INSERT INTO venue (name, building, capacity, map_url, created_at, created_by, updated_at, updated_by) VALUES
    ('Main Auditorium',       'Academic Block 1', 1200, NULL, now(), 'system', now(), 'system'),
    ('Library Seminar Hall',  'Central Library',   200, NULL, now(), 'system', now(), 'system'),
    ('Innovation Centre Lab', 'Innovation Centre', 120, NULL, now(), 'system', now(), 'system'),
    ('Sports Complex',        'Sports Complex',   2000, NULL, now(), 'system', now(), 'system'),
    ('Open Air Theatre',      'Student Plaza',    3000, NULL, now(), 'system', now(), 'system');

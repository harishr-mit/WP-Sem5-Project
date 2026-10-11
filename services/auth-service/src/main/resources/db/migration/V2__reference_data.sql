-- Reference data. Departments are placeholders approved by the owner (docs/plans/phase-2.md §15); change them in a new
-- migration, never by editing this one.

INSERT INTO role (id, name) VALUES
    (1, 'STUDENT'),
    (2, 'ORGANIZER'),
    (3, 'VOLUNTEER'),
    (4, 'ADMIN');

INSERT INTO department (code, name) VALUES
    ('CSE',  'Computer Science and Engineering'),
    ('IT',   'Information Technology'),
    ('ECE',  'Electronics and Communication Engineering'),
    ('EIE',  'Electronics and Instrumentation Engineering'),
    ('MECH', 'Mechanical Engineering'),
    ('AERO', 'Aeronautical Engineering'),
    ('AUTO', 'Automobile Engineering'),
    ('PROD', 'Production Technology'),
    ('RPT',  'Rubber and Plastics Technology'),
    ('ROBO', 'Robotics and Automation'),
    ('AIDS', 'Artificial Intelligence and Data Science');

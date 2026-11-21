-- Demo data for the dev profile only (never loaded in tests or production).
-- Repeatable migration: Flyway runs it after the versioned ones, so it never blocks new V* files.
-- Every insert is idempotent so re-running it does not duplicate rows.
-- All demo users share the public password "Password123" (BCrypt, strength 10).

INSERT INTO users (id, email, password_hash, full_name, role)
VALUES ('00000000-0000-0000-0000-000000000001', 'admin@ticketing.dev',
        '$2a$10$p7vrilYCbOZUHkJ5SMgDx.m9b2lKVJJl7pa7S6nn3KWvbRaMNwbhq', 'Demo Admin', 'ADMIN'),
       ('00000000-0000-0000-0000-000000000002', 'organizer@ticketing.dev',
        '$2a$10$p7vrilYCbOZUHkJ5SMgDx.m9b2lKVJJl7pa7S6nn3KWvbRaMNwbhq', 'Demo Organizer', 'ORGANIZER'),
       ('00000000-0000-0000-0000-000000000003', 'user@ticketing.dev',
        '$2a$10$p7vrilYCbOZUHkJ5SMgDx.m9b2lKVJJl7pa7S6nn3KWvbRaMNwbhq', 'Demo User', 'USER')
ON CONFLICT (id) DO NOTHING;

INSERT INTO venues (id, name, city, address, capacity)
VALUES ('10000000-0000-0000-0000-000000000001', 'Estadio Central', 'Lima', 'Av. Principal 100', 40000),
       ('10000000-0000-0000-0000-000000000002', 'Teatro Municipal', 'Bogota', 'Calle 10 # 5-20', 1200)
ON CONFLICT (id) DO NOTHING;

INSERT INTO events (id, organizer_id, venue_id, title, description, category, starts_at, ends_at, status)
VALUES ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002',
        '10000000-0000-0000-0000-000000000001', 'Rock en el Parque', 'Festival de rock al aire libre',
        'MUSIC', now() + INTERVAL '60 days', now() + INTERVAL '60 days 6 hours', 'PUBLISHED'),
       ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002',
        '10000000-0000-0000-0000-000000000002', 'Hamlet', 'Obra clasica en dos actos',
        'THEATER', now() + INTERVAL '90 days', now() + INTERVAL '90 days 3 hours', 'DRAFT')
ON CONFLICT (id) DO NOTHING;

INSERT INTO ticket_types (id, event_id, name, price, total_quantity, available_quantity,
                          sale_starts_at, sale_ends_at, max_per_order)
VALUES ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
        'General', 45.00, 1000, 1000, now() - INTERVAL '1 day', now() + INTERVAL '59 days', 6),
       ('30000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001',
        'VIP', 150.00, 100, 100, now() - INTERVAL '1 day', now() + INTERVAL '59 days', 4),
       ('30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000002',
        'Platea', 60.00, 300, 300, now() - INTERVAL '1 day', now() + INTERVAL '89 days', 4)
ON CONFLICT (id) DO NOTHING;

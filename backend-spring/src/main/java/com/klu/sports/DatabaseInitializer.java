package com.klu.sports;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements CommandLineRunner {
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    @Value("${app.admin-name}") private String adminName;
    @Value("${app.admin-email}") private String adminEmail;
    @Value("${app.admin-password-reset:false}") private boolean adminPasswordReset;
    @Value("${app.admin-password}") private String adminPassword;

    public DatabaseInitializer(JdbcTemplate db) { this.db = db; }

    @Override public void run(String... args) {
        execute("CREATE TABLE IF NOT EXISTS users (id BIGSERIAL PRIMARY KEY, name TEXT NOT NULL, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'STUDENT' CHECK (role IN ('STUDENT','COORDINATOR','ADMIN')), student_id TEXT UNIQUE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
        execute("CREATE TABLE IF NOT EXISTS events (id BIGSERIAL PRIMARY KEY, title TEXT NOT NULL, sport TEXT NOT NULL, type TEXT NOT NULL DEFAULT 'Tournament', event_date TEXT NOT NULL, event_time TEXT NOT NULL DEFAULT '09:00 AM', venue TEXT NOT NULL, deadline TEXT NOT NULL, capacity INTEGER NOT NULL DEFAULT 30 CHECK (capacity > 0), spots INTEGER NOT NULL DEFAULT 30 CHECK (spots >= 0), status TEXT NOT NULL DEFAULT 'Open', color TEXT NOT NULL DEFAULT 'green', icon TEXT NOT NULL DEFAULT '🏆', description TEXT NOT NULL DEFAULT '', created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
        execute("CREATE TABLE IF NOT EXISTS registrations (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE, event_id BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE, status TEXT NOT NULL DEFAULT 'Registered', attendance TEXT NOT NULL DEFAULT 'Not marked', created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE(user_id,event_id))");
        execute("CREATE TABLE IF NOT EXISTS results (id BIGSERIAL PRIMARY KEY, event_id BIGINT REFERENCES events(id) ON DELETE SET NULL, title TEXT NOT NULL, details TEXT NOT NULL DEFAULT '', published_by BIGINT REFERENCES users(id) ON DELETE SET NULL, published_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
        execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS gender TEXT");
        execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS sport TEXT");
        execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS coordinator_id TEXT");
        execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS department TEXT");
        execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS account_status TEXT NOT NULL DEFAULT 'ACTIVE'");
        execute("ALTER TABLE registrations ADD COLUMN IF NOT EXISTS department TEXT");
        execute("ALTER TABLE registrations ADD COLUMN IF NOT EXISTS specialization TEXT");
        execute("ALTER TABLE registrations ADD COLUMN IF NOT EXISTS phone TEXT");
        execute("ALTER TABLE registrations ADD COLUMN IF NOT EXISTS year_of_study TEXT");
        execute("ALTER TABLE registrations ADD COLUMN IF NOT EXISTS gender TEXT");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS one_coordinator_per_sport ON users(sport) WHERE role='COORDINATOR' AND sport IS NOT NULL");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS one_coordinator_id ON users(coordinator_id) WHERE coordinator_id IS NOT NULL");
        execute("CREATE TABLE IF NOT EXISTS coordinator_requests (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE, requested_sport TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')), admin_reviewer_id BIGINT REFERENCES users(id) ON DELETE SET NULL, reviewed_at TIMESTAMPTZ, rejection_reason TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
        execute("ALTER TABLE coordinator_requests ADD COLUMN IF NOT EXISTS user_id BIGINT");
        execute("ALTER TABLE coordinator_requests ADD COLUMN IF NOT EXISTS requested_sport TEXT");
        execute("ALTER TABLE coordinator_requests ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'PENDING'");
        execute("ALTER TABLE coordinator_requests ADD COLUMN IF NOT EXISTS admin_reviewer_id BIGINT");
        execute("ALTER TABLE coordinator_requests ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ");
        execute("ALTER TABLE coordinator_requests ADD COLUMN IF NOT EXISTS rejection_reason TEXT");
        execute("ALTER TABLE coordinator_requests ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()");
        execute("DO $$ DECLARE column_record RECORD; BEGIN FOR column_record IN SELECT column_name FROM information_schema.columns WHERE table_name='coordinator_requests' AND column_name NOT IN ('id','user_id','requested_sport','status','admin_reviewer_id','reviewed_at','rejection_reason','created_at') LOOP EXECUTE format('ALTER TABLE coordinator_requests ALTER COLUMN %I DROP NOT NULL', column_record.column_name); END LOOP; END $$");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS coordinator_requests_user_id_key ON coordinator_requests(user_id) WHERE user_id IS NOT NULL");
        if (adminEmail != null && !adminEmail.isBlank() && adminPassword != null && !adminPassword.isBlank()) {
            String normalizedEmail = adminEmail.toLowerCase().trim();
            Integer count = db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?", Integer.class, normalizedEmail);
            if (count != null && count == 0) {
                db.update("INSERT INTO users(name,email,password_hash,role) VALUES(?,?,?,'ADMIN')", adminName, normalizedEmail, encoder.encode(adminPassword));
            } else if (adminPasswordReset) {
                db.update("UPDATE users SET password_hash=?, role='ADMIN', account_status='ACTIVE' WHERE email=? AND role='ADMIN'", encoder.encode(adminPassword), normalizedEmail);
                System.out.println("Configured administrator password was reset.");
            }
        }
        System.out.println("PostgreSQL schema is ready.");
    }

    private void execute(String sql) { db.execute(sql); }
}

import express from 'express'
import dotenv from 'dotenv'
import pg from 'pg'
import bcrypt from 'bcryptjs'
import jwt from 'jsonwebtoken'
import { events as seedEvents } from '../src/data/events.js'

dotenv.config()
const { Pool } = pg
const app = express()
const PORT = Number(process.env.API_PORT || 4000)
const JWT_SECRET = process.env.JWT_SECRET
if (!process.env.DATABASE_URL) console.warn('DATABASE_URL is not set. Copy .env.example to .env and configure PostgreSQL.')
if (!JWT_SECRET || JWT_SECRET.startsWith('replace-this')) { console.error('JWT_SECRET is missing or still uses the example value. Edit .env and set a long private random secret.'); process.exit(1) }
if (process.env.ADMIN_PASSWORD === 'ChangeThisAdminPassword123!') console.warn('IMPORTANT: Change ADMIN_PASSWORD in .env before using the portal.')
const pool = new Pool({ connectionString: process.env.DATABASE_URL || 'postgresql://postgres:postgres@localhost:5432/SPORTS' })
app.use(express.json({ limit: '1mb' }))

const asyncRoute = fn => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next)
const publicUser = row => ({ id: row.id, name: row.name, email: row.email, role: row.role, studentId: row.student_id || null, gender: row.gender || null, createdAt: row.created_at })
const eventFromRow = row => ({
  id: row.id, title: row.title, sport: row.sport, type: row.type, date: row.event_date, time: row.event_time,
  venue: row.venue, deadline: row.deadline, spots: row.spots, capacity: row.capacity, status: row.status,
  color: row.color, icon: row.icon, description: row.description, registeredCount: row.registered_count ?? Math.max(0, row.capacity-row.spots)
})

async function initializeDatabase() {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS users (
      id BIGSERIAL PRIMARY KEY, name TEXT NOT NULL, email TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'STUDENT' CHECK (role IN ('STUDENT','COORDINATOR','ADMIN')),
      student_id TEXT UNIQUE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    CREATE TABLE IF NOT EXISTS events (
      id BIGSERIAL PRIMARY KEY, title TEXT NOT NULL, sport TEXT NOT NULL, type TEXT NOT NULL DEFAULT 'Tournament',
      event_date TEXT NOT NULL, event_time TEXT NOT NULL DEFAULT '09:00 AM', venue TEXT NOT NULL, deadline TEXT NOT NULL,
      capacity INTEGER NOT NULL DEFAULT 30 CHECK (capacity > 0), spots INTEGER NOT NULL DEFAULT 30 CHECK (spots >= 0),
      status TEXT NOT NULL DEFAULT 'Open', color TEXT NOT NULL DEFAULT 'green', icon TEXT NOT NULL DEFAULT '🏆',
      description TEXT NOT NULL DEFAULT '', created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    CREATE TABLE IF NOT EXISTS registrations (
      id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      event_id BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
      status TEXT NOT NULL DEFAULT 'Registered', attendance TEXT NOT NULL DEFAULT 'Not marked',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE(user_id,event_id)
    );
    CREATE TABLE IF NOT EXISTS results (
      id BIGSERIAL PRIMARY KEY, event_id BIGINT REFERENCES events(id) ON DELETE SET NULL,
      title TEXT NOT NULL, details TEXT NOT NULL DEFAULT '', published_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
      published_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
  `)
  await pool.query(`
    ALTER TABLE users ADD COLUMN IF NOT EXISTS gender TEXT;
    ALTER TABLE registrations ADD COLUMN IF NOT EXISTS department TEXT;
    ALTER TABLE registrations ADD COLUMN IF NOT EXISTS specialization TEXT;
    ALTER TABLE registrations ADD COLUMN IF NOT EXISTS phone TEXT;
    ALTER TABLE registrations ADD COLUMN IF NOT EXISTS year_of_study TEXT;
    ALTER TABLE registrations ADD COLUMN IF NOT EXISTS gender TEXT;
  `)
  const count = await pool.query('SELECT COUNT(*)::int AS count FROM events')
  if (count.rows[0].count === 0) {
    for (const e of seedEvents) {
      await pool.query(`INSERT INTO events (id,title,sport,type,event_date,event_time,venue,deadline,capacity,spots,status,color,icon,description)
        VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)`,
      [e.id,e.title,e.sport,e.type,e.date,e.time,e.venue,e.deadline,e.capacity,e.spots,e.status,e.color,e.icon,e.description])
    }
    await pool.query("SELECT setval(pg_get_serial_sequence('events','id'), GREATEST((SELECT MAX(id) FROM events),1))")
  }
  if (process.env.ADMIN_EMAIL && process.env.ADMIN_PASSWORD) {
    const email = process.env.ADMIN_EMAIL.toLowerCase().trim()
    const exists = await pool.query('SELECT id FROM users WHERE email=$1', [email])
    if (!exists.rowCount) {
      const hash = await bcrypt.hash(process.env.ADMIN_PASSWORD, 12)
      await pool.query('INSERT INTO users(name,email,password_hash,role) VALUES($1,$2,$3,$4)', [process.env.ADMIN_NAME || 'Sports Portal Admin',email,hash,'ADMIN'])
      console.log(`Initial admin account created for ${email}`)
    }
  }
  console.log('PostgreSQL schema is ready.')
}

function authenticate(req, res, next) {
  const header = req.headers.authorization || ''
  const token = header.startsWith('Bearer ') ? header.slice(7) : ''
  if (!token) return res.status(401).json({ message: 'Please log in to continue.' })
  try { req.user = jwt.verify(token, JWT_SECRET) ; next() }
  catch { return res.status(401).json({ message: 'Your session is invalid or expired. Please log in again.' }) }
}
const allow = (...roles) => (req,res,next) => {
  if (!req.user) return res.status(401).json({ message: 'Please log in to continue.' })
  if (!roles.includes(req.user.role)) return res.status(403).json({ message: 'You do not have permission to perform this action.' })
  next()
}
function issueToken(row) {
  return jwt.sign({ id: Number(row.id), name: row.name, email: row.email, role: row.role, studentId: row.student_id || null }, JWT_SECRET, { expiresIn: '8h' })
}

app.get('/api/health', asyncRoute(async (_req,res) => {
  const result = await pool.query('SELECT current_database() AS database')
  res.json({ status:'ok', application:'KLU Sports Management Portal', database:result.rows[0].database, timestamp:new Date().toISOString() })
}))

app.post('/api/auth/signup', asyncRoute(async (req,res) => {
  const { name, email, password, studentId, gender } = req.body || {}
  if (!name?.trim() || !studentId?.trim() || !gender?.trim() || !email?.trim() || !password || password.length < 8) return res.status(400).json({ message:'Full name, student ID, gender, email, and a password of at least 8 characters are required.' })
  if (!['Female','Male','Non-binary','Prefer not to say'].includes(gender)) return res.status(400).json({ message:'Choose a valid gender.' })
  const cleanEmail = email.toLowerCase().trim()
  const hash = await bcrypt.hash(password,12)
  try {
    const result = await pool.query('INSERT INTO users(name,email,password_hash,role,student_id,gender) VALUES($1,$2,$3,\'STUDENT\',$4,$5) RETURNING *', [name.trim(),cleanEmail,hash,studentId.trim(),gender])
    const user = publicUser(result.rows[0])
    res.status(201).json({ user, token:issueToken(result.rows[0]) })
  } catch (err) {
    if (err.code === '23505') return res.status(409).json({ message: err.constraint?.includes('student_id') ? 'That student ID is already registered.' : 'An account with this email already exists.' })
    throw err
  }
}))
app.post('/api/auth/login', asyncRoute(async (req,res) => {
  const { email,password } = req.body || {}
  if (!email || !password) return res.status(400).json({ message:'Enter your email and password.' })
  const result = await pool.query('SELECT * FROM users WHERE email=$1', [String(email).toLowerCase().trim()])
  if (!result.rowCount || !(await bcrypt.compare(password,result.rows[0].password_hash))) return res.status(401).json({ message:'Incorrect email or password.' })
  res.json({ user:publicUser(result.rows[0]), token:issueToken(result.rows[0]) })
}))
app.get('/api/auth/me', authenticate, asyncRoute(async (req,res) => {
  const result = await pool.query('SELECT * FROM users WHERE id=$1', [req.user.id])
  if (!result.rowCount) return res.status(401).json({message:'Account not found.'})
  res.json({user:publicUser(result.rows[0])})
}))

app.get('/api/events', asyncRoute(async (_req,res) => {
  const result = await pool.query(`SELECT e.*, COUNT(r.id)::int AS registered_count FROM events e LEFT JOIN registrations r ON r.event_id=e.id GROUP BY e.id ORDER BY e.id`)
  res.json(result.rows.map(eventFromRow))
}))
app.post('/api/events', authenticate, allow('ADMIN','COORDINATOR'), asyncRoute(async (req,res) => {
  const b=req.body||{}
  if (!b.title?.trim() || !b.sport?.trim() || !b.date?.trim() || !b.venue?.trim()) return res.status(400).json({message:'Title, sport, date, and venue are required.'})
  const capacity=Math.max(1,Number(b.capacity)||30)
  const icons={Badminton:'🏸',Cricket:'🏏',Basketball:'🏀',Football:'⚽',Volleyball:'🏐',Athletics:'🏃'}
  const q=await pool.query(`INSERT INTO events(title,sport,type,event_date,event_time,venue,deadline,capacity,spots,status,color,icon,description)
    VALUES($1,$2,$3,$4,$5,$6,$7,$8,$8,'Open',$9,$10,$11) RETURNING *,0::int AS registered_count`,
  [b.title.trim(),b.sport.trim(),b.type||'Tournament',b.date,b.time||'09:00 AM',b.venue.trim(),b.deadline||b.date,capacity,b.color||'green',b.icon||icons[b.sport]||'🏆',b.description||''])
  res.status(201).json(eventFromRow(q.rows[0]))
}))
app.put('/api/events/:id', authenticate, allow('ADMIN','COORDINATOR'), asyncRoute(async (req,res) => {
  const b=req.body||{}
  const existing=await pool.query('SELECT * FROM events WHERE id=$1',[req.params.id])
  if(!existing.rowCount) return res.status(404).json({message:'Event not found.'})
  const old=existing.rows[0], capacity=Math.max(1,Number(b.capacity)||old.capacity)
  const registrations=await pool.query('SELECT COUNT(*)::int AS n FROM registrations WHERE event_id=$1',[req.params.id])
  if(capacity<registrations.rows[0].n) return res.status(409).json({message:'Capacity cannot be lower than current registrations.'})
  const q=await pool.query(`UPDATE events SET title=$1,sport=$2,type=$3,event_date=$4,event_time=$5,venue=$6,deadline=$7,capacity=$8,spots=$9,status=$10,description=$11 WHERE id=$12 RETURNING *,0::int AS registered_count`,
    [b.title??old.title,b.sport??old.sport,b.type??old.type,b.date??old.event_date,b.time??old.event_time,b.venue??old.venue,b.deadline??old.deadline,capacity,capacity-registrations.rows[0].n,capacity-registrations.rows[0].n===0?'Full':'Open',b.description??old.description,req.params.id])
  res.json(eventFromRow(q.rows[0]))
}))
app.delete('/api/events/:id', authenticate, allow('ADMIN','COORDINATOR'), asyncRoute(async (req,res) => {
  const q=await pool.query('DELETE FROM events WHERE id=$1 RETURNING id',[req.params.id])
  if(!q.rowCount) return res.status(404).json({message:'Event not found.'})
  res.status(204).end()
}))

app.get('/api/registrations', authenticate, asyncRoute(async (req,res) => {
  const values=[]; let where=''
  if(req.user.role==='STUDENT'){ values.push(req.user.id); where='WHERE r.user_id=$1' }
  else if(req.query.eventId){ values.push(req.query.eventId); where='WHERE r.event_id=$1' }
  const q=await pool.query(`SELECT r.id,r.user_id,r.event_id,r.status,r.attendance,r.created_at,r.department,r.specialization,r.phone,r.year_of_study,r.gender,u.name AS student_name,u.email,u.student_id,e.title AS event_title,e.sport
    FROM registrations r JOIN users u ON u.id=r.user_id JOIN events e ON e.id=r.event_id ${where} ORDER BY r.created_at DESC`,values)
  res.json(q.rows.map(r=>({id:Number(r.id),userId:Number(r.user_id),studentId:r.student_id||String(r.user_id),studentName:r.student_name,email:r.email,gender:r.gender,eventId:Number(r.event_id),eventTitle:r.event_title,sport:r.sport,department:r.department,specialization:r.specialization,phone:r.phone,yearOfStudy:r.year_of_study,status:r.status,attendance:r.attendance,createdAt:r.created_at})))
}))
app.post('/api/registrations', authenticate, allow('STUDENT'), asyncRoute(async (req,res) => {
  const eventId=Number(req.body?.eventId)
  const { department, specialization, phone, yearOfStudy }=req.body||{}
  if(!eventId) return res.status(400).json({message:'Choose an event to register.'})
  if(!department?.trim()||!specialization?.trim()||!yearOfStudy?.trim()||!phone?.trim()) return res.status(400).json({message:'Department, specialization, phone number, and year of study are required.'})
  if(!['1st Year','2nd Year','3rd Year','4th Year'].includes(yearOfStudy)) return res.status(400).json({message:'Choose a valid year of study.'})
  const normalizedPhone=String(phone).replace(/[\s-]/g,'')
  if(!/^(?:\+91|91)?[6-9]\d{9}$/.test(normalizedPhone)) return res.status(400).json({message:'Enter a valid 10-digit Indian mobile number.'})
  const client=await pool.connect()
  try {
    await client.query('BEGIN')
    const ev=await client.query('SELECT * FROM events WHERE id=$1 FOR UPDATE',[eventId])
    if(!ev.rowCount){await client.query('ROLLBACK');return res.status(404).json({message:'Event not found.'})}
    if(ev.rows[0].spots<=0 || ev.rows[0].status==='Full'){await client.query('ROLLBACK');return res.status(409).json({message:'This event is full.'})}
    const student=await client.query('SELECT gender FROM users WHERE id=$1',[req.user.id])
    const ins=await client.query(`INSERT INTO registrations(user_id,event_id,department,specialization,phone,year_of_study,gender) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING *`,[req.user.id,eventId,department.trim(),specialization.trim(),normalizedPhone,yearOfStudy,student.rows[0]?.gender||null])
    await client.query("UPDATE events SET spots=spots-1,status=CASE WHEN spots-1<=0 THEN 'Full' ELSE 'Open' END WHERE id=$1",[eventId])
    await client.query('COMMIT')
    res.status(201).json({id:Number(ins.rows[0].id),userId:req.user.id,eventId,status:'Registered',attendance:'Not marked',createdAt:ins.rows[0].created_at})
  } catch(err) {
    await client.query('ROLLBACK')
    if(err.code==='23505') return res.status(409).json({message:'You are already registered for this event.'})
    throw err
  } finally {client.release()}
}))
app.patch('/api/registrations/:id/attendance', authenticate, allow('ADMIN','COORDINATOR'), asyncRoute(async (req,res) => {
  const attendance=['Present','Absent','Not marked'].includes(req.body?.attendance)?req.body.attendance:'Not marked'
  const q=await pool.query('UPDATE registrations SET attendance=$1 WHERE id=$2 RETURNING id,attendance,status',[attendance,req.params.id])
  if(!q.rowCount)return res.status(404).json({message:'Registration not found.'})
  res.json(q.rows[0])
}))
app.get('/api/results', asyncRoute(async (req,res) => {
  const q=await pool.query('SELECT r.id,r.event_id,e.title AS event_title,r.title,r.details,r.published_at,u.name AS published_by FROM results r LEFT JOIN events e ON e.id=r.event_id LEFT JOIN users u ON u.id=r.published_by ORDER BY r.published_at DESC')
  res.json(q.rows.map(r=>({id:Number(r.id),eventId:r.event_id?Number(r.event_id):null,eventTitle:r.event_title,title:r.title,details:r.details,publishedAt:r.published_at,publishedBy:r.published_by})))
}))
app.post('/api/results', authenticate, allow('ADMIN','COORDINATOR'), asyncRoute(async (req,res) => {
  const {eventId,title,details}=req.body||{}
  if(!title?.trim())return res.status(400).json({message:'A result title is required.'})
  const q=await pool.query('INSERT INTO results(event_id,title,details,published_by) VALUES($1,$2,$3,$4) RETURNING *',[eventId||null,title.trim(),details||'',req.user.id])
  res.status(201).json({id:Number(q.rows[0].id),eventId:q.rows[0].event_id?Number(q.rows[0].event_id):null,title:q.rows[0].title,details:q.rows[0].details,publishedAt:q.rows[0].published_at})
}))
app.get('/api/admin/summary', authenticate, allow('ADMIN','COORDINATOR'), asyncRoute(async (_req,res) => {
  const q=await pool.query(`SELECT (SELECT COUNT(*) FROM events)::int AS total_events,(SELECT COUNT(*) FROM registrations)::int AS total_registrations,(SELECT COUNT(*) FROM results)::int AS total_results,(SELECT COUNT(*) FROM registrations WHERE attendance<>'Not marked')::int AS attendance_marked,(SELECT COUNT(*) FROM users WHERE role='STUDENT')::int AS total_students,(SELECT COUNT(*) FROM users WHERE role='COORDINATOR')::int AS total_coordinators`)
  res.json({totalEvents:q.rows[0].total_events,totalRegistrations:q.rows[0].total_registrations,totalResults:q.rows[0].total_results,attendanceMarked:q.rows[0].attendance_marked,totalStudents:q.rows[0].total_students,totalCoordinators:q.rows[0].total_coordinators})
}))
app.get('/api/admin/users', authenticate, allow('ADMIN'), asyncRoute(async (_req,res)=>{
  const q=await pool.query('SELECT id,name,email,role,student_id,created_at FROM users ORDER BY created_at DESC')
  res.json(q.rows.map(publicUser))
}))
app.post('/api/admin/users', authenticate, allow('ADMIN'), asyncRoute(async (req,res)=>{
  const {name,email,password,role,studentId}=req.body||{}
  if(!name?.trim()||!email?.trim()||!password||password.length<8||!['COORDINATOR','ADMIN','STUDENT'].includes(role)) return res.status(400).json({message:'Enter name, email, a password of at least 8 characters, and a valid role.'})
  const hash=await bcrypt.hash(password,12)
  try {const q=await pool.query('INSERT INTO users(name,email,password_hash,role,student_id) VALUES($1,$2,$3,$4,$5) RETURNING *',[name.trim(),email.toLowerCase().trim(),hash,role,studentId?.trim()||null]);res.status(201).json({user:publicUser(q.rows[0])})}
  catch(err){if(err.code==='23505')return res.status(409).json({message:'That email or student ID is already in use.'});throw err}
}))
app.patch('/api/admin/users/:id/role', authenticate, allow('ADMIN'), asyncRoute(async (req,res)=>{
  if(!['STUDENT','COORDINATOR','ADMIN'].includes(req.body?.role))return res.status(400).json({message:'Invalid role.'})
  if(Number(req.params.id)===req.user.id && req.body.role!=='ADMIN')return res.status(400).json({message:'You cannot remove your own admin role.'})
  const q=await pool.query('UPDATE users SET role=$1 WHERE id=$2 RETURNING id,name,email,role,student_id,created_at',[req.body.role,req.params.id])
  if(!q.rowCount)return res.status(404).json({message:'User not found.'})
  res.json({user:publicUser(q.rows[0])})
}))

app.use((err,_req,res,_next)=>{
  console.error(err)
  if(err.code==='ECONNREFUSED')return res.status(503).json({message:'Cannot connect to PostgreSQL. Check that PostgreSQL is running and DATABASE_URL in .env is correct.'})
  if(err.code==='28P01'||err.code==='3D000')return res.status(503).json({message:'PostgreSQL authentication or database error. Check your database name, username, and password in .env.'})
  res.status(500).json({message:'Unexpected server error. Check the API terminal for details.'})
})

initializeDatabase().then(()=>app.listen(PORT,()=>console.log(`KLU Sports API running at http://localhost:${PORT}`))).catch(err=>{
  console.error('Could not start the API. Check PostgreSQL is running and .env settings are correct.\n',err.message)
  process.exit(1)
})

package com.klu.sports;

import com.klu.sports.security.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private static final List<String> SPORTS = List.of("Badminton", "Cricket", "Basketball", "Football", "Volleyball", "Athletics");
    private static final List<String> GENDERS = List.of("Female", "Male", "Non-binary", "Prefer not to say");
    private static final List<String> YEARS = List.of("1st Year", "2nd Year", "3rd Year", "4th Year");
    private final JdbcTemplate db;
    private final JwtService jwt;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    public ApiController(JdbcTemplate db, JwtService jwt) { this.db = db; this.jwt = jwt; }

    @GetMapping("/health") public Map<String,Object> health() {
        return Map.of("status", "ok", "application", "KLU Sports Management Portal", "database", db.queryForObject("SELECT current_database()", String.class), "timestamp", OffsetDateTime.now().toString());
    }

    @PostMapping("/auth/signup") public ResponseEntity<?> signup(@RequestBody Map<String,Object> b) {
        String name=s(b,"name"), email=s(b,"email"), password=s(b,"password"), studentId=s(b,"studentId"), gender=s(b,"gender");
        if (blank(name)||blank(studentId)||blank(gender)||blank(email)||blank(password)||password.length()<8) return error(BAD_REQUEST,"Full name, student ID, gender, email, and a password of at least 8 characters are required.");
        if (!GENDERS.contains(gender)) return error(BAD_REQUEST,"Choose a valid gender.");
        try {
            Map<String,Object> row=db.queryForMap("INSERT INTO users(name,email,password_hash,role,student_id,gender) VALUES(?,?,?,'STUDENT',?,?) RETURNING *",name,email.toLowerCase().trim(),passwords.encode(password),studentId.trim(),gender);
            return ResponseEntity.status(CREATED).body(Map.of("user",user(row),"token",token(row)));
        } catch (org.springframework.dao.DuplicateKeyException e) { return error(CONFLICT,"That email or student ID is already registered."); }
    }

    @Transactional
    @PostMapping("/auth/coordinator-signup") public ResponseEntity<?> coordinatorSignup(@RequestBody Map<String,Object> b) {
        String name=s(b,"name"), coordinatorId=s(b,"coordinatorId"), email=s(b,"email"), password=s(b,"password"), department=s(b,"department"), sport=s(b,"sport");
        if(blank(name)||blank(coordinatorId)||blank(email)||blank(password)||password.length()<8||blank(department)||!SPORTS.contains(sport)) return error(BAD_REQUEST,"All coordinator fields are required.");
        try { Map<String,Object> user=db.queryForMap("INSERT INTO users(name,email,password_hash,role,coordinator_id,department,account_status) VALUES(?,?,?,'STUDENT',?,?, 'PENDING') RETURNING id",name,email.toLowerCase().trim(),passwords.encode(password),coordinatorId.trim(),department.trim()); db.update("INSERT INTO coordinator_requests(user_id,requested_sport) VALUES(?,?)",user.get("id"),sport); return ResponseEntity.status(CREATED).body(Map.of("message","Your coordinator registration request has been submitted successfully. Your account will be activated after Admin approval.")); }
        catch(org.springframework.dao.DuplicateKeyException e){return error(CONFLICT,"That email or Coordinator ID is already registered.");}
    }

    @PostMapping("/auth/login") public ResponseEntity<?> login(@RequestBody Map<String,Object> b) {
        String email=s(b,"email"), password=s(b,"password");
        if(blank(email)||blank(password)) return error(BAD_REQUEST,"Enter your email and password.");
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM users WHERE email=?",email.toLowerCase().trim());
        if(rows.isEmpty()||!passwords.matches(password,s(rows.get(0),"password_hash"))) return error(UNAUTHORIZED,"Incorrect email or password.");
        String accountStatus=s(rows.get(0),"account_status","ACTIVE"); if(accountStatus.equals("PENDING"))return error(FORBIDDEN,"Your coordinator registration is pending Admin approval. You will be able to access the Coordinator Dashboard once your request is approved."); if(accountStatus.equals("REJECTED"))return error(FORBIDDEN,"Your coordinator registration request was not approved.");
        return ResponseEntity.ok(Map.of("user",user(rows.get(0)),"token",token(rows.get(0))));
    }

    @GetMapping("/auth/me") public Map<String,Object> me(HttpServletRequest req) { Map<String,Object> row=userRow(auth(req).id()); return Map.of("user",user(row),"token",token(row)); }

    @GetMapping("/events") public List<Map<String,Object>> events(HttpServletRequest req) {
        Auth a=auth(req); String sport=a.coordinatorSport();
        String sql="SELECT e.*, COUNT(r.id)::int registered_count FROM events e LEFT JOIN registrations r ON r.event_id=e.id "+(sport!=null?"WHERE e.sport=? ":"")+"GROUP BY e.id ORDER BY e.id";
        return db.queryForList(sql,sport==null?new Object[]{}:new Object[]{sport}).stream().map(this::event).toList();
    }

    @PostMapping("/events") public ResponseEntity<?> createEvent(@RequestBody Map<String,Object> b,HttpServletRequest req) {
        Auth a=auth(req); require(a,"ADMIN","COORDINATOR"); String sport=s(b,"sport");
        if(blank(s(b,"title"))||blank(sport)||blank(s(b,"date"))||blank(s(b,"venue"))) return error(BAD_REQUEST,"Title, sport, date, and venue are required.");
        if(!SPORTS.contains(sport)) return error(BAD_REQUEST,"Choose a valid sport.");
        checkSport(a,sport);
        int capacity=Math.max(1,n(b,"capacity",30)); String icon=Map.of("Badminton","🏸","Cricket","🏏","Basketball","🏀","Football","⚽","Volleyball","🏐","Athletics","🏃").getOrDefault(sport,"🏆");
        Map<String,Object> row=db.queryForMap("INSERT INTO events(title,sport,type,event_date,event_time,venue,deadline,capacity,spots,status,color,icon,description) VALUES(?,?,?,?,?,?,?, ?,?,'Open',?,?,?) RETURNING *,0::int registered_count",s(b,"title").trim(),sport,s(b,"type","Tournament"),s(b,"date"),s(b,"time","09:00 AM"),s(b,"venue").trim(),s(b,"deadline",s(b,"date")),capacity,capacity,s(b,"color","green"),s(b,"icon",icon),s(b,"description",""));
        return ResponseEntity.status(CREATED).body(event(row));
    }

    @DeleteMapping("/events/{id}") public ResponseEntity<?> deleteEvent(@PathVariable long id,HttpServletRequest req) { Auth a=auth(req); require(a,"ADMIN","COORDINATOR"); Map<String,Object> e=one("SELECT sport FROM events WHERE id=?",id); checkSport(a,s(e,"sport")); if(db.update("DELETE FROM events WHERE id=?",id)==0) throw notFound("Event not found."); return ResponseEntity.noContent().build(); }

    @PutMapping("/events/{id}") public Map<String,Object> updateEvent(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest req) {
        Auth a=auth(req); require(a,"ADMIN","COORDINATOR"); Map<String,Object> old=one("SELECT * FROM events WHERE id=?",id); checkSport(a,s(old,"sport")); String sport=s(b,"sport",s(old,"sport")); if(!SPORTS.contains(sport))throw new ResponseStatusException(BAD_REQUEST,"Choose a valid sport."); checkSport(a,sport); int capacity=Math.max(1,n(b,"capacity",(int)num(old,"capacity"))); int registered=count("SELECT COUNT(*) FROM registrations WHERE event_id=?",new Object[]{id}); if(capacity<registered)throw new ResponseStatusException(CONFLICT,"Capacity cannot be lower than current registrations.");
        String status=capacity-registered==0?"Full":"Open"; Map<String,Object> row=db.queryForMap("UPDATE events SET title=?,sport=?,type=?,event_date=?,event_time=?,venue=?,deadline=?,capacity=?,spots=?,status=?,description=? WHERE id=? RETURNING *,0::int registered_count",s(b,"title",s(old,"title")),sport,s(b,"type",s(old,"type")),s(b,"date",s(old,"event_date")),s(b,"time",s(old,"event_time")),s(b,"venue",s(old,"venue")),s(b,"deadline",s(old,"deadline")),capacity,capacity-registered,status,s(b,"description",s(old,"description")),id); return event(row);
    }

    @GetMapping("/registrations") public List<Map<String,Object>> registrations(HttpServletRequest req,@RequestParam(required=false) Long eventId) {
        Auth a=auth(req); List<Object> args=new ArrayList<>(); List<String> conditions=new ArrayList<>();
        if(a.role().equals("STUDENT")){args.add(a.id());conditions.add("r.user_id=?");} if(a.coordinatorSport()!=null){args.add(a.coordinatorSport());conditions.add("e.sport=?");} if(eventId!=null){args.add(eventId);conditions.add("r.event_id=?");}
        String where=conditions.isEmpty()?"":" WHERE "+String.join(" AND ",conditions);
        return db.queryForList("SELECT r.id,r.user_id,r.event_id,r.status,r.attendance,r.created_at,r.department,r.specialization,r.phone,r.year_of_study,r.gender,u.name student_name,u.email,u.student_id,e.title event_title,e.sport FROM registrations r JOIN users u ON u.id=r.user_id JOIN events e ON e.id=r.event_id"+where+" ORDER BY r.created_at DESC",args.toArray()).stream().map(this::registration).toList();
    }

    @PostMapping("/registrations") public ResponseEntity<?> createRegistration(@RequestBody Map<String,Object> b,HttpServletRequest req) {
        Auth a=auth(req); require(a,"STUDENT"); long eventId=longValue(b,"eventId",0); String department=s(b,"department"), specialization=s(b,"specialization"), phone=s(b,"phone"), year=s(b,"yearOfStudy");
        if(eventId==0||blank(department)||blank(specialization)||blank(phone)||blank(year))return error(BAD_REQUEST,"Department, specialization, phone number, and year of study are required."); if(!YEARS.contains(year)||!phone.replaceAll("[\\s-]","").matches("(?:\\+91|91)?[6-9]\\d{9}"))return error(BAD_REQUEST,"Enter valid registration details.");
        try { db.update("INSERT INTO registrations(user_id,event_id,department,specialization,phone,year_of_study,gender) SELECT ?,?,?,?,?,?,gender FROM users WHERE id=?",a.id(),eventId,department.trim(),specialization.trim(),phone.replaceAll("[\\s-]",""),year,a.id()); db.update("UPDATE events SET spots=spots-1,status=CASE WHEN spots-1<=0 THEN 'Full' ELSE 'Open' END WHERE id=? AND spots>0",eventId); return ResponseEntity.status(CREATED).body(Map.of("userId",a.id(),"eventId",eventId,"status","Registered","attendance","Not marked")); } catch(org.springframework.dao.DuplicateKeyException e){return error(CONFLICT,"You are already registered for this event.");}
    }

    @PatchMapping("/registrations/{id}/attendance") public Map<String,Object> attendance(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest req) { Auth a=auth(req); require(a,"ADMIN","COORDINATOR"); String value=s(b,"attendance","Not marked"); if(!List.of("Present","Absent","Not marked").contains(value)) value="Not marked"; String extra=a.coordinatorSport()!=null?" AND e.sport=?":""; List<Object> args=new ArrayList<>(List.of(value,id)); if(a.coordinatorSport()!=null)args.add(a.coordinatorSport()); List<Map<String,Object>> rows=db.queryForList("UPDATE registrations r SET attendance=? FROM events e WHERE r.event_id=e.id AND r.id=?"+extra+" RETURNING r.id,r.attendance,r.status",args.toArray()); if(rows.isEmpty())throw notFound("Registration not found."); return rows.get(0); }

    @GetMapping("/results") public List<Map<String,Object>> results(HttpServletRequest req) { Auth a=auth(req); if(a.coordinatorSport()!=null)return db.queryForList("SELECT r.id,r.event_id,e.title event_title,r.title,r.details,r.published_at,u.name published_by FROM results r LEFT JOIN events e ON e.id=r.event_id LEFT JOIN users u ON u.id=r.published_by WHERE e.sport=? ORDER BY r.published_at DESC",a.coordinatorSport()).stream().map(this::result).toList(); return db.queryForList("SELECT r.id,r.event_id,e.title event_title,r.title,r.details,r.published_at,u.name published_by FROM results r LEFT JOIN events e ON e.id=r.event_id LEFT JOIN users u ON u.id=r.published_by ORDER BY r.published_at DESC").stream().map(this::result).toList(); }

    @PostMapping("/results") public ResponseEntity<?> createResult(@RequestBody Map<String,Object> b,HttpServletRequest req){Auth a=auth(req);require(a,"ADMIN","COORDINATOR");String title=s(b,"title"),details=s(b,"details");Long eventId=b.get("eventId")==null||String.valueOf(b.get("eventId")).isBlank()?null:longValue(b,"eventId",0);if(blank(title))return error(BAD_REQUEST,"A result title is required.");if(a.coordinatorSport()!=null){if(eventId==null)return error(BAD_REQUEST,"Choose an event for your assigned sport.");checkSport(a,s(one("SELECT sport FROM events WHERE id=?",eventId),"sport"));}Map<String,Object> row=db.queryForMap("INSERT INTO results(event_id,title,details,published_by) VALUES(?,?,?,?) RETURNING *",eventId,title.trim(),details,a.id());return ResponseEntity.status(CREATED).body(result(row));}

    @GetMapping("/admin/summary") public Map<String,Object> summary(HttpServletRequest req) { Auth a=auth(req); require(a,"ADMIN","COORDINATOR"); String sport=a.coordinatorSport(); String e=sport==null?"":" WHERE sport=?"; String join=sport==null?"":" JOIN events e ON e.id=r.event_id WHERE e.sport=?"; Object[] one=sport==null?new Object[]{}:new Object[]{sport}; return Map.of("totalEvents",count("SELECT COUNT(*) FROM events"+e,one),"totalRegistrations",count("SELECT COUNT(*) FROM registrations r"+join,one),"totalResults",count("SELECT COUNT(*) FROM results r LEFT JOIN events e ON e.id=r.event_id"+join,one),"attendanceMarked",count("SELECT COUNT(*) FROM registrations r"+join+(join.isEmpty()?" WHERE ":" AND ")+"r.attendance<>'Not marked'",one),"totalStudents",count("SELECT COUNT(DISTINCT r.user_id) FROM registrations r"+join,one),"totalCoordinators",count("SELECT COUNT(*) FROM users WHERE role='COORDINATOR'",new Object[]{})); }

    @GetMapping("/admin/users") public List<Map<String,Object>> users(HttpServletRequest req){require(auth(req),"ADMIN");return db.queryForList("SELECT id,name,email,role,student_id,gender,sport,created_at FROM users ORDER BY created_at DESC").stream().map(this::user).toList();}

    @GetMapping("/admin/coordinator-requests") public List<Map<String,Object>> coordinatorRequests(HttpServletRequest req){require(auth(req),"ADMIN");return db.queryForList("SELECT cr.id,cr.user_id,cr.requested_sport,cr.status,cr.created_at,cr.reviewed_at,cr.rejection_reason,u.name,u.email,u.coordinator_id,u.department FROM coordinator_requests cr JOIN users u ON u.id=cr.user_id ORDER BY cr.created_at DESC").stream().map(r->map("id",num(r,"id"),"userId",num(r,"user_id"),"name",s(r,"name"),"email",s(r,"email"),"coordinatorId",s(r,"coordinator_id"),"department",s(r,"department"),"requestedSport",s(r,"requested_sport"),"status",s(r,"status"),"createdAt",r.get("created_at"),"reviewedAt",r.get("reviewed_at"),"rejectionReason",s(r,"rejection_reason",null))).toList();}

    @PatchMapping("/admin/coordinator-requests/{id}/approve") public Map<String,Object> approveCoordinator(@PathVariable long id,@RequestBody(required=false) Map<String,Object> b,HttpServletRequest req){Auth admin=auth(req);require(admin,"ADMIN");Map<String,Object> request=one("SELECT * FROM coordinator_requests WHERE id=?",id);if(!s(request,"status").equals("PENDING"))throw new ResponseStatusException(CONFLICT,"This coordinator request has already been reviewed.");String sport=b==null?null:s(b,"sport",null);if(blank(sport))sport=s(request,"requested_sport");if(!SPORTS.contains(sport))throw new ResponseStatusException(BAD_REQUEST,"Choose a valid sport.");try{db.update("UPDATE users SET role='COORDINATOR',account_status='ACTIVE',sport=? WHERE id=?",sport,num(request,"user_id"));db.update("UPDATE coordinator_requests SET status='APPROVED',requested_sport=?,admin_reviewer_id=?,reviewed_at=NOW() WHERE id=?",sport,admin.id(),id);return Map.of("message","Coordinator approved.");}catch(org.springframework.dao.DuplicateKeyException e){throw new ResponseStatusException(CONFLICT,"That sport already has a coordinator.");}}

    @PatchMapping("/admin/coordinator-requests/{id}/reject") public Map<String,Object> rejectCoordinator(@PathVariable long id,@RequestBody(required=false) Map<String,Object> b,HttpServletRequest req){Auth admin=auth(req);require(admin,"ADMIN");Map<String,Object> request=one("SELECT * FROM coordinator_requests WHERE id=?",id);if(!s(request,"status").equals("PENDING"))throw new ResponseStatusException(CONFLICT,"This coordinator request has already been reviewed.");db.update("UPDATE users SET role='STUDENT',account_status='REJECTED',sport=NULL WHERE id=?",num(request,"user_id"));db.update("UPDATE coordinator_requests SET status='REJECTED',admin_reviewer_id=?,reviewed_at=NOW(),rejection_reason=? WHERE id=?",admin.id(),b==null?null:s(b,"reason",null),id);return Map.of("message","Coordinator request rejected.");}

    @PostMapping("/admin/users") public ResponseEntity<?> createUser(@RequestBody Map<String,Object> b,HttpServletRequest req){require(auth(req),"ADMIN");String name=s(b,"name"),email=s(b,"email"),password=s(b,"password"),role=s(b,"role"),sport=s(b,"sport");if(blank(name)||blank(email)||blank(password)||password.length()<8||!List.of("STUDENT","COORDINATOR","ADMIN").contains(role))return error(BAD_REQUEST,"Enter valid account details.");if(role.equals("COORDINATOR")&&!SPORTS.contains(sport))return error(BAD_REQUEST,"Choose the coordinator's assigned sport.");try{Map<String,Object> row=db.queryForMap("INSERT INTO users(name,email,password_hash,role,student_id,sport) VALUES(?,?,?, ?,?,?) RETURNING *",name,email.toLowerCase().trim(),passwords.encode(password),role,s(b,"studentId",null),role.equals("COORDINATOR")?sport:null);return ResponseEntity.status(CREATED).body(Map.of("user",user(row)));}catch(org.springframework.dao.DuplicateKeyException e){return error(CONFLICT,"That email or student ID is already in use.");}}

    @PatchMapping("/admin/users/{id}/role") public Map<String,Object> updateRole(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest req){Auth a=auth(req);require(a,"ADMIN");String role=s(b,"role"),sport=s(b,"sport",null);if(!List.of("STUDENT","COORDINATOR","ADMIN").contains(role))throw new ResponseStatusException(BAD_REQUEST,"Invalid role.");if(role.equals("COORDINATOR")&&!SPORTS.contains(sport))throw new ResponseStatusException(BAD_REQUEST,"Choose the coordinator's assigned sport.");if(id==a.id()&&!role.equals("ADMIN"))throw new ResponseStatusException(BAD_REQUEST,"You cannot remove your own admin role.");Map<String,Object> row=one("UPDATE users SET role=?,sport=? WHERE id=? RETURNING *",role,role.equals("COORDINATOR")?sport:null,id);return Map.of("user",user(row));}

    @GetMapping(value="/registrations/export.pdf",produces="application/pdf") public ResponseEntity<byte[]> export(HttpServletRequest req){ List<Map<String,Object>> rows=registrations(req,null); StringBuilder text=new StringBuilder("KLU Sports Registration Entries\\n\\n"); for(int i=0;i<rows.size();i++){Map<String,Object> r=rows.get(i);text.append(i+1).append(". ").append(s(r,"studentName")).append(" | ").append(s(r,"eventTitle")).append(" | ").append(s(r,"sport")).append("\\n");} if(rows.isEmpty())text.append("No registration entries found."); byte[] pdf=pdf(text.toString()); return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=klu-sports-entries.pdf").contentType(MediaType.APPLICATION_PDF).body(pdf); }

    private Auth auth(HttpServletRequest req){try{String h=req.getHeader("Authorization");if(h==null||!h.startsWith("Bearer "))throw new ResponseStatusException(UNAUTHORIZED,"Please log in to continue.");Claims c=jwt.parse(h.substring(7));Map<String,Object> current=userRow(((Number)c.get("id")).longValue());if(!s(current,"account_status","ACTIVE").equals("ACTIVE"))throw new ResponseStatusException(FORBIDDEN,"This account is not active.");return new Auth(num(current,"id"),s(current,"role"),s(current,"sport",null));}catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(UNAUTHORIZED,"Your session is invalid or expired. Please log in again.");}}
    private void require(Auth a,String... roles){if(Arrays.stream(roles).noneMatch(a.role()::equals))throw new ResponseStatusException(FORBIDDEN,"You do not have permission to perform this action.");}
    private void checkSport(Auth a,String sport){if(a.role().equals("COORDINATOR")&&!Objects.equals(a.coordinatorSport(),sport))throw new ResponseStatusException(FORBIDDEN,"You can only manage data for your assigned sport.");}
    private Map<String,Object> userRow(long id){return one("SELECT * FROM users WHERE id=?",id);}
    private Map<String,Object> one(String sql,Object... args){List<Map<String,Object>> rows=db.queryForList(sql,args);if(rows.isEmpty())throw notFound("Record not found.");return rows.get(0);}
    private ResponseStatusException notFound(String message){return new ResponseStatusException(NOT_FOUND,message);}
    private ResponseEntity<Map<String,String>> error(org.springframework.http.HttpStatus status,String message){return ResponseEntity.status(status).body(Map.of("message",message));}
    private int count(String sql,Object[] args){return db.queryForObject(sql,Integer.class,args);}
    private String token(Map<String,Object> r){return jwt.issue(map("id",num(r,"id"),"name",s(r,"name"),"email",s(r,"email"),"role",s(r,"role"),"studentId",s(r,"student_id",null),"sport",s(r,"sport",null),"accountStatus",s(r,"account_status","ACTIVE")));}
    private Map<String,Object> user(Map<String,Object> r){return map("id",num(r,"id"),"name",s(r,"name"),"email",s(r,"email"),"role",s(r,"role"),"studentId",s(r,"student_id",null),"coordinatorId",s(r,"coordinator_id",null),"department",s(r,"department",null),"gender",s(r,"gender",null),"sport",s(r,"sport",null),"accountStatus",s(r,"account_status","ACTIVE"),"createdAt",r.get("created_at"));}
    private Map<String,Object> event(Map<String,Object> r){return map("id",num(r,"id"),"title",s(r,"title"),"sport",s(r,"sport"),"type",s(r,"type"),"date",s(r,"event_date"),"time",s(r,"event_time"),"venue",s(r,"venue"),"deadline",s(r,"deadline"),"spots",num(r,"spots"),"capacity",num(r,"capacity"),"status",s(r,"status"),"color",s(r,"color"),"icon",s(r,"icon"),"description",s(r,"description"),"registeredCount",num(r,"registered_count"));}
    private Map<String,Object> registration(Map<String,Object> r){return map("id",num(r,"id"),"userId",num(r,"user_id"),"studentId",s(r,"student_id",String.valueOf(num(r,"user_id"))),"studentName",s(r,"student_name"),"email",s(r,"email"),"gender",s(r,"gender",null),"eventId",num(r,"event_id"),"eventTitle",s(r,"event_title"),"sport",s(r,"sport"),"department",s(r,"department",null),"specialization",s(r,"specialization",null),"phone",s(r,"phone",null),"yearOfStudy",s(r,"year_of_study",null),"status",s(r,"status"),"attendance",s(r,"attendance"),"createdAt",r.get("created_at"));}
    private Map<String,Object> result(Map<String,Object> r){return map("id",num(r,"id"),"eventId",r.get("event_id"),"eventTitle",s(r,"event_title",null),"title",s(r,"title"),"details",s(r,"details"),"publishedAt",r.get("published_at"),"publishedBy",s(r,"published_by",null));}
    private String s(Map<?,?> m,String k){return s(m,k,"");} private String s(Map<?,?> m,String k,String d){Object v=m.get(k);return v==null?d:String.valueOf(v);} private String s(Claims c,String k){Object v=c.get(k);return v==null?null:String.valueOf(v);} private boolean blank(String s){return s==null||s.isBlank();} private int n(Map<String,Object> m,String k,int d){try{return Integer.parseInt(s(m,k,String.valueOf(d)));}catch(Exception e){return d;}} private long num(Map<?,?> m,String k){Object v=m.get(k);return v instanceof Number n?n.longValue():Long.parseLong(String.valueOf(v));} private long longValue(Map<?,?> m,String k,long d){try{return Long.parseLong(String.valueOf(m.containsKey(k)?m.get(k):d));}catch(Exception e){return d;}}
    private record Auth(long id,String role,String coordinatorSport){}
    private Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]);return result;}

    private byte[] pdf(String text){String stream="BT /F1 11 Tf 40 800 Td ("+text.replace("\\","\\\\").replace("(","\\(").replace(")","\\)").replace("\\n", ") Tj 0 -16 Td (")+ ") Tj ET";String[] objects={"<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>","<< /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>","<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>","<< /Length "+stream.getBytes(StandardCharsets.US_ASCII).length+" >>\\nstream\\n"+stream+"\\nendstream"};StringBuilder out=new StringBuilder("%PDF-1.4\\n");List<Integer> offsets=new ArrayList<>();for(int i=0;i<objects.length;i++){offsets.add(out.length());out.append(i+1).append(" 0 obj\\n").append(objects[i]).append("\\nendobj\\n");}int x=out.length();out.append("xref\\n0 ").append(objects.length+1).append("\\n0000000000 65535 f \\n");for(int o:offsets)out.append(String.format("%010d 00000 n \\n",o));out.append("trailer << /Size ").append(objects.length+1).append(" /Root 1 0 R >>\\nstartxref\\n").append(x).append("\\n%%EOF");return out.toString().getBytes(StandardCharsets.US_ASCII);}
}

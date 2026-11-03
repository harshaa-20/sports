import { Activity, LogOut, ShieldCheck, X } from 'lucide-react'

const sports = [
  ['Cricket', '🏏'],
  ['Football', '⚽'],
  ['Badminton', '🏸'],
  ['Basketball', '🏀'],
  ['Volleyball', '🏐'],
]

export default function Sidebar({ user, roles, navigation, currentPage, eventCount, mobileOpen, onNavigate, onSport, onLogout, onClose }) {
  return (
    <aside className={`portal-sidebar ${mobileOpen ? 'open' : ''}`}>
      <div className="portal-brand">
        <div className="brand-icon"><Activity size={23} /></div>
        <div><strong>KLU<span>SPORTS</span></strong><small>CAMPUS ATHLETICS</small></div>
        <button type="button" className="mobile-close" onClick={onClose} aria-label="Close menu"><X /></button>
      </div>
      <div className="sidebar-scroll">
        <div className="side-caption">WORKSPACE</div>
        <nav className="portal-nav" aria-label={`${roles[user.role]} navigation`}>
          {navigation.map(({ label, page, Icon, download, active }) => (
            <button type="button" key={`${label}-${page}`} className={currentPage === page && active !== false ? 'selected' : ''} onClick={() => onNavigate(page, download)}>
              <Icon size={18} /><span>{label}</span>{page === 'Explore events' && <b>{eventCount}</b>}
            </button>
          ))}
        </nav>
        <div className="side-caption sport-caption">DISCOVER SPORTS</div>
        <div className="sport-shortcuts">
          {sports.map(([sport, icon]) => <button type="button" key={sport} onClick={() => onSport(sport)}><span>{icon}</span>{sport}</button>)}
        </div>
      </div>
      <div className="sidebar-bottom">
        <div className="sidebar-note"><ShieldCheck size={18} /><strong>One portal. Every opportunity.</strong><p>Sports events, registrations and results in one place.</p></div>
        <div className="profile-row">
          <div className="avatar">{user.name.split(/\s+/).filter(Boolean).slice(0, 2).map(part => part[0]).join('').toUpperCase()}</div>
          <div className="profile-text"><strong>{user.name}</strong><small>{roles[user.role]}</small></div>
          <button type="button" className="sidebar-logout" onClick={onLogout} aria-label="Logout"><LogOut size={17} /><span>Logout</span></button>
        </div>
      </div>
    </aside>
  )
}

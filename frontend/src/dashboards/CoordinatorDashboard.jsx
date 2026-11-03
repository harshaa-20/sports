import { CalendarDays, ClipboardList, LayoutDashboard, Trophy, UserRound } from 'lucide-react'

export const coordinatorDashboard = {
  role: 'COORDINATOR',
  eyebrow: 'SPORTS OPERATIONS',
  nav: [
    { label: 'Overview', page: 'Overview', Icon: LayoutDashboard },
    { label: 'Manage Events', page: 'Manage events', Icon: CalendarDays },
    { label: 'Applicants', page: 'Registrations & attendance', Icon: ClipboardList },
    { label: 'Download Applicants', page: 'Registrations & attendance', Icon: ClipboardList, download: true, active: false },
    { label: 'Profile', page: 'Profile', Icon: UserRound },
    { label: 'Results', page: 'Results', Icon: Trophy },
  ],
}

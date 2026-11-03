import { CalendarDays, ClipboardList, LayoutDashboard, Trophy, Users, UserRound, BarChart3 } from 'lucide-react'

export const adminDashboard = {
  role: 'ADMIN',
  eyebrow: 'ADMINISTRATION',
  nav: [
    { label: 'Overview', page: 'Overview', Icon: LayoutDashboard },
    { label: 'Manage Sports', page: 'Manage events', Icon: Trophy, active: false },
    { label: 'Manage Events', page: 'Manage events', Icon: CalendarDays },
    { label: 'Student Management', page: 'User management', Icon: Users },
    { label: 'Coordinator Requests', page: 'Coordinator Requests', Icon: Users },
    { label: 'Manage Coordinators', page: 'User management', Icon: UserRound },
    { label: 'All Registrations', page: 'Registrations & attendance', Icon: ClipboardList },
    { label: 'Reports', page: 'Results', Icon: BarChart3 },
  ],
}

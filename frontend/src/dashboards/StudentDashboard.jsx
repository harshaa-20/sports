import { CalendarDays, ClipboardList, LayoutDashboard, Trophy, UserRound } from 'lucide-react'

export const studentDashboard = {
  role: 'STUDENT',
  eyebrow: 'YOUR CAMPUS. YOUR GAME.',
  nav: [
    { label: 'Overview', page: 'Overview', Icon: LayoutDashboard },
    { label: 'Sports Events', page: 'Explore events', Icon: CalendarDays },
    { label: 'My Registrations', page: 'My registrations', Icon: ClipboardList },
    { label: 'Profile', page: 'Profile', Icon: UserRound },
  ],
}

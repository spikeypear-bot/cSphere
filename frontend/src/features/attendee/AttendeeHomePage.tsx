import { Link } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import './AttendeePages.css'

export function AttendeeHomePage() {
  return <main className="page">
    <PageHeader eyebrow="Attendee" title="Welcome" description="Find event details and get ready for what’s coming up." />
    <Card className="attendee-home-card">
      <div>
        <p className="attendee-home-card__eyebrow">Your events</p>
        <h2>Make plans with confidence.</h2>
        <p>Browse confirmed events for the latest date, venue and accessibility details.</p>
      </div>
      <Link className="button button--primary" to="/attendee/browse">Browse events<span aria-hidden="true">→</span></Link>
    </Card>
  </main>
}

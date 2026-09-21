import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { TextField } from '../../components/ui/fields'
import { useSession, type Role } from '../../lib/sessionContext'
import './RoleSelectPage.css'

interface RoleOption {
  role: Role
  title: string
  verb: string
  accentVar: string
  destination: string
  /** Only Event Organiser asks for an organisation name — that's the only
   * role with real, data-scoped functionality behind it so far (EO01/EO02/
   * EO15). The other four roles have skeleton-only consoles (no backend
   * calls yet), so there's nothing yet to scope by organisation/ownership —
   * see docs/decision-log.md D6a. */
  needsOrganisation: boolean
}

// One card per role, each naming the single thing that role owns — the
// research behind this page (docs/product-context.md) found that role-select
// screens read fastest when a card states what it *does*, not just who it's
// for. Every role now routes somewhere real: Event Organiser has working
// pages (EO01/EO02/EO15); the rest open a skeleton console — see
// docs/implementation-roadmap.md for what's built vs. still to come.
const ROLE_OPTIONS: RoleOption[] = [
  {
    role: 'organiser',
    title: 'Event Organiser',
    verb: 'Request and track your events',
    accentVar: '--role-organiser',
    destination: '/organiser',
    needsOrganisation: true,
  },
  {
    role: 'coordinator',
    title: 'Event Coordinator',
    verb: 'Review requests and confirm events',
    accentVar: '--role-coordinator',
    destination: '/coordinator',
    needsOrganisation: false,
  },
  {
    role: 'venue-staff',
    title: 'Venue Staff',
    verb: 'Maintain venues and approve bookings',
    accentVar: '--role-venue',
    destination: '/venue-staff',
    needsOrganisation: false,
  },
  {
    role: 'technical-support',
    title: 'Technical Support Staff',
    verb: 'Manage equipment and reservations',
    accentVar: '--role-technical',
    destination: '/technical-support',
    needsOrganisation: false,
  },
  {
    role: 'attendee',
    title: 'Attendee',
    verb: 'Register for and attend events',
    accentVar: '--role-attendee',
    destination: '/attendee',
    needsOrganisation: false,
  },
]

export function RoleSelectPage() {
  const { loginAs } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const [pendingRole, setPendingRole] = useState<RoleOption | null>(null)
  const [organisationInput, setOrganisationInput] = useState('')
  const deniedPath = new URLSearchParams(location.search).get('access-denied')

  function handleSelect(option: RoleOption) {
    if (!option.needsOrganisation) {
      loginAs(option.role)
      navigate(option.destination)
      return
    }
    setPendingRole(option)
  }

  function confirmOrganisation() {
    if (!pendingRole || !organisationInput.trim()) return
    loginAs(pendingRole.role, organisationInput.trim())
    navigate(pendingRole.destination)
  }

  return (
    <div className="role-select">
      <div className="role-select__intro">
        <h1>Who's working today?</h1>
        <p>Pick your role to open ConnectSphere's console for it.</p>
      </div>

      {deniedPath ? (
        <Card className="role-select__access-denied" role="alert">
          <h2>Access denied</h2>
          <p>You do not have permission to access <code>{deniedPath}</code> with your current role. Choose the appropriate role to continue.</p>
        </Card>
      ) : null}

      <div className="role-select__grid">
        {ROLE_OPTIONS.map((option) => (
          <Card
            key={option.role}
            className="role-card"
            style={{ ['--role-accent' as string]: `var(${option.accentVar})` }}
          >
            <button
              type="button"
              className="role-card__button"
              onClick={() => handleSelect(option)}
            >
              <span className="role-card__mark" aria-hidden="true" />
              <h2>{option.title}</h2>
              <p>{option.verb}</p>
              <span className="role-card__cta">Login as {option.title} →</span>
            </button>
          </Card>
        ))}
      </div>

      {pendingRole ? (
        <div className="role-select__overlay" role="dialog" aria-label="Enter your organisation">
          <Card className="role-select__prompt">
            <h2>Which organisation are you with?</h2>
            <p className="field-hint">
              ConnectSphere keeps every organisation's event requests separate — this is how we
              know which ones are yours.
            </p>
            <TextField
              id="organisation-name"
              label="Organisation name"
              value={organisationInput}
              onChange={setOrganisationInput}
              placeholder="e.g. Acme Conferences Pte Ltd"
            />
            <div className="role-select__prompt-actions">
              <Button variant="secondary" onClick={() => setPendingRole(null)}>
                Back
              </Button>
              <Button onClick={confirmOrganisation} disabled={!organisationInput.trim()}>
                Continue
              </Button>
            </div>
          </Card>
        </div>
      ) : null}
    </div>
  )
}

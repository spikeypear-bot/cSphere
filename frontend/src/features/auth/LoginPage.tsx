import { useState, type FormEvent } from 'react'
import { useLocation } from 'react-router-dom'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { useSession } from '../../lib/sessionContext'
import './LoginPage.css'

/**
 * AU02. Replaces D6a's "Login as [Role]" selector: the role is now whatever
 * the verified account says it is, so there is nothing for the person to
 * choose — they identify themselves and the backend decides what that means.
 *
 * Redirecting after a successful login is App's job, not this page's: the
 * session changes, the route guard re-runs, and the console appears. Doing it
 * here with a navigate() would double up with that and race it.
 */
export function LoginPage() {
  const { login } = useSession()
  const location = useLocation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const deniedPath = new URLSearchParams(location.search).get('access-denied')

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (submitting) return
    setSubmitting(true)
    setError(null)
    try {
      await login(username.trim(), password)
    } catch (caught) {
      // The backend answers identically for a wrong password, an unknown
      // username and a blank field, so that this form cannot be used to
      // discover which accounts exist (D19). Show what it said verbatim
      // rather than inventing a more specific message here.
      setError(caught instanceof Error ? caught.message : 'Could not sign in.')
      setPassword('')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="login">
      <Card className="login__card">
        <h1>Sign in to ConnectSphere</h1>
        <p className="login__intro">
          Use your ConnectSphere account. What you can see and do is set by your
          account's role.
        </p>

        {deniedPath ? (
          <p className="login__notice" role="status">
            Please sign in to open <code>{deniedPath}</code>.
          </p>
        ) : null}

        <form onSubmit={handleSubmit} noValidate>
          <div className="field">
            <label htmlFor="login-username">Username</label>
            <input
              id="login-username"
              name="username"
              autoComplete="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="login-password">Password</label>
            <input
              id="login-password"
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </div>

          {error ? (
            <p className="login__error" role="alert">
              {error}
            </p>
          ) : null}

          <Button type="submit" disabled={submitting || !username.trim() || !password}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>
      </Card>
    </div>
  )
}

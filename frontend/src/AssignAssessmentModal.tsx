import { useMemo, useState } from 'react'
import { ArrowRight, X } from 'lucide-react'
import type { FormEvent } from 'react'
import type { Assessment } from './api'
import { api, errorMessage } from './api'
import './AssignAssessmentModal.css'

type CandidateResult = { email: string; fullName: string; status: 'ASSIGNED' | 'ALREADY_ASSIGNED' | 'FAILED'; accountCreated: boolean; message: string }
type AssignResponse = { assigned: number; alreadyAssigned: number; failed: number; candidates: CandidateResult[] }

export default function AssignAssessmentModal({ assessments, initialAssessmentId, onClose, onAssigned }: {
  assessments: Assessment[]
  initialAssessmentId?: string
  onClose: () => void
  onAssigned?: () => void
}) {
  const [assessmentId, setAssessmentId] = useState(initialAssessmentId ?? '')
  const [emailText, setEmailText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<AssignResponse | null>(null)
  const availableAssessments = useMemo(() => assessments.filter(a => a.status === 'PUBLISHED'), [assessments])
  const emails = useMemo(() => [...new Set(emailText.split(/[\s,;]+/).map(email => email.trim().toLowerCase()).filter(Boolean))], [emailText])

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError('')
    setResult(null)
    if (!assessmentId) { setError('Choose a published assessment.'); return }
    if (!emails.length) { setError('Enter at least one candidate email.'); return }
    const invalid = emails.find(email => !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
    if (invalid) { setError(`“${invalid}” is not a valid email address.`); return }
    setBusy(true)
    try {
      const base = new URL(api.defaults.baseURL ?? window.location.origin, window.location.origin).origin
      const { data } = await api.post<AssignResponse>(`${base}/api/recruiter/assessments/${assessmentId}/assign`, { candidateEmails: emails })
      setResult(data)
      setEmailText('')
      onAssigned?.()
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }

  return <div className="assignment-modal-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) onClose() }}>
    <section className="assignment-modal" role="dialog" aria-modal="true" aria-labelledby="assign-modal-title">
      <header><div><span className="eyebrow">CANDIDATE ACCESS</span><h2 id="assign-modal-title">Assign assessment</h2><p>Assign a published assessment to candidate email addresses.</p></div><button type="button" className="icon-button" aria-label="Close" onClick={onClose}><X size={17}/></button></header>
      <form onSubmit={submit}>
        <label>Assessment<select value={assessmentId} onChange={event => setAssessmentId(event.target.value)} required>
          <option value="">Select an assessment</option>
          {availableAssessments.map(assessment => <option key={assessment.id} value={assessment.id}>{assessment.title}</option>)}
        </select></label>
        {!availableAssessments.length && <p className="assign-modal-hint">Create and publish an assessment first; it will appear here.</p>}
        <label>Candidate emails<textarea rows={4} value={emailText} onChange={event => setEmailText(event.target.value)} placeholder="alex@example.com, sara@example.com"/><small>Separate multiple emails with commas, spaces, or new lines.</small></label>
        {emails.length > 0 && <div className="assign-email-chips" aria-label="Emails to assign">{emails.map(email => <span key={email}>{email}</span>)}</div>}
        {error && <div className="alert error">{error}</div>}
        {result && <div className="alert success">Successfully assigned assessment to {result.assigned + result.alreadyAssigned} candidate(s).{result.failed > 0 && ` ${result.failed} could not be assigned.`}
          <ul>{result.candidates.filter(candidate => candidate.status !== 'FAILED').map(candidate => <li key={candidate.email}>{candidate.email}: {candidate.message}</li>)}</ul>
        </div>}
        <footer><button type="button" className="button ghost" onClick={onClose}>Cancel</button><button className="button primary" type="submit" disabled={busy || !availableAssessments.length}>{busy ? 'Assigning…' : 'Assign assessment'} <ArrowRight size={15}/></button></footer>
      </form>
    </section>
  </div>
}

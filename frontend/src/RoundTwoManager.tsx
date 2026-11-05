import { useEffect, useState } from 'react'
import { FileText, Plus } from 'lucide-react'
import { api, errorMessage } from './api'

type RoundQuestion = { id: string; subject: string; questionText: string; optionA: string; optionB: string; optionC: string; optionD: string; correctOption: string }
type InterviewQuestion = { id: string; subject: string; questionText: string; position: number }
const empty = { subject: '', questionText: '', optionA: '', optionB: '', optionC: '', optionD: '', correctOption: 'A' }
const emptyInterview = { subject: '', questionText: '' }
const operatingSystemsExample = {
  subject: 'Operating Systems',
  questionText: 'Which OS component selects the next ready process to run on the CPU?',
  optionA: 'The memory manager',
  optionB: 'The file system',
  optionC: 'The CPU scheduler',
  optionD: 'The device driver',
  correctOption: 'C',
}

export default function RoundTwoManager({ assessmentId, onCountChange, readOnly = false }: { assessmentId: string; onCountChange?: (count: number) => void; readOnly?: boolean }) {
  const [items, setItems] = useState<RoundQuestion[]>([])
  const [interviewItems, setInterviewItems] = useState<InterviewQuestion[]>([])
  const [form, setForm] = useState(operatingSystemsExample)
  const [interviewForm, setInterviewForm] = useState(emptyInterview)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [interviewError, setInterviewError] = useState('')
  const [interviewBusy, setInterviewBusy] = useState(false)
  async function refresh() {
    try { const { data } = await api.get<RoundQuestion[]>(`/assessments/${assessmentId}/round-two/questions`); setItems(data); onCountChange?.(data.length) }
    catch (e) { setError(errorMessage(e)) }
  }
  async function refreshInterview() {
    try { const { data } = await api.get<InterviewQuestion[]>(`/assessments/${assessmentId}/round-two/interview-questions`); setInterviewItems(data) }
    catch (e) { setInterviewError(errorMessage(e)) }
  }
  useEffect(() => { refresh(); refreshInterview() }, [assessmentId])
  async function add(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setError('')
    try { await api.post(`/assessments/${assessmentId}/round-two/questions`, form); setForm(empty); await refresh() }
    catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  async function addInterviewQuestion(e: React.FormEvent) {
    e.preventDefault(); setInterviewBusy(true); setInterviewError('')
    try { await api.post(`/assessments/${assessmentId}/round-two/interview-questions`, interviewForm); setInterviewForm(emptyInterview); await refreshInterview() }
    catch (e) { setInterviewError(errorMessage(e)) }
    finally { setInterviewBusy(false) }
  }
  function field(key: keyof typeof empty, label: string, area = false) {
    return <label>{label}{area ? <textarea required maxLength={20000} rows={3} value={form[key]} onChange={e => setForm({ ...form, [key]: e.target.value })}/> : <input required maxLength={key === 'subject' ? 120 : 1000} value={form[key]} onChange={e => setForm({ ...form, [key]: e.target.value })}/>}</label>
  }
  return <section className="panel round-two-manager">
    <div className="form-section-head"><span className="section-icon mint-bg"><FileText/></span><div><h2>Round 2 · Subject MCQs</h2><p>These appear one at a time after all coding and interview steps. The candidate’s timer continues.</p></div></div>
    {items.map((q, i) => <div className="question-added" key={q.id}><span>{String(i + 1).padStart(2, '0')}</span><div><strong>{q.subject} · {q.questionText}</strong><small>A. {q.optionA} · B. {q.optionB} · C. {q.optionC} · D. {q.optionD}</small></div><span className="badge neutral">Key {q.correctOption}</span></div>)}
    {!items.length && <div className="subtle-empty">Add at least one MCQ to publish this assessment.</div>}
    {!readOnly && <form className="question-form" onSubmit={add}><h3>Add subject question</h3><small className="help">An editable Operating Systems example is prefilled. Change it or load it again with the sample button.</small>{field('subject', 'Subject (e.g. Operating Systems)')}{field('questionText', 'Question', true)}<div className="two-fields">{field('optionA', 'Option A')}{field('optionB', 'Option B')}</div><div className="two-fields">{field('optionC', 'Option C')}{field('optionD', 'Option D')}</div><label>Correct option<select value={form.correctOption} onChange={e => setForm({ ...form, correctOption: e.target.value })}><option>A</option><option>B</option><option>C</option><option>D</option></select></label><div className="form-actions"><button type="button" className="button ghost" onClick={() => setForm(operatingSystemsExample)}>Load OS sample</button><button className="button secondary" disabled={busy}>{busy ? 'Adding…' : 'Add Round 2 question'} <Plus size={15}/></button></div></form>}
    {error && <div className="alert error">{error}</div>}
    <div className="form-section-head interview-question-manager-head"><span className="section-icon mint-bg"><FileText/></span><div><h2>Round 2 · AI interview prompts</h2><p>These open-ended questions are asked aloud after the subject MCQs. If you add none, CogniHire selects fallback questions for the assessment role.</p></div></div>
    {interviewItems.map((q,i) => <div className="question-added" key={q.id}><span>{String(i+1).padStart(2,'0')}</span><div><strong>{q.subject} · {q.questionText}</strong><small>Asked during the live AI interview</small></div></div>)}
    {!interviewItems.length && <div className="subtle-empty">No custom interview prompts yet. Role-based fallback questions will be used.</div>}
    {!readOnly && <form className="question-form" onSubmit={addInterviewQuestion}><h3>Add AI interview prompt</h3><label>Subject<input required maxLength={120} value={interviewForm.subject} onChange={e => setInterviewForm({ ...interviewForm, subject: e.target.value })} placeholder="e.g. Databases"/></label><label>Open-ended question<textarea required maxLength={4000} rows={3} value={interviewForm.questionText} onChange={e => setInterviewForm({ ...interviewForm, questionText: e.target.value })} placeholder="Ask the candidate to explain their reasoning, trade-offs, or experience."/></label><div className="form-actions"><button type="submit" className="button secondary" disabled={interviewBusy || interviewItems.length >= 20}>{interviewBusy ? 'Adding…' : 'Add interview prompt'} <Plus size={15}/></button><small>{interviewItems.length}/20 prompts</small></div></form>}
    {interviewError && <div className="alert error">{interviewError}</div>}
  </section>
}

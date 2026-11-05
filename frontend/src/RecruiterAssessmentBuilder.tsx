import { useMemo, useState } from 'react'
import { ArrowLeft, ArrowRight, Check, FileText, Github, Plus, Sparkles, Trash2, Users, X } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { api, errorMessage } from './api'
import './RecruiterAssessmentBuilder.css'

type Subject = 'DBMS' | 'Operating Systems' | 'OOPs' | 'Computer Networks'
type Mcq = { subject: Subject; questionText: string; optionA: string; optionB: string; optionC: string; optionD: string; correctOption: 'A'|'B'|'C'|'D' }
const generalBank = [
  'How would you find a pair of numbers in an array that adds up to a target? Explain the complexity.',
  'How would you detect a cycle in a linked list, and what are the trade-offs between common approaches?',
  'When would you choose a hash map over a balanced search tree?',
  'Explain how you would find the lowest common ancestor in a binary tree.',
  'How would you find the shortest path in an unweighted graph?',
  'Describe a strategy for merging overlapping intervals and its time complexity.',
  'How would you design an LRU cache? Which data structures would you combine?',
  'Explain the difference between breadth-first and depth-first search, with use cases.',
  'How would you find the kth largest element without sorting the entire array?',
  'What edge cases would you test when reversing a string or array in place?',
  'How do you reason about time and space complexity for a nested-loop algorithm?',
  'Describe how you would break a large problem into smaller, testable steps.'
]
const mcqBank: Mcq[] = [
  { subject:'DBMS', questionText:'Which ACID property ensures a transaction is all-or-nothing?', optionA:'Atomicity', optionB:'Consistency', optionC:'Isolation', optionD:'Durability', correctOption:'A' },
  { subject:'DBMS', questionText:'What is the main purpose of a database index?', optionA:'Encrypt table data', optionB:'Speed up data retrieval', optionC:'Prevent all duplicate values', optionD:'Replace transactions', correctOption:'B' },
  { subject:'DBMS', questionText:'Which normal form removes partial dependency on a composite key?', optionA:'1NF', optionB:'2NF', optionC:'3NF', optionD:'BCNF only', correctOption:'B' },
  { subject:'Operating Systems', questionText:'Which component selects the next ready process for the CPU?', optionA:'Memory manager', optionB:'File system', optionC:'CPU scheduler', optionD:'Device driver', correctOption:'C' },
  { subject:'Operating Systems', questionText:'What is a key difference between a process and a thread?', optionA:'Threads always have separate address spaces', optionB:'Processes share one stack', optionC:'Threads in a process share its address space', optionD:'Processes cannot run concurrently', correctOption:'C' },
  { subject:'Operating Systems', questionText:'Which condition is necessary for deadlock?', optionA:'Circular wait', optionB:'Compilation', optionC:'Paging', optionD:'Context-free grammar', correctOption:'A' },
  { subject:'OOPs', questionText:'What does polymorphism allow?', optionA:'One interface with different implementations', optionB:'A class to have no methods', optionC:'Objects to avoid encapsulation', optionD:'A method to access private fields everywhere', correctOption:'A' },
  { subject:'OOPs', questionText:'Which concept hides internal state and exposes controlled operations?', optionA:'Inheritance', optionB:'Encapsulation', optionC:'Overloading', optionD:'Composition only', correctOption:'B' },
  { subject:'OOPs', questionText:'What is abstraction primarily used for?', optionA:'Expose implementation details', optionB:'Represent essential behavior while hiding details', optionC:'Prevent object creation', optionD:'Convert runtime types to strings', correctOption:'B' },
  { subject:'Computer Networks', questionText:'Which transport protocol provides ordered, reliable delivery?', optionA:'UDP', optionB:'IP', optionC:'TCP', optionD:'ARP', correctOption:'C' },
  { subject:'Computer Networks', questionText:'What does DNS primarily do?', optionA:'Maps domain names to IP addresses', optionB:'Encrypts disk sectors', optionC:'Routes packets within one switch', optionD:'Allocates process memory', correctOption:'A' },
  { subject:'Computer Networks', questionText:'Which HTTP method is generally used to retrieve a resource?', optionA:'POST', optionB:'GET', optionC:'PATCH', optionD:'DELETE', correctOption:'B' }
]
const subjects: Subject[] = ['DBMS','Operating Systems','OOPs','Computer Networks']

export default function RecruiterAssessmentBuilder({ onSaved }: { onSaved: () => void }) {
  const navigate = useNavigate()
  const [title, setTitle] = useState('Backend Engineer Coding Screen')
  const [role, setRole] = useState('Backend Engineer')
  const [description, setDescription] = useState('Solve the coding problem, explain your reasoning, and answer the selected core CS questions.')
  const [durationMinutes, setDurationMinutes] = useState(60)
  const [passingScore, setPassingScore] = useState(60)
  const [generalSelected, setGeneralSelected] = useState<string[]>([])
  const [customDraft, setCustomDraft] = useState('')
  const [activeSubject, setActiveSubject] = useState<Subject>('DBMS')
  const [mcqSelected, setMcqSelected] = useState<Mcq[]>([])
  const [githubEnabled, setGithubEnabled] = useState(false)
  const [candidateEmails, setCandidateEmails] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const customQuestions = useMemo(() => generalSelected.filter(q => !generalBank.includes(q)), [generalSelected])

  function toggleGeneral(question: string) {
    setGeneralSelected(current => current.includes(question) ? current.filter(q => q !== question) : [...current, question])
  }
  function addCustom() {
    const text = customDraft.trim()
    if (!text || generalSelected.includes(text)) return
    if (generalSelected.length >= 20) { setError('Select up to 20 general and interview questions.'); return }
    setGeneralSelected(current => [...current, text]); setCustomDraft(''); setError('')
  }
  function toggleMcq(question: Mcq) {
    setMcqSelected(current => current.some(q => q.questionText === question.questionText && q.subject === question.subject)
      ? current.filter(q => !(q.questionText === question.questionText && q.subject === question.subject)) : [...current, question])
  }
  async function submit(event: React.FormEvent) {
    event.preventDefault(); setError(''); setNotice('')
    if (!mcqSelected.length) { setError('Select at least one Core CS subject MCQ. Candidates need a Round 2 question to complete the assessment.'); return }
    if (generalSelected.length + (githubEnabled ? 3 : 0) > 20) { setError('General and generated interview questions may total at most 20.'); return }
    const emails = [...new Set(candidateEmails.split(/[\s,;]+/).map(email => email.trim().toLowerCase()).filter(Boolean))]
    const invalidEmail = emails.find(email => !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
    if (invalidEmail) { setError(`“${invalidEmail}” is not a valid candidate email.`); return }
    setBusy(true)
    try {
      const base = new URL(api.defaults.baseURL ?? window.location.origin, window.location.origin).origin
      const { data } = await api.post(`${base}/api/recruiter/assessments/create`, {
        title: title.trim(), role: role.trim(), description: description.trim(), durationMinutes, passingScore,
        generalQuestions: generalSelected,
        subjectQuestions: mcqSelected,
        enableGithubAnalysis: githubEnabled
      })
      let builderNotice = `Assessment published. Candidate test code: ${data.testCode}.`
      if (emails.length) {
        try {
          const { data: assignment } = await api.post(`${base}/api/recruiter/assessments/${data.id}/assign`, { candidateEmails: emails })
          builderNotice = `Successfully assigned assessment to ${assignment.assigned + assignment.alreadyAssigned} candidate(s). Candidate test code: ${data.testCode}.`
          if (assignment.failed) builderNotice += ` ${assignment.failed} email(s) could not be assigned.`
        } catch (assignmentError) {
          builderNotice = `Assessment was published, but candidate assignment failed: ${errorMessage(assignmentError)}`
        }
      }
      onSaved()
      navigate(`/assessments/${data.id}`, { state: { builderNotice } })
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }

  return <div className="recruiter-builder">
    <div className="page-heading"><div><div className="eyebrow">ASSESSMENTS / NEW</div><h1>Build an assessment</h1><p className="muted">Choose problem-solving prompts and core CS questions. Candidates can provide their own GitHub repository when they begin.</p></div><Link className="button ghost" to="/assessments"><X size={16}/> Cancel</Link></div>
    <form onSubmit={submit}>
      <section className="panel rb-section rb-basics">
        <div className="rb-section-title"><span className="section-icon"><FileText/></span><div><span className="eyebrow">ASSESSMENT SETUP</span><h2>General details</h2></div></div>
        <div className="rb-basics-grid"><label>Assessment title<input required maxLength={180} value={title} onChange={e => setTitle(e.target.value)} placeholder="e.g. Backend Engineer Screen"/></label><label>Target role<input required maxLength={160} value={role} onChange={e => setRole(e.target.value)} placeholder="e.g. Backend Engineer"/></label><label className="rb-description">Candidate instructions<textarea rows={3} maxLength={10000} value={description} onChange={e => setDescription(e.target.value)}/></label><label>Time limit (minutes)<input type="number" min={1} max={600} value={durationMinutes} onChange={e => setDurationMinutes(Number(e.target.value))}/></label><label>Passing score (%)<input type="number" min={0} max={100} value={passingScore} onChange={e => setPassingScore(Number(e.target.value))}/></label></div>
      </section>

      <section className="panel rb-section">
        <div className="rb-section-title"><span className="section-icon mint-bg"><Sparkles/></span><div><span className="eyebrow">SECTION 1</span><h2>General & problem-solving</h2><p>Pick curated prompts and add your own. These become open-ended interview questions.</p></div><span className="rb-count">{generalSelected.length} selected</span></div>
        <div className="rb-chip-grid">{generalBank.map((question, index) => <label className={`rb-choice ${generalSelected.includes(question) ? 'selected' : ''}`} key={question}><input type="checkbox" checked={generalSelected.includes(question)} onChange={() => toggleGeneral(question)}/><span className="rb-choice-check">{generalSelected.includes(question) ? <Check size={14}/> : index + 1}</span><span>{question}</span></label>)}</div>
        <div className="rb-custom"><label htmlFor="rb-custom-question">Write a custom question</label><div className="rb-custom-line"><textarea id="rb-custom-question" rows={2} maxLength={4000} value={customDraft} onChange={e => setCustomDraft(e.target.value)} placeholder="Add a question about data structures, algorithms, or problem solving…"/><button type="button" className="button secondary" onClick={addCustom} disabled={!customDraft.trim() || generalSelected.length >= 20}><Plus size={15}/> Add question</button></div></div>
        {customQuestions.length > 0 && <div className="rb-custom-list"><strong>Your custom questions</strong>{customQuestions.map(question => <div className="rb-custom-item" key={question}><span>{question}</span><button type="button" aria-label="Remove custom question" onClick={() => toggleGeneral(question)}><Trash2 size={15}/></button></div>)}</div>}
      </section>

      <section className="panel rb-section">
        <div className="rb-section-title"><span className="section-icon mint-bg"><FileText/></span><div><span className="eyebrow">SECTION 2</span><h2>Core CS subjects</h2><p>Select multiple questions across subjects. Correct answers remain private from candidates.</p></div><span className="rb-count">{mcqSelected.length} selected</span></div>
        <div className="rb-subject-tabs" role="tablist" aria-label="Core CS subjects">{subjects.map(subject => <button type="button" role="tab" aria-selected={activeSubject === subject} className={activeSubject === subject ? 'active' : ''} key={subject} onClick={() => setActiveSubject(subject)}>{subject}<small>{mcqSelected.filter(q => q.subject === subject).length}</small></button>)}</div>
        <div className="rb-mcq-list">{mcqBank.filter(q => q.subject === activeSubject).map(question => {
          const selected = mcqSelected.some(q => q.subject === question.subject && q.questionText === question.questionText)
          return <label key={question.questionText} className={`rb-mcq ${selected ? 'selected' : ''}`}><input type="checkbox" checked={selected} onChange={() => toggleMcq(question)}/><span className="rb-choice-check">{selected && <Check size={14}/>}</span><span className="rb-mcq-copy"><strong>{question.questionText}</strong><small>A. {question.optionA} &nbsp; B. {question.optionB}<br/>C. {question.optionC} &nbsp; D. {question.optionD}</small></span></label>
        })}</div>
      </section>

      <section className="panel rb-section">
        <div className="rb-section-title"><span className="section-icon"><Github/></span><div><span className="eyebrow">SECTION 3</span><h2>Candidate GitHub personalization</h2><p>The candidate provides a public repository link at assessment start, so questions can be grounded in their own project.</p></div></div>
        <label className="toggle-row rb-github-toggle"><span><strong>Enable Candidate GitHub Repository Analysis</strong><small>Candidates will be asked for a repository URL before the assessment starts.</small></span><input type="checkbox" checked={githubEnabled} onChange={e => setGithubEnabled(e.target.checked)}/></label>
        {githubEnabled && <div className="rb-github-note"><Sparkles size={16}/> CogniHire reads public repository metadata and creates up to three candidate-specific interview questions. Recruiters do not enter a candidate’s repository here.</div>}
      </section>

      <section className="panel rb-section rb-assign-now">
        <div className="rb-section-title"><span className="section-icon mint-bg"><Users/></span><div><span className="eyebrow">OPTIONAL</span><h2>Assign to candidates now</h2><p>These candidates will get access as soon as the assessment is published.</p></div></div>
        <label>Candidate email addresses<textarea rows={3} maxLength={12000} value={candidateEmails} onChange={e => setCandidateEmails(e.target.value)} placeholder="alex@example.com, sara@example.com"/><small>Separate emails with commas, spaces, semicolons, or new lines. New candidate profiles can claim their account by creating a candidate login with the same email.</small></label>
      </section>

      {error && <div className="alert error">{error}</div>}
      {notice && <div className="alert success">{notice}</div>}
      <div className="rb-submit"><Link className="button ghost" to="/assessments"><ArrowLeft size={15}/> Back to assessments</Link><div><small>{generalSelected.length} interview · {mcqSelected.length} MCQ{mcqSelected.length === 1 ? '' : 's'}{githubEnabled ? ' · up to 3 project prompts' : ''} · Two Sum coding screen</small><button className="button primary" type="submit" disabled={busy}>{busy ? 'Publishing assessment…' : 'Create & publish assessment'} <ArrowRight size={16}/></button></div></div>
    </form>
  </div>
}

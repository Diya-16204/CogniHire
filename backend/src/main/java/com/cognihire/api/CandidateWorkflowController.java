package com.cognihire.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class CandidateWorkflowController {
  private final AssessmentRepository assessments; private final UserAccountRepository users; private final AssessmentAssignmentRepository assignments; private final AssessmentAttemptRepository attempts; private final CodingQuestionRepository questions; private final CodeSubmissionRepository submissions;
  private final CodeExecutionProvider execution;
  CandidateWorkflowController(AssessmentRepository assessments,UserAccountRepository users,AssessmentAssignmentRepository assignments,AssessmentAttemptRepository attempts,CodingQuestionRepository questions,CodeSubmissionRepository submissions,CodeExecutionProvider execution) { this.assessments=assessments;this.users=users;this.assignments=assignments;this.attempts=attempts;this.questions=questions;this.submissions=submissions;this.execution=execution; }
  private UUID userId(Authentication auth){return UUID.fromString(auth.getName());}
  private void requireRole(Authentication a,String role){if(!a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_"+role)||x.getAuthority().equals("ROLE_ADMIN")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
  record AssignRequest(@Email @NotBlank String email) {}
  record AssignmentView(String id,String title,String description,int durationMinutes,String status,Instant assignedAt,String attemptId) {}
  record StartView(String id,String status,Instant startedAt,Instant deadlineAt,Object policySnapshot,List<Object> questions) {}
  record SaveCodeRequest(@NotBlank @Size(max=100000) String sourceCode,@NotBlank String language) {}
  record SubmissionView(String id,String status,Instant createdAt) {}
  record DraftView(String sourceCode,String language,Instant savedAt) {}
  record RunRequest(@NotBlank @Size(max=100000) String sourceCode,@NotBlank String language) {}
  record RunResponse(String id,String status,String stdout,String stderr,String compileOutput,String message,Double time,Long memory) {}

  @PostMapping("/api/v1/assessments/{assessmentId}/assignments") @ResponseStatus(HttpStatus.CREATED) @Transactional
  AssignmentView assign(@PathVariable UUID assessmentId,@Valid @RequestBody AssignRequest req,Authentication auth){
    requireRole(auth,"RECRUITER"); var assessment=assessments.findById(assessmentId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    if(!assessment.owner.id.equals(userId(auth))&&!auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN")))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if(!assessment.status.equals("PUBLISHED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Publish the assessment before assigning candidates");
    var candidate=users.findByEmailIgnoreCase(req.email().trim()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Candidate account not found")); if(candidate.role!=Role.CANDIDATE)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The selected account is not a candidate");
    var assignment=assignments.findByAssessmentIdAndCandidateId(assessmentId,candidate.id).orElseGet(()->{var n=new AssessmentAssignment();n.assessment=assessment;n.candidate=candidate;return assignments.save(n);});
    return new AssignmentView(assignment.id.toString(),assessment.title,assessment.description,assessment.durationMinutes,assessment.status,assignment.assignedAt,null);
  }
  @GetMapping("/api/v1/candidate/assignments") List<AssignmentView> myAssignments(Authentication auth){
    requireRole(auth,"CANDIDATE"); return assignments.findByCandidateIdOrderByAssignedAtDesc(userId(auth)).stream().map(a->new AssignmentView(a.id.toString(),a.assessment.title,a.assessment.description,a.assessment.durationMinutes,a.assessment.status,a.assignedAt,attempts.findByAssignmentId(a.id).map(x->x.id.toString()).orElse(null))).toList();
  }
  @PostMapping("/api/v1/candidate/assignments/{assignmentId}/start") @Transactional(noRollbackFor=ResponseStatusException.class) StartView start(@PathVariable UUID assignmentId,Authentication auth){
    requireRole(auth,"CANDIDATE"); var assignment=assignments.findById(assignmentId).filter(x->x.candidate.id.equals(userId(auth))).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    if(!assignment.assessment.status.equals("PUBLISHED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"This assessment is not available");
    var attempt=attempts.findByAssignmentId(assignmentId).orElseGet(()->{
      var a=new AssessmentAttempt(); a.assignment=assignment;a.candidate=assignment.candidate;a.deadlineAt=Instant.now().plusSeconds(assignment.assessment.durationMinutes*60L);
      a.policySnapshot=Map.of("enabled",assignment.assessment.aiEnabled,"allowedCategories",assignment.assessment.allowedAssistance,"maxHintsPerAttempt",assignment.assessment.maxHintsPerAttempt,"maxPenalty",assignment.assessment.maxPenalty,"version",assignment.assessment.version);
      a.questionSnapshot=questions.findByAssessmentIdOrderByPosition(assignment.assessment.id).stream().map(q->(Object)Map.<String,Object>of("id",q.id.toString(),"title",q.title,"statement",q.statement,"difficulty",q.difficulty,"points",q.points,"position",q.position,"publicSamples",q.publicSamples)).toList();
      return attempts.save(a);
    });
    if(!attempt.status.equals("IN_PROGRESS"))throw new ResponseStatusException(HttpStatus.CONFLICT,"This attempt has already ended");
    if(Instant.now().isAfter(attempt.deadlineAt)){attempt.status="EXPIRED";attempts.save(attempt);throw new ResponseStatusException(HttpStatus.GONE,"The assessment time limit has expired");}
    return new StartView(attempt.id.toString(),attempt.status,attempt.startedAt,attempt.deadlineAt,attempt.policySnapshot,attempt.questionSnapshot);
  }
  @PostMapping("/api/v1/candidate/attempts/{attemptId}/questions/{questionId}/draft") @ResponseStatus(HttpStatus.CREATED) @Transactional
  SubmissionView saveDraft(@PathVariable UUID attemptId,@PathVariable UUID questionId,@Valid @RequestBody SaveCodeRequest req,Authentication auth){
    requireRole(auth,"CANDIDATE"); var attempt=attempts.findById(attemptId).filter(x->x.candidate.id.equals(userId(auth))).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    if(!attempt.status.equals("IN_PROGRESS")||Instant.now().isAfter(attempt.deadlineAt))throw new ResponseStatusException(HttpStatus.CONFLICT,"This attempt is not accepting drafts");
    var question=questions.findById(questionId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)); if(!question.assessment.id.equals(attempt.assignment.assessment.id))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var language=req.language().toLowerCase(); if(!List.of("java","python","javascript","typescript","cpp","csharp").contains(language))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported language");
    var submission=new CodeSubmission();submission.attempt=attempt;submission.question=question;submission.language=language;submission.sourceCode=req.sourceCode();submission.status="SAVED";submission=submissions.save(submission);
    return new SubmissionView(submission.id.toString(),submission.status,submission.createdAt);
  }
  @GetMapping("/api/v1/candidate/attempts/{attemptId}/questions/{questionId}/draft") @Transactional(readOnly=true)
  DraftView getDraft(@PathVariable UUID attemptId,@PathVariable UUID questionId,Authentication auth){
    requireRole(auth,"CANDIDATE");var attempt=attempts.findById(attemptId).filter(x->x.candidate.id.equals(userId(auth))).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    var question=questions.findById(questionId).filter(q->q.assessment.id.equals(attempt.assignment.assessment.id)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    return submissions.findFirstByAttemptIdAndQuestionIdAndStatusOrderByCreatedAtDesc(attemptId,question.id,"SAVED").map(s->new DraftView(s.sourceCode,s.language,s.createdAt)).orElse(null);
  }
  @PostMapping("/api/v1/candidate/attempts/{attemptId}/questions/{questionId}/run") @Transactional(noRollbackFor=ResponseStatusException.class)
  RunResponse runSample(@PathVariable UUID attemptId,@PathVariable UUID questionId,@Valid @RequestBody RunRequest req,Authentication auth){
    requireRole(auth,"CANDIDATE");var attempt=attempts.findById(attemptId).filter(x->x.candidate.id.equals(userId(auth))).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    if(!attempt.status.equals("IN_PROGRESS")||Instant.now().isAfter(attempt.deadlineAt))throw new ResponseStatusException(HttpStatus.CONFLICT,"This attempt is not accepting code runs");
    var question=questions.findById(questionId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if(!question.assessment.id.equals(attempt.assignment.assessment.id))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    String language=req.language().toLowerCase();if(!List.of("java","python","javascript","typescript","cpp","csharp").contains(language))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported language");
    var submission=new CodeSubmission();submission.attempt=attempt;submission.question=question;submission.language=language;submission.sourceCode=req.sourceCode();submission.status="RUNNING";submission=submissions.save(submission);
    try{Map<?,?> sample=question.publicSamples.isEmpty()?Map.of():question.publicSamples.getFirst() instanceof Map<?,?> map?map:Map.of();String stdin=sample.get("input") instanceof String value?value:"";String expected=sample.get("output") instanceof String value?value:"";var result=execution.execute(language,req.sourceCode(),stdin,expected);submission.providerToken=result.providerToken();submission.status=result.status();submission.result=Map.of("status",result.status(),"stdout",result.stdout()==null?"":result.stdout(),"stderr",result.stderr()==null?"":result.stderr(),"compileOutput",result.compileOutput()==null?"":result.compileOutput());submissions.save(submission);return new RunResponse(submission.id.toString(),result.status(),result.stdout(),result.stderr(),result.compileOutput(),result.message(),result.time(),result.memory());}
    catch(ProviderUnavailable ex){submission.status="PROVIDER_ERROR";submissions.save(submission);throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,ex.getMessage());}
  }
  @GetMapping("/api/v1/candidate/submissions/{submissionId}") @Transactional(readOnly=true)
  RunResponse submissionStatus(@PathVariable UUID submissionId,Authentication auth){
    requireRole(auth,"CANDIDATE");var submission=submissions.findById(submissionId).filter(x->x.attempt.candidate.id.equals(userId(auth))).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    if(submission.providerToken==null)return new RunResponse(submission.id.toString(),submission.status,null,null,null,null,null,null);
    try{var result=execution.status(submission.providerToken);return new RunResponse(submission.id.toString(),result.status(),result.stdout(),result.stderr(),result.compileOutput(),result.message(),result.time(),result.memory());}catch(ProviderUnavailable ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,ex.getMessage());}
  }
}

package com.cognihire.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/assessments")
class AssessmentController {
  private final AssessmentRepository assessments; private final CodingQuestionRepository questions; private final UserAccountRepository users;
  AssessmentController(AssessmentRepository assessments,CodingQuestionRepository questions,UserAccountRepository users) { this.assessments=assessments; this.questions=questions; this.users=users; }
  record Policy(boolean enabled,List<String> allowedCategories,int maxHintsPerAttempt,double maxPenalty) {}
  record AssessmentRequest(@NotBlank @Size(max=180) String title,@NotBlank @Size(max=10000) String description,@Min(1) @Max(600) int durationMinutes,@NotNull Policy aiPolicy) {}
  record QuestionRequest(@NotBlank @Size(max=180) String title,@NotBlank @Size(max=20000) String statement,@Pattern(regexp="EASY|MEDIUM|HARD") String difficulty,@Min(1) @Max(1000) int points,@Min(0) int position,@NotNull List<@NotNull Object> publicSamples,@Size(max=100) List<Object> hiddenTests,String referenceSolution) {}
  record QuestionResponse(String id,String title,String statement,String difficulty,int points,int position,List<Object> publicSamples) {}
  record AssessmentResponse(String id,String title,String description,int durationMinutes,String status,int version,Policy aiPolicy,List<QuestionResponse> questions) {}
  private UUID userId(Authentication auth) { return UUID.fromString(auth.getName()); }
  private void recruiter(Authentication auth) { if (!auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_RECRUITER")||a.getAuthority().equals("ROLE_ADMIN"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
  private Assessment owned(UUID id,Authentication auth) { var a=assessments.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)); if(!a.owner.id.equals(userId(auth))&&!auth.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_ADMIN"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND); return a; }
  private AssessmentResponse view(Assessment a) { return new AssessmentResponse(a.id.toString(),a.title,a.description,a.durationMinutes,a.status,a.version,new Policy(a.aiEnabled,a.allowedAssistance,a.maxHintsPerAttempt,a.maxPenalty),questions.findByAssessmentIdOrderByPosition(a.id).stream().map(q->new QuestionResponse(q.id.toString(),q.title,q.statement,q.difficulty,q.points,q.position,q.publicSamples)).toList()); }
  @GetMapping List<AssessmentResponse> list(Authentication auth) { recruiter(auth); return assessments.findByOwnerIdOrderByUpdatedAtDesc(userId(auth)).stream().map(this::view).toList(); }
  @PostMapping @ResponseStatus(HttpStatus.CREATED) AssessmentResponse create(@Valid @RequestBody AssessmentRequest req,Authentication auth) {
    recruiter(auth); validatePolicy(req.aiPolicy()); var a=new Assessment(); a.owner=users.findById(userId(auth)).orElseThrow(); a.title=req.title().trim(); a.description=req.description().trim(); a.durationMinutes=req.durationMinutes(); a.aiEnabled=req.aiPolicy().enabled(); a.allowedAssistance=List.copyOf(req.aiPolicy().allowedCategories()); a.maxHintsPerAttempt=req.aiPolicy().maxHintsPerAttempt(); a.maxPenalty=req.aiPolicy().maxPenalty(); a=assessments.save(a); return view(a);
  }
  @GetMapping("/{id}") AssessmentResponse get(@PathVariable UUID id,Authentication auth) { recruiter(auth); return view(owned(id,auth)); }
  @PostMapping("/{id}/questions") @ResponseStatus(HttpStatus.CREATED) @Transactional QuestionResponse addQuestion(@PathVariable UUID id,@Valid @RequestBody QuestionRequest req,Authentication auth) {
    recruiter(auth); var a=owned(id,auth); if(!a.status.equals("DRAFT")) throw new ResponseStatusException(HttpStatus.CONFLICT,"Published assessments cannot be changed");
    var q=new CodingQuestion(); q.assessment=a; q.title=req.title().trim(); q.statement=req.statement().trim(); q.difficulty=req.difficulty(); q.points=req.points(); q.position=req.position(); q.publicSamples=List.copyOf(req.publicSamples()); q.hiddenTests=req.hiddenTests()==null?List.of():List.copyOf(req.hiddenTests()); q.referenceSolution=req.referenceSolution(); q=questions.save(q);
    return new QuestionResponse(q.id.toString(),q.title,q.statement,q.difficulty,q.points,q.position,q.publicSamples);
  }
  @PostMapping("/{id}/publish") @Transactional AssessmentResponse publish(@PathVariable UUID id,Authentication auth) {
    recruiter(auth); var a=owned(id,auth); if(!a.status.equals("DRAFT")) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only drafts can be published");
    if(questions.findByAssessmentIdOrderByPosition(id).isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Add at least one coding question before publishing");
    a.version++; a.status="PUBLISHED"; a.updatedAt=java.time.Instant.now(); return view(assessments.save(a));
  }
  private void validatePolicy(Policy p) {
    if(p.allowedCategories()==null||p.maxHintsPerAttempt()<0||p.maxHintsPerAttempt()>50||!Double.isFinite(p.maxPenalty())||p.maxPenalty()<0||p.maxPenalty()>0.30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid AI policy values");
    if(!List.of("SYNTAX","CONCEPTUAL_HINT","DEBUGGING","EDGE_CASE").containsAll(p.allowedCategories())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported AI assistance category");
  }
}

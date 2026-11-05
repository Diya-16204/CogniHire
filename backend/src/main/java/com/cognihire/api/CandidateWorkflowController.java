package com.cognihire.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
class CandidateWorkflowController {
  private final AssessmentRepository assessments; private final UserAccountRepository users; private final AssessmentAssignmentRepository assignments; private final AssessmentAttemptRepository attempts; private final CodingQuestionRepository questions; private final CodeSubmissionRepository submissions; private final CodeExecutionProvider execution; private final QuestionRepository roundTwoQuestions; private final CodingAttemptMcqAnswerRepository roundTwoAnswers; private final RoundTwoInterviewQuestionRepository roundTwoInterviewQuestions;
  private final ObjectMapper objectMapper;
  private final GitHubService github;
  private final GitHubQuestionSynthesizer githubQuestionSynthesizer;
  private final LiveMonitoringService liveMonitoring;
  private final RoundTwoInterviewTurnRepository roundTwoInterviewTurns;
  CandidateWorkflowController(AssessmentRepository assessments,UserAccountRepository users,AssessmentAssignmentRepository assignments,AssessmentAttemptRepository attempts,CodingQuestionRepository questions,CodeSubmissionRepository submissions,CodeExecutionProvider execution,ObjectMapper objectMapper,QuestionRepository roundTwoQuestions,CodingAttemptMcqAnswerRepository roundTwoAnswers,RoundTwoInterviewQuestionRepository roundTwoInterviewQuestions,GitHubService github,GitHubQuestionSynthesizer githubQuestionSynthesizer,LiveMonitoringService liveMonitoring,RoundTwoInterviewTurnRepository roundTwoInterviewTurns) { this.assessments=assessments;this.users=users;this.assignments=assignments;this.attempts=attempts;this.questions=questions;this.submissions=submissions;this.execution=execution;this.objectMapper=objectMapper;this.roundTwoQuestions=roundTwoQuestions;this.roundTwoAnswers=roundTwoAnswers;this.roundTwoInterviewQuestions=roundTwoInterviewQuestions;this.github=github;this.githubQuestionSynthesizer=githubQuestionSynthesizer;this.liveMonitoring=liveMonitoring;this.roundTwoInterviewTurns=roundTwoInterviewTurns; }
  private UUID userId(Authentication auth){return UUID.fromString(auth.getName());}
  private void requireRole(Authentication a,String role){if(!a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_"+role)||x.getAuthority().equals("ROLE_ADMIN")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
  record AssignRequest(@Email @NotBlank String email) {}
  record StartAssessmentRequest(@Size(max=500) String githubRepositoryUrl) {}
  record AssignResponse(String assignmentId,String email,String fullName,boolean alreadyAssigned) {}
  record RecruiterAssignmentView(String assignmentId,String email,String fullName,Instant assignedAt,String attemptStatus) {}
  record AssignmentView(String id,String title,String description,int durationMinutes,String status,Instant assignedAt,String attemptId,String attemptStatus,boolean githubAnalysisEnabled) {}
  record ImportRowResult(int row,String email,String status,String message,String assignmentId) {}
  record ImportAssignmentsResponse(int assigned,int alreadyAssigned,int failed,List<ImportRowResult> rows) {}
  private record SpreadsheetEmail(int row,String email) {}
  record StartView(String id,String status,Instant startedAt,Instant deadlineAt,JsonNode policySnapshot,JsonNode questions,boolean roundTwoStarted,boolean roundTwoInterviewCompleted,JsonNode roundTwoInterviewTranscript,List<RoundTwoInterviewPrompt> roundTwoInterviewQuestions) {}
  record SaveCodeRequest(@jakarta.validation.constraints.NotNull @Size(max=100000) String sourceCode,@NotBlank String language,@Size(max=3000) String dryRunReasoning,@Size(max=20) List<@Size(max=2000) String> predictedOutputs,@Size(max=8) List<@Valid InterviewTurn> interviewTranscript,@Size(max=20) String phase,@Size(max=1600) String interviewPrompt,Boolean codeSubmitted) {}
  record RunRequest(@NotBlank @Size(max=100000) String sourceCode,@NotBlank String language) {}
  record RunResponse(String status,String stdout,String stderr,String compileOutput,String message,Double time,Long memory,String expectedOutput) {}
  record SubmissionView(String id,String status,Instant createdAt) {}
  record DraftView(String sourceCode,String language,Instant savedAt,JsonNode evidence) {}
  record SubmittedQuestion(@jakarta.validation.constraints.NotNull UUID questionId,@NotBlank @Size(max=100000) String sourceCode,@NotBlank String language,@NotBlank @Size(max=3000) String dryRunReasoning,@jakarta.validation.constraints.NotNull @Size(max=20) List<@NotBlank @Size(max=2000) String> predictedOutputs,@Size(max=8) List<@Valid InterviewTurn> interviewTranscript) {}
  record SubmitAssessmentRequest(@jakarta.validation.constraints.NotEmpty @Size(max=50) List<@Valid SubmittedQuestion> questions) {}
  record SubmitAssessmentResponse(String attemptId,String status,Instant submittedAt,int submittedQuestionCount) {}
  record SubmittedQuestionView(String questionId,String title,String language,String sourceCode,Object evidence) {}
  record RoundTwoAnswerView(String subject,String questionText,String selectedOption,String correctOption,boolean correct,Instant answeredAt) {}
  record InterviewAuditTurnView(int index,String subject,String question,String answer,Instant answeredAt) {}
  record SubmittedCandidateWork(String attemptId,String candidateName,String candidateEmail,Instant startedAt,Instant submittedAt,Integer roundTwoScore,Integer roundTwoTotal,List<RoundTwoAnswerView> roundTwoAnswers,boolean recruiterJoinedDuringTest,LocalDateTime recruiterJoinedAt,List<InterviewAuditTurnView> interviewTranscript,List<RecruiterInterventionView> recruiterInterventions,List<SubmittedQuestionView> questions) {}
  record InterviewCompleteRequest(@Size(max=8) List<@Valid InterviewTurn> transcript) {}
  record RoundTwoQuestionView(String id,String subject,String questionText,String optionA,String optionB,String optionC,String optionD) {}
  record RoundTwoReviewQuestion(String id,String subject,String questionText,String optionA,String optionB,String optionC,String optionD,String selectedOption) {}
  record RoundTwoProgress(int questionNumber,int totalQuestions,boolean complete,RoundTwoQuestionView question,List<RoundTwoReviewQuestion> questions) {}
  record RoundTwoAnswerRequest(@jakarta.validation.constraints.NotNull UUID questionId,@NotBlank @jakarta.validation.constraints.Pattern(regexp="[ABCD]") String selectedOption) {}

  @PostMapping("/api/v1/assessments/{assessmentId}/assignments") @ResponseStatus(HttpStatus.CREATED) @Transactional
  AssignResponse assign(@PathVariable UUID assessmentId,@Valid @RequestBody AssignRequest req,Authentication auth){
    var assessment=publishedOwnedAssessment(assessmentId,auth);
    var candidate=users.findByEmailIgnoreCase(req.email().trim()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Candidate account not found"));
    if(candidate.role!=Role.CANDIDATE)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The selected account is not a candidate");
    var existing=assignments.findByAssessmentIdAndCandidateId(assessment.id,candidate.id);
    var assignment=existing.orElseGet(()->assignCandidate(assessment,candidate));
    return new AssignResponse(assignment.id.toString(),candidate.email,candidate.fullName,existing.isPresent());
  }

  @GetMapping("/api/v1/assessments/{assessmentId}/assignments") @Transactional(readOnly=true)
  List<RecruiterAssignmentView> listAssignments(@PathVariable UUID assessmentId,Authentication auth){
    var assessment=publishedOwnedAssessment(assessmentId,auth);
    return assignments.findByAssessmentIdOrderByAssignedAtDesc(assessment.id).stream().map(assignment->{
      var attempt=attempts.findByAssignmentId(assignment.id).orElse(null);
      return new RecruiterAssignmentView(assignment.id.toString(),assignment.candidate.email,assignment.candidate.fullName,
          assignment.assignedAt,attempt==null?null:attempt.status);
    }).toList();
  }

  @GetMapping(value="/api/v1/assessments/{assessmentId}/assignments/template", produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  ResponseEntity<byte[]> assignmentTemplate(@PathVariable UUID assessmentId,Authentication auth){
    publishedOwnedAssessment(assessmentId,auth);
    try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
      var sheet=workbook.createSheet("Candidates");
      var header=sheet.createRow(0);header.createCell(0).setCellValue("email");
      sheet.setColumnWidth(0,36*256);workbook.write(output);
      return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=candidate-emails-template.xlsx")
          .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
          .body(output.toByteArray());
    }catch(IOException ex){throw new IllegalStateException("Could not create the Excel template",ex);}
  }

  @PostMapping(value="/api/v1/assessments/{assessmentId}/assignments/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  ImportAssignmentsResponse importAssignments(@PathVariable UUID assessmentId,@RequestParam("file") MultipartFile file,Authentication auth){
    var assessment=publishedOwnedAssessment(assessmentId,auth);
    if(file.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose an Excel file first");
    if(file.getSize()>5L*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Excel file must be 5 MB or smaller");
    if(file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload an .xlsx Excel workbook");
    var emails=readCandidateEmails(file);
    var seen=new HashSet<String>();var results=new ArrayList<ImportRowResult>();int assigned=0,alreadyAssigned=0,failed=0;
    for(var entry:emails){
      var email=entry.email().trim();var normalized=email.toLowerCase(Locale.ROOT);
      if(!normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")){
        results.add(new ImportRowResult(entry.row(),email,"INVALID_EMAIL","Enter a valid email address",null));failed++;continue;
      }
      if(!seen.add(normalized)){
        results.add(new ImportRowResult(entry.row(),email,"DUPLICATE_IN_FILE","This email already appeared in the workbook",null));failed++;continue;
      }
      var candidate=users.findByEmailIgnoreCase(email);
      if(candidate.isEmpty()){
        results.add(new ImportRowResult(entry.row(),email,"NOT_FOUND","No account exists for this email",null));failed++;continue;
      }
      if(candidate.get().role!=Role.CANDIDATE){
        results.add(new ImportRowResult(entry.row(),email,"NOT_CANDIDATE","This account is not registered as a candidate",null));failed++;continue;
      }
      boolean wasAssigned=assignments.findByAssessmentIdAndCandidateId(assessmentId,candidate.get().id).isPresent();
      var assignment=assignCandidate(assessment,candidate.get());
      if(wasAssigned){results.add(new ImportRowResult(entry.row(),email,"ALREADY_ASSIGNED","Candidate already has this assessment",assignment.id.toString()));alreadyAssigned++;}
      else{results.add(new ImportRowResult(entry.row(),email,"ASSIGNED","Assessment assigned",assignment.id.toString()));assigned++;}
    }
    return new ImportAssignmentsResponse(assigned,alreadyAssigned,failed,List.copyOf(results));
  }

  private List<SpreadsheetEmail> readCandidateEmails(MultipartFile file){
    try(Workbook workbook=WorkbookFactory.create(file.getInputStream())){
      if(workbook.getNumberOfSheets()==0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Workbook has no sheets");
      var sheet=workbook.getSheetAt(0);if(sheet.getLastRowNum()>1000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Workbook can contain at most 1,000 candidate rows");
      Row header=sheet.getRow(0);if(header==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"First row must contain an email header");
      var formatter=new DataFormatter(Locale.ROOT);var evaluator=workbook.getCreationHelper().createFormulaEvaluator();int emailColumn=-1;
      for(var cell:header){if("email".equals(formatter.formatCellValue(cell,evaluator).trim().toLowerCase(Locale.ROOT))){emailColumn=cell.getColumnIndex();break;}}
      if(emailColumn<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"First row must contain a column named email");
      var emails=new ArrayList<SpreadsheetEmail>();
      for(int index=1;index<=sheet.getLastRowNum();index++){
        Row row=sheet.getRow(index);if(row==null)continue;
        String email=formatter.formatCellValue(row.getCell(emailColumn),evaluator).trim();if(!email.isBlank())emails.add(new SpreadsheetEmail(index+1,email));
      }
      return List.copyOf(emails);
    }catch(ResponseStatusException ex){throw ex;}
    catch(IOException|EncryptedDocumentException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Could not read this workbook. Use the provided .xlsx template.");}
  }

  private Assessment publishedOwnedAssessment(UUID id,Authentication auth){
    requireRole(auth,"RECRUITER");boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));
    var assessment=(admin?assessments.findById(id):assessments.findByIdAndOwnerId(id,userId(auth)))
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    if(!assessment.status.equals("PUBLISHED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Publish the assessment before assigning candidates");
    return assessment;
  }
  private AssessmentAssignment assignCandidate(Assessment assessment,UserAccount candidate){
    return assignments.findByAssessmentIdAndCandidateId(assessment.id,candidate.id).orElseGet(()->{
      var assignment=new AssessmentAssignment();assignment.assessment=assessment;assignment.candidate=candidate;return assignments.save(assignment);
    });
  }
  private AssignmentView assignmentView(Assessment assessment,AssessmentAssignment assignment){
    return new AssignmentView(assignment.id.toString(),assessment.title,assessment.description,assessment.durationMinutes,assessment.status,assignment.assignedAt,null,null,assessment.githubAnalysisEnabled);
  }
  @GetMapping("/api/v1/candidate/assignments") List<AssignmentView> myAssignments(Authentication auth){
    requireRole(auth,"CANDIDATE"); return assignments.findByCandidateIdOrderByAssignedAtDesc(userId(auth)).stream().map(a->{var attempt=attempts.findByAssignmentId(a.id).orElse(null);return new AssignmentView(a.id.toString(),a.assessment.title,a.assessment.description,a.assessment.durationMinutes,a.assessment.status,a.assignedAt,attempt==null?null:attempt.id.toString(),attempt==null?null:attempt.status,a.assessment.githubAnalysisEnabled);}).toList();
  }
  @PostMapping("/api/v1/candidate/assignments/{assignmentId}/start") @Transactional(noRollbackFor=ResponseStatusException.class) StartView start(@PathVariable UUID assignmentId,@RequestBody(required=false) StartAssessmentRequest request,Authentication auth){
    requireRole(auth,"CANDIDATE"); var assignment=assignments.findByIdAndCandidateIdForUpdate(assignmentId,userId(auth)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    if(assignment.assessment==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"This assignment is not linked to an assessment");
    if(!assignment.assessment.status.equals("PUBLISHED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"This assessment is not available");
    var existingAttempt=attempts.findByAssignmentId(assignmentId);
    if(existingAttempt.isEmpty()&&assignment.assessment.githubAnalysisEnabled&&(request==null||request.githubRepositoryUrl()==null||request.githubRepositoryUrl().isBlank()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter your public GitHub repository URL to start this assessment");
    var attempt=existingAttempt.orElseGet(()->{
      var a=new AssessmentAttempt(); a.assignment=assignment;a.candidate=assignment.candidate;a.deadlineAt=Instant.now().plusSeconds(assignment.assessment.durationMinutes*60L);
      a.policySnapshot=objectMapper.valueToTree(Map.of("enabled",assignment.assessment.aiEnabled,"allowedCategories",assignment.assessment.allowedAssistance,"maxHintsPerAttempt",assignment.assessment.maxHintsPerAttempt,"maxPenalty",assignment.assessment.maxPenalty,"version",assignment.assessment.version));
      a.questionSnapshot=objectMapper.valueToTree(questions.findByAssessmentIdOrderByPosition(assignment.assessment.id).stream().map(q->Map.<String,Object>of("id",q.id.toString(),"title",q.title,"statement",q.statement,"difficulty",q.difficulty,"points",q.points,"position",q.position,"publicSamples",q.publicSamples)).toList());
      if(assignment.assessment.githubAnalysisEnabled){
        a.githubRepositoryUrl=request.githubRepositoryUrl().trim();
        GitHubRepositoryContext context=github.inspect(a.githubRepositoryUrl);
        List<String> generated=githubQuestionSynthesizer.generate(assignment.assessment.role,assignment.assessment.projectDescription,context);
        List<Map<String,Object>> personalQuestions=new ArrayList<>();
        for(int i=0;i<generated.size();i++) personalQuestions.add(Map.of("id","candidate-github-"+(i+1),"subject",context==null?"Project & Role Interview":"GitHub · "+context.fullName(),"questionText",generated.get(i),"position",i));
        a.roundTwoInterviewQuestions=objectMapper.valueToTree(personalQuestions);
      }
      return attempts.save(a);
    });
    if(!List.of("IN_PROGRESS","ROUND_2_COMPLETED").contains(attempt.status))throw new ResponseStatusException(HttpStatus.CONFLICT,"This attempt has already ended");
    if(Instant.now().isAfter(attempt.deadlineAt)){attempt.status="EXPIRED";attempts.save(attempt);throw new ResponseStatusException(HttpStatus.GONE,"The assessment time limit has expired");}
    return new StartView(attempt.id.toString(),attempt.status,attempt.startedAt,attempt.deadlineAt,attempt.policySnapshot,candidateQuestions(attempt.questionSnapshot),attempt.roundTwoStartedAt!=null,attempt.roundTwoInterviewCompletedAt!=null,attempt.roundTwoInterviewTranscript,roundTwoInterviewPromptList(attempt));
  }

  @GetMapping("/api/v1/candidate/attempts/{attemptId}/round-two/interview-questions") @Transactional(readOnly=true)
  List<RoundTwoInterviewPrompt> roundTwoInterviewQuestionList(@PathVariable UUID attemptId,Authentication auth){
    requireRole(auth,"CANDIDATE");var attempt=attempts.findByIdAndCandidateId(attemptId,userId(auth)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Attempt not found for this candidate"));
    return roundTwoInterviewPromptList(attempt);
  }

  private List<RoundTwoInterviewPrompt> roundTwoInterviewPromptList(AssessmentAttempt attempt){
    var assessment=assessment(attempt);
    var configured=roundTwoInterviewQuestions.findByAssessmentIdOrderByPositionAscCreatedAtAsc(assessment.id);
    List<RoundTwoInterviewPrompt> prompts=new ArrayList<>(configured.stream().map(q->new RoundTwoInterviewPrompt(q.id.toString(),q.subject,q.questionText,q.position)).toList());
    JsonNode personalized=attempt.roundTwoInterviewQuestions;
    if(personalized!=null&&personalized.isArray()) personalized.forEach(q->prompts.add(new RoundTwoInterviewPrompt(q.path("id").asText(),q.path("subject").asText("Project & Role Interview"),q.path("questionText").asText(),prompts.size())));
    if(!prompts.isEmpty())return prompts;
    return RoundTwoInterviewPromptCatalog.fallback(assessment.role);
  }
  private JsonNode candidateQuestions(JsonNode snapshot){
    var safeQuestions=objectMapper.createArrayNode();
    snapshot.forEach(question->{var safe=objectMapper.createObjectNode();for(String field:List.of("id","title","statement","difficulty","points","position"))if(question.has(field))safe.set(field,question.get(field));
      var safeSamples=objectMapper.createArrayNode();question.path("publicSamples").forEach(sample->{var safeSample=objectMapper.createObjectNode();if(sample.has("input"))safeSample.set("input",sample.get("input"));safeSamples.add(safeSample);});safe.set("publicSamples",safeSamples);safeQuestions.add(safe);});
    return safeQuestions;
  }
  private void requireCodingPhase(AssessmentAttempt attempt) {
    if(attempt.roundTwoStartedAt!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Coding and AI interview are locked after Round 2 begins");
    if(!attempt.status.equals("IN_PROGRESS")||Instant.now().isAfter(attempt.deadlineAt)) throw new ResponseStatusException(HttpStatus.CONFLICT,"This attempt is not accepting coding work");
  }
  private UUID assessmentId(AssessmentAttempt attempt){
    return assessment(attempt).id;
  }
  private Assessment assessment(AssessmentAttempt attempt){if(attempt==null||attempt.assignment==null||attempt.assignment.assessment==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"This attempt is not linked to an assessment");return attempt.assignment.assessment;}
  @PostMapping("/api/v1/candidate/attempts/{attemptId}/questions/{questionId}/run") @Transactional(readOnly=true)
  RunResponse runSample(@PathVariable UUID attemptId,@PathVariable UUID questionId,@Valid @RequestBody RunRequest req,Authentication auth){
    requireRole(auth,"CANDIDATE");var attempt=attempts.findByIdAndCandidateId(attemptId,userId(auth)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Attempt not found for this candidate"));
    requireCodingPhase(attempt);
    var question=questions.findByIdAndAssessmentId(questionId,assessmentId(attempt)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Question not found in this assessment"));
    if(!"python".equalsIgnoreCase(req.language().trim()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only Python sample execution is currently supported");
    JsonNode samples=objectMapper.valueToTree(question.publicSamples);
    String input=samples.path(0).path("input").asText(null);
    if(input==null||input.isBlank()) {
      if("two sum".equalsIgnoreCase(question.title.trim())) input="2 7 11 15\\n9";
      else throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"This coding question has no public sample input configured");
    }
    String expectedOutput=samples.path(0).path("output").isMissingNode()?null:samples.path(0).path("output").asText();
    try{var result=execution.execute(req.sourceCode(),input,71);var response=new RunResponse(result.status(),result.stdout(),result.stderr(),result.compileOutput(),result.message(),result.time(),result.memory(),expectedOutput);liveMonitoring.publishExecution(attempt,question,response);return response;}
    catch(Judge0Unavailable ex){throw new ResponseStatusException(ex.httpStatus(),ex.getMessage());}
  }
  @PostMapping("/api/v1/candidate/attempts/{attemptId}/questions/{questionId}/draft") @ResponseStatus(HttpStatus.CREATED) @Transactional
  SubmissionView saveDraft(@PathVariable UUID attemptId,@PathVariable UUID questionId,@Valid @RequestBody SaveCodeRequest req,Authentication auth){
    requireRole(auth,"CANDIDATE"); var attempt=attempts.findByIdAndCandidateId(attemptId,userId(auth)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Attempt not found for this candidate"));
    requireCodingPhase(attempt);
    var question=questions.findByIdAndAssessmentId(questionId,assessmentId(attempt)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Question not found in this assessment"));
    var language=req.language().toLowerCase(Locale.ROOT); if(!List.of("python").contains(language))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only Python submissions are currently supported");
    var submission=new CodeSubmission();submission.attempt=attempt;submission.question=question;submission.language=language;submission.sourceCode=req.sourceCode();submission.status="SAVED";
    var evidence=new java.util.HashMap<String,Object>();if(req.dryRunReasoning()!=null)evidence.put("dryRunReasoning",req.dryRunReasoning());if(req.predictedOutputs()!=null)evidence.put("predictedOutputs",req.predictedOutputs());if(req.interviewTranscript()!=null)evidence.put("interviewTranscript",req.interviewTranscript());if(req.phase()!=null)evidence.put("phase",req.phase());if(req.interviewPrompt()!=null)evidence.put("interviewPrompt",req.interviewPrompt());evidence.put("codeSubmitted",Boolean.TRUE.equals(req.codeSubmitted()));submission.result=evidence;submission=submissions.save(submission);
    return new SubmissionView(submission.id.toString(),submission.status,submission.createdAt);
  }
  @PostMapping("/api/v1/candidate/attempts/{attemptId}/questions/{questionId}/code-submit") @ResponseStatus(HttpStatus.CREATED) @Transactional
  SubmissionView submitQuestionCode(@PathVariable UUID attemptId,@PathVariable UUID questionId,@Valid @RequestBody SaveCodeRequest req,Authentication auth){
    requireRole(auth,"CANDIDATE");var attempt=attempts.findByIdAndCandidateId(attemptId,userId(auth)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Attempt not found for this candidate"));
    requireCodingPhase(attempt);
    var question=questions.findByIdAndAssessmentId(questionId,assessmentId(attempt)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Question not found in this assessment"));
    if(req.sourceCode().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Write your Python code before submitting it");
    if(req.dryRunReasoning()==null||req.dryRunReasoning().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Complete the dry-run reasoning first");
    String language=req.language().toLowerCase(Locale.ROOT);if(!language.equals("python"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only Python submissions are currently supported");
    int sampleCount=question.publicSamples==null?0:question.publicSamples.size();if(req.predictedOutputs()==null||req.predictedOutputs().size()!=sampleCount)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Complete the dry-run prediction for each public example first");
    var evidence=new java.util.HashMap<String,Object>();evidence.put("dryRunReasoning",req.dryRunReasoning());evidence.put("predictedOutputs",req.predictedOutputs());evidence.put("interviewTranscript",req.interviewTranscript()==null?List.of():req.interviewTranscript());evidence.put("phase","interview");evidence.put("interviewPrompt",req.interviewPrompt()==null?"":req.interviewPrompt());evidence.put("codeSubmitted",true);evidence.put("interviewCompleted",false);
    var submission=new CodeSubmission();submission.attempt=attempt;submission.question=question;submission.language=language;submission.sourceCode=req.sourceCode();submission.status="CODE_SUBMITTED";submission.result=evidence;submission=submissions.save(submission);
    return new SubmissionView(submission.id.toString(),submission.status,submission.createdAt);
  }
  @GetMapping("/api/v1/candidate/attempts/{attemptId}/questions/{questionId}/draft") @Transactional(readOnly=true)
  DraftView getDraft(@PathVariable UUID attemptId,@PathVariable UUID questionId,Authentication auth){
    requireRole(auth,"CANDIDATE");var attempt=attempts.findByIdAndCandidateId(attemptId,userId(auth)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Attempt not found for this candidate"));
    var question=questions.findByIdAndAssessmentId(questionId,assessmentId(attempt)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Question not found in this assessment"));
    return submissions.findFirstByAttemptIdAndQuestionIdOrderByCreatedAtDesc(attemptId,question.id).map(s->new DraftView(s.sourceCode,s.language,s.createdAt,objectMapper.valueToTree(s.result))).orElse(null);
  }

  @PostMapping("/api/v1/candidate/attempts/{attemptId}/questions/{questionId}/interview/complete") @Transactional
  SubmissionView completeInterview(@PathVariable UUID attemptId,@PathVariable UUID questionId,@Valid @RequestBody InterviewCompleteRequest req,Authentication auth) {
    requireRole(auth,"CANDIDATE"); var attempt=attempts.findByIdAndCandidateId(attemptId,userId(auth)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Attempt not found for this candidate"));
    requireCodingPhase(attempt);
    var question=questions.findByIdAndAssessmentId(questionId,assessmentId(attempt)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Question not found in this assessment"));
    var submission=submissions.findFirstByAttemptIdAndQuestionIdAndStatusOrderByCreatedAtDesc(attempt.id,question.id,"CODE_SUBMITTED").orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Submit code before completing its interview"));
    var evidence=objectMapper.convertValue(submission.result, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>() {});
    evidence.put("interviewCompleted",true); evidence.put("interviewTranscript",req.transcript()==null?List.of():req.transcript()); submission.result=evidence; submissions.save(submission);
    return new SubmissionView(submission.id.toString(),submission.status,submission.createdAt);
  }

  @PostMapping("/api/v1/candidate/attempts/{attemptId}/round-two/start") @Transactional
  RoundTwoProgress startRoundTwo(@PathVariable UUID attemptId,Authentication auth) {
    requireRole(auth,"CANDIDATE"); var attempt=attempts.findByIdAndCandidateIdForUpdate(attemptId,userId(auth)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Attempt not found for this candidate"));
    ensureAttemptOpen(attempt);
    if(attempt.roundTwoStartedAt==null) {
      var ordered=questions.findByAssessmentIdOrderByPosition(assessmentId(attempt));
      for(var codingQuestion:ordered) {
        var code=submissions.findFirstByAttemptIdAndQuestionIdAndStatusOrderByCreatedAtDesc(attempt.id,codingQuestion.id,"CODE_SUBMITTED").orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Submit every coding question before Round 2"));
        var evidence=objectMapper.convertValue(code.result,new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>() {});
        if(!Boolean.TRUE.equals(evidence.get("interviewCompleted"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Complete the AI interview for every coding question before Round 2");
      }
      if(roundTwoQuestions.findByAssessmentIdOrderByCreatedAtAscIdAsc(assessmentId(attempt)).isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"No Round 2 questions are configured for this assessment");
      attempt.roundTwoStartedAt=Instant.now(); attempts.save(attempt);
    }
    return roundTwoProgress(attempt);
  }

  @GetMapping({"/api/v1/candidate/attempts/{attemptId}/interventions", "/api/v1/candidate/attempts/{attemptId}/follow-ups"}) @Transactional(readOnly=true)
  ResponseEntity<List<RecruiterInterventionView>> candidateInterventions(@PathVariable UUID attemptId,Authentication auth) {
    requireRole(auth,"CANDIDATE");
    return ResponseEntity.ok(liveMonitoring.candidateInterventionAudit(attemptId,userId(auth)));
  }

  @GetMapping("/api/v1/candidate/attempts/{attemptId}/round-two/current") @Transactional(readOnly=true)
  RoundTwoProgress currentRoundTwo(@PathVariable UUID attemptId,Authentication auth) {
    requireRole(auth,"CANDIDATE"); var attempt=attempts.findByIdAndCandidateId(attemptId,userId(auth)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Attempt not found for this candidate"));
    ensureAttemptOpen(attempt); if(attempt.roundTwoStartedAt==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Round 2 has not started"); return roundTwoProgress(attempt);
  }

  @PostMapping("/api/v1/candidate/attempts/{attemptId}/round-two/answer") @Transactional
  RoundTwoProgress answerRoundTwo(@PathVariable UUID attemptId,@Valid @RequestBody RoundTwoAnswerRequest req,Authentication auth) {
    requireRole(auth,"CANDIDATE"); var attempt=attempts.findByIdAndCandidateIdForUpdate(attemptId,userId(auth)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Attempt not found for this candidate"));
    ensureAttemptOpen(attempt); if(attempt.roundTwoStartedAt==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Round 2 has not started");
    if(attempt.roundTwoInterviewCompletedAt!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"The Round 2 interview is complete; subject answers are locked");
    var ordered=roundTwoQuestions.findByAssessmentIdOrderByCreatedAtAscIdAsc(assessmentId(attempt)); long answered=roundTwoAnswers.countByAttemptId(attempt.id);
    int requestedIndex=-1;
    for(int i=0;i<ordered.size();i++) if(ordered.get(i).id.equals(req.questionId())) { requestedIndex=i; break; }
    if(requestedIndex<0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Round 2 question not found in this assessment");
    if(requestedIndex>answered) throw new ResponseStatusException(HttpStatus.CONFLICT,"Answer the current Round 2 question before moving ahead");
    final int selectedIndex=requestedIndex;
    var selectedQuestion=ordered.get(selectedIndex);
    var answer=roundTwoAnswers.findByAttemptIdAndQuestionId(attempt.id,selectedQuestion.id).orElseGet(()->{
      if(selectedIndex!=(int)answered) throw new ResponseStatusException(HttpStatus.CONFLICT,"This Round 2 answer is not available to edit");
      var created=new CodingAttemptMcqAnswer();created.attempt=attempt;created.question=selectedQuestion;return created;
    });
    answer.selectedOption=req.selectedOption(); answer.isCorrect=selectedQuestion.correctOption.equals(req.selectedOption()); answer.answeredAt=Instant.now(); roundTwoAnswers.save(answer);
    return roundTwoProgress(attempt);
  }

  private void ensureAttemptOpen(AssessmentAttempt attempt) {
    if(!List.of("IN_PROGRESS","ROUND_2_COMPLETED").contains(attempt.status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"This attempt has ended");
    if(Instant.now().isAfter(attempt.deadlineAt)) { attempt.status="EXPIRED"; attempts.save(attempt); throw new ResponseStatusException(HttpStatus.GONE,"The assessment time limit has expired"); }
  }
  private RoundTwoProgress roundTwoProgress(AssessmentAttempt attempt) {
    var ordered=roundTwoQuestions.findByAssessmentIdOrderByCreatedAtAscIdAsc(assessmentId(attempt)); int total=ordered.size(); int answered=(int)roundTwoAnswers.countByAttemptId(attempt.id);
    var savedAnswers=roundTwoAnswers.findByAttemptIdOrderByAnsweredAtAscIdAsc(attempt.id).stream().collect(java.util.stream.Collectors.toMap(answer->answer.question.id,answer->answer.selectedOption));
    var review=ordered.stream().limit(Math.min(total,answered+1L)).map(q->new RoundTwoReviewQuestion(q.id.toString(),q.subject,q.questionText,q.optionA,q.optionB,q.optionC,q.optionD,savedAnswers.get(q.id))).toList();
    if(answered>=total) return new RoundTwoProgress(total,total,true,null,review);
    var q=ordered.get(answered); return new RoundTwoProgress(answered+1,total,false,new RoundTwoQuestionView(q.id.toString(),q.subject,q.questionText,q.optionA,q.optionB,q.optionC,q.optionD),review);
  }

  @PostMapping("/api/v1/candidate/attempts/{attemptId}/submit") @Transactional
  SubmitAssessmentResponse submitAssessment(@PathVariable UUID attemptId,@Valid @RequestBody SubmitAssessmentRequest req,Authentication auth){
    requireRole(auth,"CANDIDATE");var attempt=attempts.findByIdAndCandidateId(attemptId,userId(auth)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Attempt not found for this candidate"));
    if(!List.of("IN_PROGRESS","ROUND_2_COMPLETED").contains(attempt.status)||Instant.now().isAfter(attempt.deadlineAt))throw new ResponseStatusException(HttpStatus.CONFLICT,"This assessment is no longer accepting submissions");
    if(attempt.roundTwoStartedAt==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Complete Round 2 before submitting the assessment");
    if(attempt.roundTwoInterviewCompletedAt==null||!attempt.status.equals("ROUND_2_COMPLETED")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Complete the Round 2 AI interview before submitting the assessment");
    var mcqList=roundTwoQuestions.findByAssessmentIdOrderByCreatedAtAscIdAsc(assessmentId(attempt));
    var mcqAnswers=roundTwoAnswers.findByAttemptIdOrderByAnsweredAtAscIdAsc(attempt.id);
    if(mcqList.isEmpty()||mcqAnswers.size()!=mcqList.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Answer every Round 2 question before submitting");
    var expectedIds=new HashSet<String>();attempt.questionSnapshot.forEach(q->{if(q.hasNonNull("id"))expectedIds.add(q.path("id").asText());});
    var receivedIds=new HashSet<UUID>();if(req.questions().size()!=expectedIds.size())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Submit code for every question before finishing");
    for(var item:req.questions()){
      if(!receivedIds.add(item.questionId()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A question was submitted more than once");
      if(!expectedIds.contains(item.questionId().toString()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Question does not belong to this attempt");
      String language=item.language().toLowerCase(Locale.ROOT);if(!List.of("python").contains(language))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only Python submissions are currently supported");
      var question=questions.findByIdAndAssessmentId(item.questionId(),assessmentId(attempt)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Question not found in this assessment"));
      if(!submissions.existsByAttemptIdAndQuestionIdAndStatus(attempt.id,question.id,"CODE_SUBMITTED"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Submit code for every question before submitting the assessment");
      var codeSubmission=submissions.findFirstByAttemptIdAndQuestionIdAndStatusOrderByCreatedAtDesc(attempt.id,question.id,"CODE_SUBMITTED").orElseThrow();
      var codeEvidence=objectMapper.convertValue(codeSubmission.result,new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>() {});
      if(!Boolean.TRUE.equals(codeEvidence.get("interviewCompleted"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Complete the AI interview for every coding question before submitting");
      int sampleCount=question.publicSamples==null?0:question.publicSamples.size();
      if(item.predictedOutputs()!=null&&item.predictedOutputs().size()!=sampleCount)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide one predicted output for each public example");
      var submission=new CodeSubmission();submission.attempt=attempt;submission.question=question;submission.language=language;submission.sourceCode=item.sourceCode();submission.status="SUBMITTED";
      var evidence=new java.util.HashMap<String,Object>();evidence.put("dryRunReasoning",item.dryRunReasoning());evidence.put("predictedOutputs",item.predictedOutputs());evidence.put("interviewTranscript",item.interviewTranscript()==null?List.of():item.interviewTranscript());evidence.put("codeWasExecuted",false);submission.result=evidence;submissions.save(submission);
    }
    attempt.roundTwoScore=(int)mcqAnswers.stream().filter(CodingAttemptMcqAnswer::isCorrect).count();attempt.roundTwoTotal=mcqList.size();
    attempt.status="SUBMITTED";attempt.submittedAt=Instant.now();attempts.save(attempt);
    return new SubmitAssessmentResponse(attempt.id.toString(),attempt.status,attempt.submittedAt,receivedIds.size());
  }
  @GetMapping("/api/v1/assessments/{assessmentId}/submitted-work") @Transactional(readOnly=true)
  List<SubmittedCandidateWork> submittedWork(@PathVariable UUID assessmentId,Authentication auth){
    requireRole(auth,"RECRUITER");boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));
    var assessment=(admin?assessments.findById(assessmentId):assessments.findByIdAndOwnerId(assessmentId,userId(auth))).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    return attempts.findByAssignment_Assessment_IdAndStatusOrderBySubmittedAtDesc(assessment.id,"SUBMITTED").stream()
        .map(a->new SubmittedCandidateWork(a.id.toString(),a.candidate.fullName,a.candidate.email,a.startedAt,a.submittedAt,a.roundTwoScore,a.roundTwoTotal,
            roundTwoAnswers.findByAttemptIdOrderByAnsweredAtAscIdAsc(a.id).stream().map(answer->new RoundTwoAnswerView(answer.question.subject,answer.question.questionText,answer.selectedOption,answer.question.correctOption,answer.isCorrect,answer.answeredAt)).toList(),
            a.recruiterJoinedDuringTest,a.recruiterJoinedAt,
            roundTwoInterviewTurns.findByAttemptIdOrderByTurnIndexAsc(a.id).stream().map(turn->new InterviewAuditTurnView(turn.turnIndex,turn.subject,turn.prompt,turn.answer,turn.answeredAt)).toList(),
            liveMonitoring.interventionAudit(a.id),
            submissions.findByAttemptIdAndStatusOrderByCreatedAt(a.id,"SUBMITTED").stream()
                .map(s->new SubmittedQuestionView(s.question.id.toString(),s.question.title,s.language,s.sourceCode,s.result)).toList())).toList();
  }
}

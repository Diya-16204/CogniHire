package com.cognihire.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

@RestController
class RoundTwoQuestionController {
  private final AssessmentRepository assessments;
  private final QuestionRepository questions;
  private final RoundTwoInterviewQuestionRepository interviewQuestions;
  RoundTwoQuestionController(AssessmentRepository assessments, QuestionRepository questions,RoundTwoInterviewQuestionRepository interviewQuestions) { this.assessments=assessments; this.questions=questions; this.interviewQuestions=interviewQuestions; }

  record QuestionRequest(@NotBlank @Size(max=120) String subject, @NotBlank @Size(max=20000) String questionText,
      @NotBlank @Size(max=1000) String optionA, @NotBlank @Size(max=1000) String optionB,
      @NotBlank @Size(max=1000) String optionC, @NotBlank @Size(max=1000) String optionD,
      @Pattern(regexp="[ABCD]") String correctOption) {}
  record QuestionView(String id, String subject, String questionText, String optionA, String optionB, String optionC, String optionD, String correctOption) {}
  record InterviewQuestionRequest(@NotBlank @Size(max=120) String subject,@NotBlank @Size(max=4000) String questionText) {}
  record InterviewQuestionView(String id,String subject,String questionText,int position) {}

  private Assessment owned(UUID id, Authentication auth) {
    UUID userId=UUID.fromString(auth.getName());
    boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));
    return (admin?assessments.findById(id):assessments.findByIdAndOwnerId(id,userId))
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Assessment not found"));
  }
  @GetMapping("/api/v1/assessments/{assessmentId}/round-two/questions") @Transactional(readOnly=true)
  List<QuestionView> list(@PathVariable UUID assessmentId, Authentication auth) {
    var assessment=owned(assessmentId,auth);
    if(!auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_RECRUITER")||a.getAuthority().equals("ROLE_ADMIN"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return questions.findByAssessmentIdOrderByCreatedAtAscIdAsc(assessment.id).stream().map(this::view).toList();
  }
  @PostMapping("/api/v1/assessments/{assessmentId}/round-two/questions") @ResponseStatus(HttpStatus.CREATED) @Transactional
  QuestionView create(@PathVariable UUID assessmentId, @Valid @RequestBody QuestionRequest request, Authentication auth) {
    var assessment=owned(assessmentId,auth);
    if(!auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_RECRUITER")||a.getAuthority().equals("ROLE_ADMIN"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if(!assessment.status.equals("DRAFT")) throw new ResponseStatusException(HttpStatus.CONFLICT,"Published assessments cannot be changed");
    if(request.correctOption()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose the correct option");
    var q=new Question(); q.assessment=assessment; q.subject=request.subject().trim(); q.questionText=request.questionText().trim();
    q.optionA=request.optionA().trim();q.optionB=request.optionB().trim();q.optionC=request.optionC().trim();q.optionD=request.optionD().trim();q.correctOption=request.correctOption();
    return view(questions.save(q));
  }
  private QuestionView view(Question q) { return new QuestionView(q.id.toString(),q.subject,q.questionText,q.optionA,q.optionB,q.optionC,q.optionD,q.correctOption); }

  @GetMapping("/api/v1/assessments/{assessmentId}/round-two/interview-questions") @Transactional(readOnly=true)
  List<InterviewQuestionView> interviewQuestions(@PathVariable UUID assessmentId,Authentication auth){
    var assessment=owned(assessmentId,auth);
    if(!auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_RECRUITER")||a.getAuthority().equals("ROLE_ADMIN"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return interviewQuestions.findByAssessmentIdOrderByPositionAscCreatedAtAsc(assessment.id).stream().map(q->new InterviewQuestionView(q.id.toString(),q.subject,q.questionText,q.position)).toList();
  }

  @PostMapping("/api/v1/assessments/{assessmentId}/round-two/interview-questions") @ResponseStatus(HttpStatus.CREATED) @Transactional
  InterviewQuestionView addInterviewQuestion(@PathVariable UUID assessmentId,@Valid @RequestBody InterviewQuestionRequest request,Authentication auth){
    var assessment=owned(assessmentId,auth);
    if(!auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_RECRUITER")||a.getAuthority().equals("ROLE_ADMIN"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if(!assessment.status.equals("DRAFT")) throw new ResponseStatusException(HttpStatus.CONFLICT,"Published assessments cannot be changed");
    int position=interviewQuestions.countByAssessmentId(assessmentId);
    if(position>=20)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"An assessment can have at most 20 AI interview questions");
    var question=new RoundTwoInterviewQuestion();question.assessment=assessment;question.subject=request.subject().trim();question.questionText=request.questionText().trim();question.position=position;
    question=interviewQuestions.save(question);
    return new InterviewQuestionView(question.id.toString(),question.subject,question.questionText,question.position);
  }
}

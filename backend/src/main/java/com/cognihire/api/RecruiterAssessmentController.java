package com.cognihire.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/recruiter/assessments")
class RecruiterAssessmentController {
  private final UnifiedRecruiterAssessmentService service;
  private final AssessmentAssignmentService assignmentService;
  RecruiterAssessmentController(UnifiedRecruiterAssessmentService service, AssessmentAssignmentService assignmentService) {
    this.service = service; this.assignmentService = assignmentService;
  }

  @PostMapping("/create")
  @ResponseStatus(HttpStatus.CREATED)
  UnifiedAssessmentCreatedResponse create(@Valid @RequestBody CreateUnifiedRecruiterAssessmentRequest request,
                                           Authentication authentication) {
    return service.create(request, authentication);
  }

  @PostMapping("/{assessmentId}/assign")
  CandidateEmailsAssignResponse assign(@org.springframework.web.bind.annotation.PathVariable java.util.UUID assessmentId,
      @Valid @RequestBody CandidateEmailsAssignRequest request, Authentication authentication) {
    return assignmentService.assign(assessmentId, request, authentication);
  }
}

@org.springframework.stereotype.Service
class UnifiedRecruiterAssessmentService {
  private static final String TEST_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final SecureRandom RANDOM = new SecureRandom();
  private final AssessmentRepository assessments;
  private final UserAccountRepository users;
  private final QuestionRepository subjectQuestions;
  private final RoundTwoInterviewQuestionRepository interviewQuestions;
  private final CodingQuestionRepository codingQuestions;

  UnifiedRecruiterAssessmentService(AssessmentRepository assessments, UserAccountRepository users,
      QuestionRepository subjectQuestions, RoundTwoInterviewQuestionRepository interviewQuestions,
      CodingQuestionRepository codingQuestions) {
    this.assessments = assessments; this.users = users; this.subjectQuestions = subjectQuestions;
    this.interviewQuestions = interviewQuestions; this.codingQuestions = codingQuestions;
  }

  @Transactional
  UnifiedAssessmentCreatedResponse create(CreateUnifiedRecruiterAssessmentRequest request, Authentication auth) {
    var recruiterId = RecruitmentAssessmentService.recruiter(auth);
    List<String> general = request.generalQuestions() == null ? List.of() : request.generalQuestions().stream()
        .map(String::trim).filter(text -> !text.isBlank()).distinct().toList();
    List<UnifiedMcqQuestionInput> mcqs = request.subjectQuestions() == null ? List.of() : request.subjectQuestions();
    if (mcqs.isEmpty())
      throw new IllegalArgumentException("Select at least one Core CS subject MCQ; candidates need a Round 2 question to complete the assessment");
    if (general.size() > 20)
      throw new IllegalArgumentException("General interview prompts may total at most 20");

    var assessment = new Assessment();
    assessment.owner = users.findById(recruiterId).orElseThrow(() -> new ResourceNotFoundException("Recruiter not found"));
    assessment.title = request.title().trim(); assessment.role = request.role().trim();
    assessment.description = request.description() == null || request.description().isBlank()
        ? "Assessment for " + assessment.role : request.description().trim();
    assessment.durationMinutes = request.durationMinutes();
    assessment.passingScore = request.passingScore().setScale(2, RoundingMode.HALF_UP);
    assessment.status = "PUBLISHED";
    assessment.testCode = uniqueTestCode();
    assessment.githubAnalysisEnabled = request.enableGithubAnalysis();
    assessment.githubRepositoryUrl = null;
    assessment.projectDescription = null;
    assessment = assessments.saveAndFlush(assessment);

    var codingQuestion = new CodingQuestion();
    codingQuestion.assessment = assessment;
    codingQuestion.title = "Two Sum";
    codingQuestion.statement = "Given an array of integers on the first line and a target on the second line, print the indices of two distinct numbers that add up to the target. Print the indices in ascending order. Assume exactly one solution exists.";
    codingQuestion.difficulty = "EASY";
    codingQuestion.points = 20;
    codingQuestion.position = 0;
    codingQuestion.publicSamples = List.of(java.util.Map.of("input", "2 7 11 15\n9", "output", "0 1"));
    codingQuestion.hiddenTests = List.of(
        java.util.Map.of("input", "3 2 4\n6", "output", "1 2"),
        java.util.Map.of("input", "3 3\n6", "output", "0 1"));
    codingQuestions.save(codingQuestion);

    for (var input : mcqs) {
      var q = new Question(); q.assessment = assessment; q.subject = input.subject().trim();
      q.questionText = input.questionText().trim(); q.optionA = input.optionA().trim(); q.optionB = input.optionB().trim();
      q.optionC = input.optionC().trim(); q.optionD = input.optionD().trim(); q.correctOption = input.correctOption();
      subjectQuestions.save(q);
    }
    List<RoundTwoInterviewQuestion> prompts = new ArrayList<>();
    int position = 0;
    for (String text : general) {
      var prompt = new RoundTwoInterviewQuestion(); prompt.assessment = assessment;
      prompt.subject = "General & Problem Solving"; prompt.questionText = text; prompt.position = position++;
      prompts.add(prompt);
    }
    interviewQuestions.saveAll(prompts);
    return new UnifiedAssessmentCreatedResponse(assessment.id.toString(), assessment.title, assessment.status, assessment.testCode,
        mcqs.size(), prompts.size(), assessment.githubAnalysisEnabled, null);
  }

  private String uniqueTestCode() {
    for (int attempt = 0; attempt < 20; attempt++) {
      var code = new StringBuilder(6);
      for (int i = 0; i < 6; i++) code.append(TEST_CODE_ALPHABET.charAt(RANDOM.nextInt(TEST_CODE_ALPHABET.length())));
      if (!assessments.existsByTestCode(code.toString())) return code.toString();
    }
    throw new IllegalStateException("Could not generate a unique candidate test code; please try again");
  }
}

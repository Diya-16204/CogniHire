package com.cognihire.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
class RecruiterAuthController {
  private final RecruiterAuthService auth;
  RecruiterAuthController(RecruiterAuthService auth) { this.auth = auth; }

  @PostMapping("/login")
  RecruiterLoginResponse login(@Valid @RequestBody RecruiterLoginRequest request) { return auth.login(request); }
}

@RestController
@RequestMapping("/api/assessments")
class RecruitmentAssessmentController {
  private final RecruitmentAssessmentService assessments;
  private final SubmissionReviewService reviews;

  RecruitmentAssessmentController(RecruitmentAssessmentService assessments, SubmissionReviewService reviews) {
    this.assessments = assessments; this.reviews = reviews;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  AssessmentDetailsResponse create(@Valid @RequestBody CreateRecruitmentAssessmentRequest request, Authentication authentication) {
    return assessments.create(request, authentication);
  }

  @PostMapping("/{assessmentId}/questions")
  @ResponseStatus(HttpStatus.CREATED)
  RecruiterQuestionResponse addQuestion(@PathVariable UUID assessmentId, @Valid @RequestBody CreateMcqQuestionRequest request, Authentication authentication) {
    return assessments.addQuestion(assessmentId, request, authentication);
  }

  @PutMapping("/{assessmentId}/publish")
  PublishedAssessmentResponse publish(@PathVariable UUID assessmentId, Authentication authentication) {
    return assessments.publish(assessmentId, authentication);
  }

  @GetMapping("/{assessmentId}/submissions")
  List<SubmissionSummaryResponse> submissions(@PathVariable UUID assessmentId, Authentication authentication) {
    return reviews.submissions(assessmentId, authentication);
  }
}

@RestController
@RequestMapping("/api/public")
class PublicAssessmentController {
  private final CandidateMcqService candidates;
  PublicAssessmentController(CandidateMcqService candidates) { this.candidates = candidates; }

  @PostMapping("/assessments/{testCode}/start")
  CandidateAssessmentResponse start(@PathVariable String testCode, @Valid @RequestBody CandidateStartRequest request) {
    return candidates.start(testCode, request);
  }

  @PostMapping("/attempts/{attemptId}/submit")
  CandidateTestResultResponse submit(@PathVariable UUID attemptId, @Valid @RequestBody SubmitRecruitmentAttemptRequest request) {
    return candidates.submit(attemptId, request);
  }
}

@RestController
@RequestMapping("/api/submissions")
class SubmissionReviewController {
  private final SubmissionReviewService reviews;
  SubmissionReviewController(SubmissionReviewService reviews) { this.reviews = reviews; }

  @PatchMapping("/{attemptId}/status")
  SubmissionSummaryResponse updateStatus(@PathVariable UUID attemptId, @Valid @RequestBody UpdateSubmissionStatusRequest request, Authentication authentication) {
    return reviews.updateStatus(attemptId, request, authentication);
  }
}

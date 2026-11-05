package com.cognihire.api;

import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

record LiveSessionView(String attemptId, String candidateId, String candidateName, String candidateEmail,
                       String assessmentId, String assessmentTitle, String status, Instant startedAt,
                       Instant deadlineAt) {}
record LiveCodeSnapshot(String attemptId, String questionId, String questionTitle, String language,
                        String code, Instant updatedAt) {}
record LiveCodeEvent(String type, String attemptId, String candidateName, String questionId,
                     String questionTitle, String language, String code, Instant updatedAt) {}
record LiveExecutionEvent(String type, String attemptId, String questionId, String status,
                          String stdout, String stderr, String compileOutput, String message,
                          String expectedOutput, Instant updatedAt) {}
record LiveTranscriptEvent(String type, String attemptId, String candidateName, String subject,
                           String question, String answer, String text, Instant updatedAt) {}
record LiveTurnView(int index, String subject, String question, String answer) {}
record LiveCandidateMessage(String text) {}
record CandidateProctorEventCommand(String type) {}
record WebRtcSignalCommand(String type, Map<String, Object> payload) {}
record WebRtcSignalEvent(String type, String attemptId, String senderId, String senderRole, String senderName,
                         Map<String, Object> payload, Instant sentAt) {}
record LiveSessionDetail(LiveSessionView session, LiveCodeSnapshot editor, List<LiveTurnView> transcript, boolean takeoverActive) {}

@Service
class LiveMonitoringService {
  private final AssessmentAttemptRepository attempts;
  private final UserAccountRepository users;
  private final CodingQuestionRepository questions;
  private final RoundTwoInterviewTurnRepository turns;
  private final SimpMessagingTemplate messaging;
  private final Map<String, LiveCodeSnapshot> editorSnapshots = new ConcurrentHashMap<>();
  private final Map<String, Boolean> recruiterTakeover = new ConcurrentHashMap<>();
  private final Map<String, List<String>> queuedPrompts = new ConcurrentHashMap<>();

  LiveMonitoringService(AssessmentAttemptRepository attempts, UserAccountRepository users, CodingQuestionRepository questions, RoundTwoInterviewTurnRepository turns,
                        SimpMessagingTemplate messaging) {
    this.attempts = attempts;
    this.users = users;
    this.questions = questions;
    this.turns = turns;
    this.messaging = messaging;
  }

  @Transactional(readOnly = true)
  List<LiveSessionView> list(UUID recruiterId) {
    return attempts.findLiveForRecruiter(recruiterId).stream().map(LiveMonitoringService::view).toList();
  }

  @Transactional(readOnly = true)
  LiveSessionDetail detail(UUID attemptId, UUID recruiterId) {
    var attempt = requireOwnedAttempt(attemptId, recruiterId);
    var transcript = turns.findByAttemptIdOrderByTurnIndexAsc(attemptId).stream()
        .map(turn -> new LiveTurnView(turn.turnIndex, turn.subject, turn.prompt, turn.answer)).toList();
    return new LiveSessionDetail(view(attempt), editorSnapshots.get(attemptId.toString()), transcript, isTakeoverActive(attemptId.toString()));
  }

  @Transactional(readOnly = true)
  void authorizeRecruiter(UUID attemptId, UUID recruiterId) {
    requireOwnedAttempt(attemptId, recruiterId);
  }

  @Transactional(readOnly = true)
  boolean authorizeCandidate(UUID attemptId, UUID candidateId) {
    return attempts.findByIdAndCandidateId(attemptId, candidateId).isPresent();
  }

  @Transactional(readOnly = true)
  AssessmentAttempt requireOwnedAttempt(UUID attemptId, UUID recruiterId) {
    return attempts.findForRecruiter(attemptId, recruiterId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Live assessment session not found"));
  }

  @Transactional(readOnly = true)
  void syncEditor(UUID attemptId, UUID candidateId, String questionId, String language, String code) {
    if (code == null || code.length() > 100_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code must be at most 100,000 characters");
    if (language == null || language.length() > 40) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid language is required");
    var attempt = attempts.findByIdAndCandidateId(attemptId, candidateId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attempt not found for this candidate"));
    if (!"IN_PROGRESS".equals(attempt.status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "This assessment is not active");
    UUID qid;
    try { qid = UUID.fromString(questionId); }
    catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid question ID is required"); }
    var question = questions.findByIdAndAssessmentId(qid, attempt.assignment.assessment.id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Question not found in this assessment"));
    var snapshot = new LiveCodeSnapshot(attemptId.toString(), qid.toString(), question.title, language, code, Instant.now());
    editorSnapshots.put(attemptId.toString(), snapshot);
    messaging.convertAndSend(editorTopic(attemptId), new LiveCodeEvent("CODE_UPDATE", attemptId.toString(),
        attempt.candidate.fullName, qid.toString(), question.title, language, code, snapshot.updatedAt()));
  }

  void publishExecution(AssessmentAttempt attempt, CodingQuestion question, CandidateWorkflowController.RunResponse result) {
    messaging.convertAndSend(editorTopic(attempt.id), new LiveExecutionEvent("EXECUTION_RESULT", attempt.id.toString(),
        question.id.toString(), result.status(), result.stdout(), result.stderr(), result.compileOutput(),
        result.message(), result.expectedOutput(), Instant.now()));
    publishTranscript(attempt.id.toString(), new LiveTranscriptEvent("CODE_EXECUTION", attempt.id.toString(),
        attempt.candidate.fullName, "Code execution", question.title, "",
        "Candidate ran the sample tests · " + result.status(), Instant.now()));
  }

  void publishTranscript(String attemptId, LiveTranscriptEvent event) {
    messaging.convertAndSend(transcriptTopic(attemptId), event);
  }

  void queuePrompt(String attemptId, String prompt) {
    queuedPrompts.compute(attemptId, (key, current) -> {
      var next = current == null ? new java.util.ArrayList<String>() : new java.util.ArrayList<>(current);
      next.add(prompt);
      return List.copyOf(next);
    });
  }

  List<String> drainQueuedPrompts(String attemptId) {
    var prompts = queuedPrompts.remove(attemptId);
    return prompts == null ? List.of() : prompts;
  }

  void setTakeover(String attemptId, boolean active) {
    if (active) recruiterTakeover.put(attemptId, true);
    else recruiterTakeover.remove(attemptId);
  }

  boolean isTakeoverActive(String attemptId) { return Boolean.TRUE.equals(recruiterTakeover.get(attemptId)); }

  @Transactional(readOnly = true)
  void candidateMessage(UUID attemptId, UUID candidateId, String text) {
    if (text == null || text.isBlank() || text.length() > 2000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a message of up to 2,000 characters");
    var attempt = attempts.findByIdAndCandidateId(attemptId, candidateId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Live assessment session not found"));
    if (!"IN_PROGRESS".equals(attempt.status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "This assessment is not active");
    if (!isTakeoverActive(attemptId.toString())) throw new ResponseStatusException(HttpStatus.CONFLICT, "The recruiter has not taken over this interview");
    publishTranscript(attemptId.toString(), new LiveTranscriptEvent("CANDIDATE_MESSAGE", attemptId.toString(),
        attempt.candidate.fullName, "Live recruiter chat", "", text.trim(), "Candidate sent a live message.", Instant.now()));
  }

  @Transactional(readOnly = true)
  void recordProctorEvent(UUID attemptId, UUID candidateId, CandidateProctorEventCommand command) {
    if (command == null || !List.of("TAB_HIDDEN", "TAB_VISIBLE").contains(command.type()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported assessment focus event");
    var attempt = attempts.findByIdAndCandidateId(attemptId, candidateId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment session not found"));
    if (!"IN_PROGRESS".equals(attempt.status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "This assessment is not active");
    boolean hidden = "TAB_HIDDEN".equals(command.type());
    publishTranscript(attemptId.toString(), new LiveTranscriptEvent(hidden ? "PROCTOR_TAB_SWITCH" : "PROCTOR_TAB_RETURN",
        attemptId.toString(), attempt.candidate.fullName, "Browser focus", "", "",
        hidden ? "Candidate switched away from the assessment tab." : "Candidate returned to the assessment tab.", Instant.now()));
  }

  @Transactional(readOnly = true)
  void relayWebRtc(UUID attemptId, WebRtcSignalCommand command, Authentication authentication) {
    if (authentication == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to join this call");
    String senderId = authentication.getName();
    boolean recruiter = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_RECRUITER"));
    boolean candidate = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CANDIDATE"));
    if (!recruiter && !candidate) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the recruiter or assigned candidate can join this call");
    var attempt = recruiter
        ? requireOwnedAttempt(attemptId, UUID.fromString(senderId))
        : attempts.findByIdAndCandidateId(attemptId, UUID.fromString(senderId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment session not found"));
    if (!"IN_PROGRESS".equals(attempt.status) && !"ROUND_2_COMPLETED".equals(attempt.status))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This assessment session is no longer active");
    if (command == null || command.type() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A WebRTC signal type is required");
    String type = command.type().trim().toUpperCase(java.util.Locale.ROOT);
    if (!List.of("OFFER", "ANSWER", "ICE_CANDIDATE", "END_CALL").contains(type))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported WebRTC signal type");
    Map<String, Object> payload = command.payload() == null ? Map.of() : command.payload();
    try {
      if (new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(payload).length > 100_000)
        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "WebRTC signal is too large");
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid WebRTC signal payload");
    }
    String senderName = recruiter ? users.findById(UUID.fromString(senderId)).map(u -> u.fullName).orElse("Recruiter") : attempt.candidate.fullName;
    messaging.convertAndSend("/topic/attempt/" + attemptId + "/webrtc",
        new WebRtcSignalEvent(type, attemptId.toString(), senderId, recruiter ? "RECRUITER" : "CANDIDATE", senderName, payload, Instant.now()));
  }

  private static String editorTopic(UUID attemptId) { return "/topic/attempt/" + attemptId + "/editor"; }
  private static String transcriptTopic(String attemptId) { return "/topic/attempt/" + attemptId + "/transcript"; }
  private static LiveSessionView view(AssessmentAttempt attempt) {
    var assignment = attempt.assignment;
    var assessment = assignment == null ? null : assignment.assessment;
    return new LiveSessionView(attempt.id.toString(), attempt.candidate.id.toString(), attempt.candidate.fullName,
        attempt.candidate.email, assessment == null ? "" : assessment.id.toString(),
        assessment == null ? "Assessment" : assessment.title, attempt.status, attempt.startedAt, attempt.deadlineAt);
  }
}

@RestController
@RequestMapping("/api/v1/recruiter/live-sessions")
class LiveMonitoringController {
  private final LiveMonitoringService live;
  LiveMonitoringController(LiveMonitoringService live) { this.live = live; }

  @GetMapping
  List<LiveSessionView> list(Authentication auth) {
    requireRecruiter(auth);
    return live.list(UUID.fromString(auth.getName()));
  }

  @GetMapping("/{attemptId}")
  LiveSessionDetail detail(@PathVariable UUID attemptId, Authentication auth) {
    requireRecruiter(auth);
    return live.detail(attemptId, UUID.fromString(auth.getName()));
  }

  private void requireRecruiter(Authentication auth) {
    if (auth == null || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_RECRUITER")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recruiter access is required");
  }
}

package com.cognihire.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

record InterviewWsCommand(String attemptId,String text) {}
record LiveInterventionCommand(String mode,String text) {}
record LiveEditorSyncCommand(String questionId,String language,String code) {}
record RoundTwoInterviewTurnView(int index,String subject,String question,String answer) {}
record InterviewWsEvent(String type,String attemptId,int questionNumber,int totalQuestions,String subject,String question,String text,int delaySeconds,List<RoundTwoInterviewTurnView> transcript) {}
record InterviewAnswerEvent(String eventId,String attemptId,String candidateId,int turnIndex,int delaySeconds,String subject,String question,String answer) {}
record RoundTwoInterviewState(String attemptId,String candidateId,List<RoundTwoInterviewPrompt> questions,int questionIndex,String subject,String prompt,String draft,boolean complete,long readyAtEpochMillis,List<RoundTwoInterviewTurnView> transcript) {}

@Controller
class RoundTwoInterviewSocketController {
  private final RoundTwoInterviewService interviews;
  private final LiveMonitoringService liveMonitoring;
  RoundTwoInterviewSocketController(RoundTwoInterviewService interviews,LiveMonitoringService liveMonitoring){this.interviews=interviews;this.liveMonitoring=liveMonitoring;}
  @MessageMapping("/interview.start") void start(InterviewWsCommand command,Principal principal){interviews.start(command,principal);}
  @MessageMapping("/interview.draft") void draft(InterviewWsCommand command,Principal principal){interviews.draft(command,principal);}
  @MessageMapping("/interview.answer") void answer(InterviewWsCommand command,Principal principal){interviews.answer(command,principal);}
  @MessageMapping("/attempt/{attemptId}/editor-sync") void editorSync(@DestinationVariable String attemptId,LiveEditorSyncCommand command,Principal principal){liveMonitoring.syncEditor(UUID.fromString(attemptId),UUID.fromString(principal.getName()),command.questionId(),command.language(),command.code());}
  @MessageMapping("/attempt/{attemptId}/intervene") void intervene(@DestinationVariable String attemptId,LiveInterventionCommand command,Principal principal){interviews.intervene(UUID.fromString(attemptId),command,principal);}
  @MessageMapping("/attempt/{attemptId}/candidate-message") void candidateMessage(@DestinationVariable String attemptId,LiveCandidateMessage command,Principal principal){liveMonitoring.candidateMessage(UUID.fromString(attemptId),UUID.fromString(principal.getName()),command.text());}
  @MessageMapping("/attempt/{attemptId}/intervention-answer") void interventionAnswer(@DestinationVariable String attemptId,RecruiterInterventionAnswer command,Principal principal){
    if (!(principal instanceof org.springframework.security.core.Authentication auth) || auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_CANDIDATE")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the assigned candidate can answer recruiter follow-ups");
    liveMonitoring.answerIntervention(UUID.fromString(attemptId),UUID.fromString(principal.getName()),command);
  }
  @MessageMapping("/attempt/{attemptId}/proctor-event") void proctorEvent(@DestinationVariable String attemptId,CandidateProctorEventCommand command,Principal principal){
    if (!(principal instanceof org.springframework.security.core.Authentication auth) || auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_CANDIDATE")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the assigned candidate can publish assessment focus events");
    liveMonitoring.recordProctorEvent(UUID.fromString(attemptId),UUID.fromString(principal.getName()),command);
  }
  @MessageMapping("/attempt/{attemptId}/webrtc-signal") void webrtcSignal(@DestinationVariable String attemptId,WebRtcSignalCommand command,Principal principal){
    if (!(principal instanceof org.springframework.security.core.Authentication auth)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in to join this call");
    liveMonitoring.relayWebRtc(UUID.fromString(attemptId),command,auth);
  }
}

@Service
class RoundTwoInterviewService {
  private static final Logger log=LoggerFactory.getLogger(RoundTwoInterviewService.class);
  private final AssessmentAttemptRepository attempts;
  private final QuestionRepository questions;
  private final CodingAttemptMcqAnswerRepository answers;
  private final RoundTwoInterviewTurnRepository turnRepository;
  private final RoundTwoInterviewQuestionRepository interviewQuestions;
  private final RoundTwoInterviewLogService logService;
  private final ObjectMapper mapper;
  private final SimpMessagingTemplate messaging;
  private final LiveMonitoringService liveMonitoring;
  private final TaskScheduler scheduler;
  private final Executor worker;
  private final ObjectProvider<RedisTemplate<String,Object>> redisProvider;
  private final ObjectProvider<KafkaTemplate<Object,Object>> kafkaProvider;
  private final String topic;
  private final boolean kafkaEnabled;
  private final ConcurrentHashMap<String,RoundTwoInterviewState> memory=new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String,Object> locks=new ConcurrentHashMap<>();
  private final java.util.Set<String> scheduled=ConcurrentHashMap.newKeySet();
  private final AtomicBoolean redisUnavailable=new AtomicBoolean(false);

  RoundTwoInterviewService(AssessmentAttemptRepository attempts,QuestionRepository questions,CodingAttemptMcqAnswerRepository answers,
      RoundTwoInterviewTurnRepository turnRepository,RoundTwoInterviewQuestionRepository interviewQuestions,RoundTwoInterviewLogService logService,ObjectMapper mapper,
      SimpMessagingTemplate messaging,LiveMonitoringService liveMonitoring,@org.springframework.beans.factory.annotation.Qualifier("interviewScheduler") TaskScheduler scheduler,@org.springframework.beans.factory.annotation.Qualifier("interviewWorker") Executor worker,
      ObjectProvider<RedisTemplate<String,Object>> redisProvider,ObjectProvider<KafkaTemplate<Object,Object>> kafkaProvider,
      @Value("${interview.kafka-topic:candidate-interview-answers}") String topic,@Value("${interview.kafka-enabled:false}") boolean kafkaEnabled){
    this.attempts=attempts;this.questions=questions;this.answers=answers;this.turnRepository=turnRepository;this.interviewQuestions=interviewQuestions;this.logService=logService;
    this.mapper=mapper;this.messaging=messaging;this.liveMonitoring=liveMonitoring;this.scheduler=scheduler;this.worker=worker;this.redisProvider=redisProvider;
    this.kafkaProvider=kafkaProvider;this.topic=topic;this.kafkaEnabled=kafkaEnabled;
  }

  void start(InterviewWsCommand command,Principal principal){
    UUID attemptId=parseAttempt(command);String candidateId=principal.getName();String key=attemptId.toString();
    try {
      RoundTwoInterviewState state=loadOrCreate(attemptId,UUID.fromString(candidateId));
      if(state.complete()){finish(state);return;}
      if(liveMonitoring.isTakeoverActive(key)){
        send(candidateId,event("TAKEOVER_WAITING",state,"The recruiter has taken over this interview. Wait for their message.",0));return;
      }
      if(!state.prompt().isBlank()){send(candidateId,event("QUESTION",state,"",0));return;}
      int delay=remainingDelay(state);
      send(candidateId,event("REVIEWING",state,"Preparing the next question…",delay));
      scheduleQuestion(state,delay);
    } catch(RuntimeException e){send(candidateId,new InterviewWsEvent("ERROR",key,0,0,"","",safeMessage(e),0,List.of()));}
  }

  void draft(InterviewWsCommand command,Principal principal){
    try {
      UUID attemptId=parseAttempt(command);String candidateId=principal.getName();String text=command.text()==null?"":command.text();
      if(text.length()>3000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Keep your live answer under 3,000 characters");
      synchronized(lock(attemptId)){
        RoundTwoInterviewState state=loadOrCreate(attemptId,UUID.fromString(candidateId));if(state.complete())return;
        save(copy(state,state.questionIndex(),state.subject(),state.prompt(),text,state.complete(),state.readyAtEpochMillis(),state.transcript()));
        liveMonitoring.publishTranscript(attemptId.toString(),new LiveTranscriptEvent("ANSWER_DRAFT",attemptId.toString(),"",state.subject(),state.prompt(),"",text,Instant.now()));
      }
    } catch(RuntimeException e){send(principal.getName(),new InterviewWsEvent("ERROR",command.attemptId(),0,0,"","",safeMessage(e),0,List.of()));}
  }

  void intervene(UUID attemptId,LiveInterventionCommand command,Principal principal){
    String recruiterId=principal.getName();
    if(!(principal instanceof org.springframework.security.core.Authentication auth)||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_RECRUITER")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Recruiter access is required");
    var attempt=liveMonitoring.requireOwnedAttempt(attemptId,UUID.fromString(recruiterId));
    if(!"IN_PROGRESS".equals(attempt.status))throw new ResponseStatusException(HttpStatus.CONFLICT,"This candidate session is no longer active");
    String mode=command==null||command.mode()==null?"":command.mode().trim().toUpperCase(java.util.Locale.ROOT);
    String candidateId=attempt.candidate.id.toString();
    if("PROMPT".equals(mode)){
      String text=command.text()==null?"":command.text().trim();
      if(text.isBlank()||text.length()>2000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a prompt of up to 2,000 characters");
      synchronized(lock(attemptId)){
        RoundTwoInterviewState state=readCache(attemptId.toString());
        if(state==null)liveMonitoring.queuePrompt(attemptId.toString(),text);
        else {
          if(state.complete())throw new ResponseStatusException(HttpStatus.CONFLICT,"The interview is already complete");
          var prompts=new ArrayList<>(state.questions());
          prompts.add(new RoundTwoInterviewPrompt("recruiter-"+UUID.randomUUID(),"Recruiter follow-up",text,prompts.size()));
          save(new RoundTwoInterviewState(state.attemptId(),state.candidateId(),List.copyOf(prompts),state.questionIndex(),state.subject(),state.prompt(),state.draft(),state.complete(),state.readyAtEpochMillis(),state.transcript()));
        }
      }
      liveMonitoring.createIntervention(attemptId,UUID.fromString(recruiterId),text);
      send(candidateId,new InterviewWsEvent("PROMPT_QUEUED",attemptId.toString(),0,0,"Recruiter follow-up","", "The recruiter added a follow-up question.",0,List.of()));
      return;
    }
    if("TAKEOVER".equals(mode)){
      liveMonitoring.setTakeover(attemptId.toString(),true);
      send(candidateId,new InterviewWsEvent("RECRUITER_JOINED",attemptId.toString(),0,0,"Recruiter","","A recruiter has joined this live assessment. The automated interviewer is paused.",0,List.of()));
      return;
    }
    if("DIRECT_MESSAGE".equals(mode)){
      String text=command.text()==null?"":command.text().trim();
      if(text.isBlank()||text.length()>2000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a message of up to 2,000 characters");
      if(!liveMonitoring.isTakeoverActive(attemptId.toString()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Take over the interview before sending a direct message");
      send(candidateId,new InterviewWsEvent("RECRUITER_MESSAGE",attemptId.toString(),0,0,"Live recruiter","",text,0,List.of()));
      return;
    }
    if("RESUME".equals(mode)){
      liveMonitoring.setTakeover(attemptId.toString(),false);
      RoundTwoInterviewState state=readCache(attemptId.toString());
      if(state!=null&&!state.complete()&&state.prompt().isBlank())scheduleQuestion(state,remainingDelay(state));
      send(candidateId,new InterviewWsEvent("AI_RESUMED",attemptId.toString(),0,0,"","","The automated interviewer has resumed.",0,List.of()));
      return;
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use PROMPT, TAKEOVER, DIRECT_MESSAGE, or RESUME as the intervention mode");
  }

  void answer(InterviewWsCommand command,Principal principal){
    UUID attemptId=parseAttempt(command);String candidateId=principal.getName();String key=attemptId.toString();String text=command.text()==null?"":command.text().trim();
    try {
      if(text.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Type an answer before sending it");
      if(text.length()>3000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Keep each answer under 3,000 characters");
      RoundTwoInterviewState next;int delay=ThreadLocalRandom.current().nextInt(2,4);int turnIndex;
      synchronized(lock(attemptId)){
        RoundTwoInterviewState state=loadOrCreate(attemptId,UUID.fromString(candidateId));
        if(state.complete())throw new ResponseStatusException(HttpStatus.CONFLICT,"The Round 2 interview is already complete");
        if(state.prompt().isBlank())throw new ResponseStatusException(HttpStatus.CONFLICT,"Wait for the interviewer’s question before answering");
        turnIndex=state.transcript().size();
        var transcript=new ArrayList<>(state.transcript());transcript.add(new RoundTwoInterviewTurnView(turnIndex,state.subject(),state.prompt(),text));
        int nextIndex=state.questionIndex()+1;boolean complete=nextIndex>=state.questions().size();
        String nextSubject=complete?"":state.questions().get(nextIndex).subject();
        next=new RoundTwoInterviewState(state.attemptId(),state.candidateId(),state.questions(),nextIndex,nextSubject,"","",complete,System.currentTimeMillis()+delay*1000L,List.copyOf(transcript));
        save(next);
      }
      send(candidateId,event("ACK",next,"Answer received.",delay));
      if(next.complete())send(candidateId,event("REVIEWING",next,"The interviewer is wrapping up your answers…",delay));
      else send(candidateId,event("REVIEWING",next,"AI is reviewing your answer…",delay));
      InterviewAnswerEvent event=new InterviewAnswerEvent(UUID.randomUUID().toString(),key,candidateId,turnIndex,delay,
          next.transcript().get(turnIndex).subject(),next.transcript().get(turnIndex).question(),text);
      worker.execute(()->publishOrProcess(event));
    } catch(RuntimeException e){send(candidateId,new InterviewWsEvent("ERROR",key,0,0,"","",safeMessage(e),0,List.of()));}
  }

  private RoundTwoInterviewState loadOrCreate(UUID attemptId,UUID candidateId){
    String key=attemptId.toString();RoundTwoInterviewState cached=readCache(key);if(cached!=null){if(!cached.candidateId().equals(candidateId.toString()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Interview attempt not found");return cached;}
    var attempt=attempts.findByIdAndCandidateId(attemptId,candidateId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Interview attempt not found for this candidate"));
    if(attempt.roundTwoStartedAt==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Complete the Round 2 subject questions before starting the interview");
    if(!List.of("IN_PROGRESS","ROUND_2_COMPLETED").contains(attempt.status))throw new ResponseStatusException(HttpStatus.CONFLICT,"This assessment is not accepting interview responses");
    if(Instant.now().isAfter(attempt.deadlineAt))throw new ResponseStatusException(HttpStatus.GONE,"The assessment time limit has expired");
    var ordered=questions.findByAssessmentIdOrderByCreatedAtAscIdAsc(attempt.assignment.assessment.id);
    if(answers.countByAttemptId(attempt.id)<ordered.size())throw new ResponseStatusException(HttpStatus.CONFLICT,"Answer all Round 2 subject questions before starting the interview");
    var configured=interviewQuestions.findByAssessmentIdOrderByPositionAscCreatedAtAsc(attempt.assignment.assessment.id);
    var promptBuilder=new ArrayList<RoundTwoInterviewPrompt>();
    configured.stream().map(q->new RoundTwoInterviewPrompt(q.id.toString(),q.subject,q.questionText,promptBuilder.size())).forEach(promptBuilder::add);
    if(attempt.roundTwoInterviewQuestions!=null&&attempt.roundTwoInterviewQuestions.isArray()) attempt.roundTwoInterviewQuestions.forEach(q->promptBuilder.add(new RoundTwoInterviewPrompt(q.path("id").asText(),q.path("subject").asText("Project & Role Interview"),q.path("questionText").asText(),promptBuilder.size())));
    if(promptBuilder.isEmpty())promptBuilder.addAll(RoundTwoInterviewPromptCatalog.fallback(attempt.assignment.assessment.role));
    for(String prompt:liveMonitoring.drainQueuedPrompts(key))promptBuilder.add(new RoundTwoInterviewPrompt("recruiter-"+UUID.randomUUID(),"Recruiter follow-up",prompt,promptBuilder.size()));
    List<RoundTwoInterviewPrompt> prompts=List.copyOf(promptBuilder);
    List<RoundTwoInterviewTurnView> transcript=turnRepository.findByAttemptIdOrderByTurnIndexAsc(attemptId).stream().map(t->new RoundTwoInterviewTurnView(t.turnIndex,t.subject,t.prompt,t.answer)).toList();
    int nextIndex=Math.min(transcript.size(),prompts.size());boolean complete=attempt.roundTwoInterviewCompletedAt!=null||attempt.status.equals("ROUND_2_COMPLETED")||nextIndex>=prompts.size();
    String subject=complete?"":prompts.get(nextIndex).subject();
    var state=new RoundTwoInterviewState(key,candidateId.toString(),prompts,nextIndex,subject,"","",complete,System.currentTimeMillis(),transcript);
    save(state);return state;
  }

  private void publishOrProcess(InterviewAnswerEvent event){
    if(kafkaEnabled){
      try {
        KafkaTemplate<Object,Object> kafka=kafkaProvider.getIfAvailable();
        if(kafka!=null){kafka.send(new ProducerRecord<>(topic,event.attemptId(),toJson(event))).whenComplete((result,error)->{if(error!=null){log.warn("Interview Kafka unavailable; processing answer through local async fallback: {}",safeMessage(error));fallback(event);}});return;}
      } catch(RuntimeException e){log.warn("Interview Kafka unavailable; processing answer through local async fallback: {}",safeMessage(e));}
    }
    fallback(event);
  }
  private void fallback(InterviewAnswerEvent event){try{worker.execute(()->processAnswer(event));}catch(RuntimeException e){log.error("Could not enqueue interview answer for processing",e);send(event.candidateId(),new InterviewWsEvent("ERROR",event.attemptId(),0,0,event.subject(),"","Interview worker is busy. Retry in a moment.",0,List.of()));}}

  @KafkaListener(topics="${interview.kafka-topic:candidate-interview-answers}",groupId="${spring.kafka.consumer.group-id:cognihire-interview}")
  void consume(String payload){try{processAnswer(mapper.readValue(payload,InterviewAnswerEvent.class));}catch(Exception e){log.error("Could not process candidate interview answer event",e);throw new IllegalStateException(e);}}

  private void processAnswer(InterviewAnswerEvent event){
    try {
      if(!logService.persist(event))return;
      liveMonitoring.publishTranscript(event.attemptId(),new LiveTranscriptEvent("ANSWER_SUBMITTED",event.attemptId(),"",event.subject(),event.question(),event.answer(),"Candidate answer received.",Instant.now()));
      if(liveMonitoring.isTakeoverActive(event.attemptId())){
        send(event.candidateId(),new InterviewWsEvent("TAKEOVER_WAITING",event.attemptId(),0,0,event.subject(),"","Your answer was shared with the recruiter. Waiting for the live interviewer.",0,List.of()));
        return;
      }
      if(!scheduled.add(event.eventId()))return;
      scheduler.schedule(()->{
        scheduled.remove(event.eventId());
        try {
          RoundTwoInterviewState state=readCache(event.attemptId());
          if(state==null){state=loadOrCreate(UUID.fromString(event.attemptId()),UUID.fromString(event.candidateId()));}
          if(state.complete()){finish(state);return;}
          generateQuestion(state);
        } catch(RuntimeException e){send(event.candidateId(),new InterviewWsEvent("ERROR",event.attemptId(),0,0,event.subject(),"",interviewError(e),0,List.of()));}
      },Instant.now().plusSeconds(event.delaySeconds()));
    } catch(RuntimeException e){log.error("Interview answer was received but could not be logged",e);send(event.candidateId(),new InterviewWsEvent("ERROR",event.attemptId(),0,0,event.subject(),"","Your answer was received, but transcript logging failed. Please retry after reconnecting.",0,List.of()));}
  }

  private void scheduleQuestion(RoundTwoInterviewState state,int delay){
    String guard=state.attemptId()+":"+state.questionIndex();if(!scheduled.add(guard))return;
    scheduler.schedule(()->{scheduled.remove(guard);try{generateQuestion(state);}catch(RuntimeException e){send(state.candidateId(),new InterviewWsEvent("ERROR",state.attemptId(),0,state.questions().size(),state.subject(),"",safeMessage(e),0,state.transcript()));}},Instant.now().plusSeconds(Math.max(delay,0)));
  }
  private void generateQuestion(RoundTwoInterviewState state){
    if(liveMonitoring.isTakeoverActive(state.attemptId())){
      send(state.candidateId(),event("TAKEOVER_WAITING",state,"The recruiter has taken over this interview. Wait for their message.",0));return;
    }
    if(state.questionIndex()>=state.questions().size()){finish(state);return;}
    var recruiterQuestion=state.questions().get(state.questionIndex());String prompt=recruiterQuestion.questionText();String subject=recruiterQuestion.subject();
    var updated=new RoundTwoInterviewState(state.attemptId(),state.candidateId(),state.questions(),state.questionIndex(),subject,prompt,"",false,state.readyAtEpochMillis(),state.transcript());
    save(updated);send(state.candidateId(),event("QUESTION",updated,"",0));
  }
  private void finish(RoundTwoInterviewState state){
    logService.complete(state);
    var completed=new RoundTwoInterviewState(state.attemptId(),state.candidateId(),state.questions(),state.questionIndex(),"","","",true,state.readyAtEpochMillis(),state.transcript());save(completed);
    send(state.candidateId(),event("COMPLETE",completed,"Round 2 interview complete. You can now submit the assessment.",0));
  }
  private int remainingDelay(RoundTwoInterviewState state){return (int)Math.max(0,Math.ceil((state.readyAtEpochMillis()-System.currentTimeMillis())/1000.0));}
  private RoundTwoInterviewState copy(RoundTwoInterviewState state,int index,String subject,String prompt,String draft,boolean complete,long readyAt,List<RoundTwoInterviewTurnView> transcript){return new RoundTwoInterviewState(state.attemptId(),state.candidateId(),state.questions(),index,subject,prompt,draft,complete,readyAt,transcript);}
  private InterviewWsEvent event(String type,RoundTwoInterviewState state,String text,int delay){return new InterviewWsEvent(type,state.attemptId(),Math.min(state.questionIndex()+1,state.questions().size()),state.questions().size(),state.subject(),state.prompt(),text,delay,state.transcript());}
  private void send(String candidateId,InterviewWsEvent event){
    messaging.convertAndSendToUser(candidateId,"/queue/interview",event);
    if(event.attemptId()!=null&&!event.attemptId().isBlank()){
      var latest=event.transcript()==null||event.transcript().isEmpty()?null:event.transcript().get(event.transcript().size()-1);
      liveMonitoring.publishTranscript(event.attemptId(),new LiveTranscriptEvent(event.type(),event.attemptId(),"",event.subject(),
          event.question(),latest==null?"":latest.answer(),event.text(),Instant.now()));
    }
  }
  private UUID parseAttempt(InterviewWsCommand command){try{return UUID.fromString(command.attemptId());}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A valid attempt ID is required");}}
  private Object lock(UUID id){return locks.computeIfAbsent(id.toString(),ignored->new Object());}
  private RoundTwoInterviewState readCache(String key){
    RoundTwoInterviewState state=memory.get(key);if(state!=null)return state;
    if(redisUnavailable.get())return null;
    try {var redis=redisProvider.getIfAvailable();if(redis==null)return null;Object value=redis.opsForValue().get("cognihire:interview:v2:"+key);if(value instanceof String json){state=mapper.readValue(json,RoundTwoInterviewState.class);memory.put(key,state);return state;}}
    catch(Exception e){redisUnavailable.set(true);log.warn("Redis unavailable; using in-memory interview state fallback: {}",safeMessage(e));}
    return null;
  }
  private void save(RoundTwoInterviewState state){
    memory.put(state.attemptId(),state);
    if(redisUnavailable.get())return;
    try {var redis=redisProvider.getIfAvailable();if(redis!=null)redis.opsForValue().set("cognihire:interview:v2:"+state.attemptId(),toJson(state),Duration.ofHours(4));}
    catch(Exception e){redisUnavailable.set(true);log.warn("Redis unavailable; using in-memory interview state fallback: {}",safeMessage(e));}
  }
  private String toJson(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Could not serialize interview event",e);}}
  private String safeMessage(Throwable error){String message=error.getMessage();return message==null||message.isBlank()?"The interview service could not complete this action.":message;}
  private String interviewError(Throwable error){return safeMessage(error);}
}

@Service
class RoundTwoInterviewLogService {
  private final RoundTwoInterviewTurnRepository turns;private final AssessmentAttemptRepository attempts;private final ObjectMapper mapper;
  RoundTwoInterviewLogService(RoundTwoInterviewTurnRepository turns,AssessmentAttemptRepository attempts,ObjectMapper mapper){this.turns=turns;this.attempts=attempts;this.mapper=mapper;}
  @Transactional boolean persist(InterviewAnswerEvent event){
    UUID attemptId=UUID.fromString(event.attemptId());
    if(turns.existsByAttemptIdAndTurnIndex(attemptId,event.turnIndex()))return false;
    var attempt=attempts.findById(attemptId).orElseThrow(()->new IllegalStateException("Interview attempt no longer exists"));
    var turn=new RoundTwoInterviewTurn();turn.attempt=attempt;turn.turnIndex=event.turnIndex();turn.subject=event.subject();turn.prompt=event.question();turn.answer=event.answer();turns.save(turn);return true;
  }
  @Transactional void complete(RoundTwoInterviewState state){
    UUID attemptId=UUID.fromString(state.attemptId());UUID candidateId=UUID.fromString(state.candidateId());
    var attempt=attempts.findByIdAndCandidateId(attemptId,candidateId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Interview attempt not found"));
    for(var item:state.transcript())if(!turns.existsByAttemptIdAndTurnIndex(attemptId,item.index())){
      var turn=new RoundTwoInterviewTurn();turn.attempt=attempt;turn.turnIndex=item.index();turn.subject=item.subject();turn.prompt=item.question();turn.answer=item.answer();turns.save(turn);
    }
    attempt.roundTwoInterviewTranscript=mapper.valueToTree(state.transcript());
    if(attempt.roundTwoInterviewCompletedAt==null)attempt.roundTwoInterviewCompletedAt=Instant.now();
    attempt.status="ROUND_2_COMPLETED";attempts.save(attempt);
  }
}

package com.cognihire.api;

import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
class InterviewWebSocketConfig implements WebSocketMessageBrokerConfigurer {
  private final JwtService jwt;
  private final ObjectProvider<LiveMonitoringService> liveMonitoringProvider;
  private final String origins;
  InterviewWebSocketConfig(JwtService jwt, ObjectProvider<LiveMonitoringService> liveMonitoringProvider, @Value("${app.frontend-origin}") String origins) { this.jwt=jwt; this.liveMonitoringProvider=liveMonitoringProvider; this.origins=origins; }

  @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
    var allowedOrigins=java.util.Arrays.stream(origins.split(",")).map(String::trim).filter(origin->!origin.isBlank()).toArray(String[]::new);
    registry.addEndpoint("/ws/interview").setAllowedOriginPatterns(allowedOrigins);
  }
  @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
    registry.enableSimpleBroker("/queue", "/topic");
  }
  @Override public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(new ChannelInterceptor() {
      @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
        if(accessor==null)return message;
        if(accessor.getCommand()==StompCommand.CONNECT) {
          String bearer=accessor.getFirstNativeHeader("Authorization");
          if(bearer==null||!bearer.startsWith("Bearer "))throw new IllegalArgumentException("A bearer token is required for the interview socket");
          Claims claims=jwt.parse(bearer.substring(7));
          String role=claims.get("role",String.class);
          if(!"CANDIDATE".equals(role)&&!"RECRUITER".equals(role))throw new IllegalArgumentException("Only candidate and recruiter accounts can join the interview socket");
          accessor.setUser(new UsernamePasswordAuthenticationToken(claims.getSubject(),null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
        } else if(accessor.getUser()==null) throw new IllegalArgumentException("Interview socket is not authenticated");
        if(accessor.getCommand()==StompCommand.SUBSCRIBE) {
          String destination=accessor.getDestination();
          if(destination!=null&&destination.matches("/topic/attempt/[0-9a-fA-F-]{36}/(editor|transcript|webrtc)")) {
            String[] parts=destination.split("/");
            try {
              var attemptId=java.util.UUID.fromString(parts[3]);
              var liveMonitoring=liveMonitoringProvider.getObject();
              if("webrtc".equals(parts[4])) {
                if(hasRole(accessor,"RECRUITER")) liveMonitoring.authorizeRecruiter(attemptId,java.util.UUID.fromString(accessor.getUser().getName()));
                else if(!hasRole(accessor,"CANDIDATE") || !liveMonitoring.authorizeCandidate(attemptId,java.util.UUID.fromString(accessor.getUser().getName())))
                  throw new IllegalArgumentException("You are not a participant in this assessment session");
              } else {
                if(!hasRole(accessor,"RECRUITER"))throw new IllegalArgumentException("Only recruiters can monitor live assessment sessions");
                liveMonitoring.authorizeRecruiter(attemptId,java.util.UUID.fromString(accessor.getUser().getName()));
              }
            }
            catch(RuntimeException ex) { throw new IllegalArgumentException("You are not authorized to monitor this assessment session"); }
          } else if(destination!=null&&destination.startsWith("/topic/attempt/")) {
            throw new IllegalArgumentException("Unsupported live monitoring destination");
          }
        }
        if(accessor.getCommand()==StompCommand.SEND) {
          String destination=accessor.getDestination();
          if(destination!=null&&destination.matches("/app/attempt/[0-9a-fA-F-]{36}/intervene")&&!hasRole(accessor,"RECRUITER"))
            throw new IllegalArgumentException("Only recruiters can intervene in an assessment");
          if(destination!=null&&destination.matches("/app/attempt/[0-9a-fA-F-]{36}/editor-sync")&&!hasRole(accessor,"CANDIDATE"))
            throw new IllegalArgumentException("Only candidates can publish editor updates");
          if(destination!=null&&destination.matches("/app/attempt/[0-9a-fA-F-]{36}/candidate-message")&&!hasRole(accessor,"CANDIDATE"))
            throw new IllegalArgumentException("Only candidates can send candidate messages");
          if(destination!=null&&destination.matches("/app/attempt/[0-9a-fA-F-]{36}/proctor-event")&&!hasRole(accessor,"CANDIDATE"))
            throw new IllegalArgumentException("Only candidates can publish assessment focus events");
          if(destination!=null&&destination.matches("/app/attempt/[0-9a-fA-F-]{36}/webrtc-signal")&&!hasRole(accessor,"RECRUITER")&&!hasRole(accessor,"CANDIDATE"))
            throw new IllegalArgumentException("Only recruiters and candidates can send call signals");
          if(destination!=null&&destination.matches("/app/interview\\.(start|draft|answer)")&&!hasRole(accessor,"CANDIDATE"))
            throw new IllegalArgumentException("Only candidates can submit interview responses");
        }
        return message;
      }
    });
  }

  

  private boolean hasRole(StompHeaderAccessor accessor,String role) {
    return accessor.getUser() instanceof org.springframework.security.core.Authentication auth
        && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_"+role));
  }

  @Bean RedisTemplate<String,Object> interviewRedisTemplate(RedisConnectionFactory connectionFactory) {
    var template=new RedisTemplate<String,Object>();
    var serializer=new StringRedisSerializer();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(serializer); template.setHashKeySerializer(serializer);
    template.setValueSerializer(serializer); template.setHashValueSerializer(serializer);
    template.afterPropertiesSet();
    return template;
  }
  @Bean ThreadPoolTaskScheduler interviewScheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("interview-delay-");scheduler.setWaitForTasksToCompleteOnShutdown(false);return scheduler;}
  @Bean(name="interviewWorker") ThreadPoolTaskExecutor interviewWorker(){var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(2);executor.setMaxPoolSize(2);executor.setQueueCapacity(50);executor.setThreadNamePrefix("interview-worker-");executor.initialize();return executor;}
}

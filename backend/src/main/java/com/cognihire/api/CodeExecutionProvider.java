package com.cognihire.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

record Judge0Result(String status,String stdout,String stderr,String compileOutput,String message,Double time,Long memory) {}

interface CodeExecutionProvider {
  Judge0Result execute(String sourceCode,String stdin,int languageId);
}

@Component
class Judge0CodeExecutionProvider implements CodeExecutionProvider {
  private static final Logger log=LoggerFactory.getLogger(Judge0CodeExecutionProvider.class);
  private static final String PISTON_PYTHON_VERSION="3.10.0";
  private final String baseUrl,pistonUrl,apiKey,rapidApiHost;
  private final boolean pistonEnabled;
  private final ObjectMapper mapper;
  private final HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

  Judge0CodeExecutionProvider(@Value("${judge0.base-url}") String baseUrl,
      @Value("${piston.url:http://localhost:2000}") String pistonUrl,
      @Value("${piston.enabled:true}") boolean pistonEnabled,
      @Value("${judge0.api-key}") String apiKey,
      @Value("${judge0.rapidapi-host}") String rapidApiHost,
      ObjectMapper mapper) {
    this.baseUrl=baseUrl==null?"":baseUrl.trim().replaceAll("/+$","");
    this.pistonUrl=pistonUrl==null?"http://localhost:2000":pistonUrl.trim().replaceAll("/+$","");
    this.pistonEnabled=pistonEnabled;
    this.apiKey=apiKey==null?"":apiKey.trim();
    this.rapidApiHost=rapidApiHost==null?"":rapidApiHost.trim();
    this.mapper=mapper;
  }

  @Override public Judge0Result execute(String sourceCode,String stdin,int languageId) {
    if(baseUrl.contains("emkc.org")||baseUrl.contains("piston")||baseUrl.contains(":2000")||(baseUrl.isBlank()&&!pistonUrl.isBlank())) {
      if(!pistonEnabled) throw new Judge0Unavailable(HttpStatus.SERVICE_UNAVAILABLE,"Code execution is disabled in this deployment; configure a private Piston service to enable it.");
      return executePiston(sourceCode,stdin);
    }
    if(baseUrl.isBlank())throw new Judge0Unavailable("The code execution service is not configured");
    if(baseUrl.contains("rapidapi.com")&&apiKey.isBlank())throw new Judge0Unavailable("Set JUDGE0_API_KEY in the backend .env file");
    try {
      var payload=Map.of("source_code",sourceCode,"language_id",languageId,"stdin",stdin,
          "cpu_time_limit",3,"wall_time_limit",5,"memory_limit",128000);
      String body=mapper.writeValueAsString(payload);
      var create=HttpRequest.newBuilder(URI.create(baseUrl+"/submissions?base64_encoded=false&wait=false"))
          .timeout(Duration.ofSeconds(12)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
      addAuth(create);
      var created=client.send(create.build(),HttpResponse.BodyHandlers.ofString());
      if(created.statusCode()<200||created.statusCode()>=300)throw new Judge0Unavailable("Judge0 rejected the submission (HTTP "+created.statusCode()+")");
      JsonNode token=mapper.readTree(created.body()).path("token");
      if(!token.isTextual()||token.asText().isBlank())throw new Judge0Unavailable("Judge0 did not return a submission token");
      String encodedToken=java.net.URLEncoder.encode(token.asText(),java.nio.charset.StandardCharsets.UTF_8);
      for(int i=0;i<30;i++) {
        Thread.sleep(500);
        var status=HttpRequest.newBuilder(URI.create(baseUrl+"/submissions/"+encodedToken+"?base64_encoded=false&fields=stdout,stderr,compile_output,message,time,memory,status"))
            .timeout(Duration.ofSeconds(8)).GET();
        addAuth(status);
        var response=client.send(status.build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()<200||response.statusCode()>=300)throw new Judge0Unavailable("Could not read the Judge0 result (HTTP "+response.statusCode()+")");
        JsonNode result=mapper.readTree(response.body());JsonNode state=result.path("status");int statusId=state.path("id").asInt(0);
        if(statusId!=1&&statusId!=2) {
          return new Judge0Result(state.path("description").asText("Unknown"),text(result,"stdout"),text(result,"stderr"),text(result,"compile_output"),text(result,"message"),decimal(result,"time"),integer(result,"memory"));
        }
      }
      throw new Judge0Unavailable("Judge0 took too long to finish this sample");
    } catch(InterruptedException e) {
      Thread.currentThread().interrupt();throw new Judge0Unavailable("Judge0 request was interrupted");
    } catch(Judge0Unavailable e) { throw e; }
    catch(Exception e) { throw new Judge0Unavailable("Judge0 is unreachable or returned an invalid response"); }
  }
  private Judge0Result executePiston(String sourceCode,String stdin) {
    try {
      var payload=Map.of(
          "language","python",
          "version",PISTON_PYTHON_VERSION,
          "files",java.util.List.of(Map.of("content",sourceCode)),
          "stdin",stdin==null?"":stdin);
      var request=HttpRequest.newBuilder(URI.create(pistonUrl+"/api/v2/execute")).timeout(Duration.ofSeconds(15))
          .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
      var response=client.send(request,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()<200||response.statusCode()>=300) {
        log.error("Piston execution failed: HTTP {} response body: {}",response.statusCode(),response.body());
        String message=pistonErrorMessage(response.body(),response.statusCode());
        HttpStatus status=response.statusCode()==400||response.statusCode()==422?HttpStatus.BAD_REQUEST:HttpStatus.SERVICE_UNAVAILABLE;
        throw new Judge0Unavailable(status,message);
      }
      JsonNode result=mapper.readTree(response.body()); JsonNode run=result.path("run"), compile=result.path("compile");
      if(run.isMissingNode()) throw new Judge0Unavailable("Piston returned no execution result: "+result.path("message").asText("invalid Piston response"));
      String code=run.path("code").isNumber()?Integer.toString(run.path("code").asInt()):null;
      String status=run.path("status").asText("");
      String description=run.path("message").asText(null);
      if(status.isBlank()) status="0".equals(code)?"Accepted":"Runtime Error";
      String compileOutput=compile.isMissingNode()?null:compile.path("stderr").asText(compile.path("stdout").asText(""));
      Double time=run.path("cpu_time").isNumber()?run.path("cpu_time").asDouble()/1000.0:null;
      Long memory=run.path("memory").isNumber()?run.path("memory").asLong():null;
      return new Judge0Result(status,text(run,"stdout"),text(run,"stderr"),compileOutput,description,time,memory);
    } catch(InterruptedException e) {
      Thread.currentThread().interrupt(); throw new Judge0Unavailable("Piston request was interrupted");
    } catch(Judge0Unavailable e) { throw e; }
    catch(HttpTimeoutException e) { throw new Judge0Unavailable("Piston did not respond within 15 seconds. Check that its Docker container is running."); }
    catch(java.net.ConnectException e) { throw new Judge0Unavailable("Cannot connect to Piston at "+baseUrl+". Start the local Piston container and retry."); }
    catch(Exception e) { throw new Judge0Unavailable("Piston is unreachable or returned an invalid response: "+safeMessage(e)); }
  }
  private String pistonErrorMessage(String body,int status) {
    try {
      JsonNode response=mapper.readTree(body);
      for(String key:java.util.List.of("message","error","detail")) {
        JsonNode detail=response.path(key);
        if(detail.isTextual()&&!detail.asText().isBlank()) {
          if(status==404) return "Piston could not find the requested Python runtime. Ensure Python "+PISTON_PYTHON_VERSION+" is installed. Details: "+detail.asText();
          return "Piston rejected the execution request: "+detail.asText();
        }
      }
    } catch(Exception ignored) { }
    if(body!=null&&!body.isBlank()) {
      String detail=body.trim();
      if(detail.length()>1000) detail=detail.substring(0,1000)+"…";
      return "Piston rejected the execution request (HTTP "+status+"): "+detail;
    }
    return status==404
        ? "Python "+PISTON_PYTHON_VERSION+" is not installed in Piston. The backend will try to install it at startup."
        : "Piston rejected the execution request (HTTP "+status+").";
  }
  private String safeMessage(Exception error) {
    String message=error.getMessage();
    return message==null||message.isBlank()?error.getClass().getSimpleName():message;
  }
  private void addAuth(HttpRequest.Builder request) {
    if(baseUrl.contains("rapidapi.com")) { request.header("X-RapidAPI-Key",apiKey);if(!rapidApiHost.isBlank())request.header("X-RapidAPI-Host",rapidApiHost); }
    else if(!apiKey.isBlank())request.header("X-Auth-Token",apiKey);
  }
  private String text(JsonNode node,String key){return node.path(key).isNull()?null:node.path(key).asText(null);}
  private Double decimal(JsonNode node,String key){return node.path(key).isNumber()?node.path(key).asDouble():null;}
  private Long integer(JsonNode node,String key){return node.path(key).isNumber()?node.path(key).asLong():null;}
}

class Judge0Unavailable extends RuntimeException {
  private final HttpStatus httpStatus;
  Judge0Unavailable(String message){this(HttpStatus.SERVICE_UNAVAILABLE,message);}
  Judge0Unavailable(HttpStatus httpStatus,String message){super(message);this.httpStatus=httpStatus;}
  HttpStatus httpStatus(){return httpStatus;}
}

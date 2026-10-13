package com.cognihire.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

interface CodeExecutionProvider {
  ExecutionOutcome execute(String language,String sourceCode,String stdin,String expectedOutput);
  ExecutionOutcome status(String providerToken);
}
record ExecutionOutcome(String status,String stdout,String stderr,String compileOutput,String message,Double time,Long memory,String providerToken) {}

@Component
class Judge0CodeExecutionProvider implements CodeExecutionProvider {
  private final String baseUrl; private final String apiKey; private final Map<String,Integer> languageIds; private final ObjectMapper mapper; private final HttpClient client;
  Judge0CodeExecutionProvider(@Value("${judge0.base-url}") String baseUrl,@Value("${judge0.api-key}") String apiKey,@Value("${judge0.language-ids}") String configuredIds,ObjectMapper mapper){
    this.baseUrl=baseUrl==null?"":baseUrl.trim().replaceAll("/+$","");this.apiKey=apiKey==null?"":apiKey.trim();this.mapper=mapper;this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();this.languageIds=parseLanguageIds(configuredIds);
  }
  private Map<String,Integer> parseLanguageIds(String value){Map<String,Integer> parsed=new HashMap<>();if(value==null||value.isBlank())return Map.of();for(String part:value.split(",")){String[] pair=part.trim().split("=",2);if(pair.length==2)try{parsed.put(pair[0].trim().toLowerCase(),Integer.parseInt(pair[1].trim()));}catch(NumberFormatException ignored){}}return Map.copyOf(parsed);}
  @Override public ExecutionOutcome execute(String language,String sourceCode,String stdin,String expectedOutput){
    if(baseUrl.isBlank())throw new ProviderUnavailable("Code execution provider is not configured");
    Integer languageId=languageIds.get(language.toLowerCase());if(languageId==null)throw new IllegalArgumentException("This language is not configured for execution");
    try {
      Map<String,Object> body=new HashMap<>();body.put("source_code",sourceCode);body.put("language_id",languageId);body.put("stdin",stdin);body.put("expected_output",expectedOutput);body.put("cpu_time_limit",2);body.put("cpu_extra_time",0.5);body.put("wall_time_limit",5);body.put("memory_limit",128000);body.put("stack_limit",64000);body.put("max_processes_and_or_threads",20);body.put("max_file_size",1024);body.put("enable_network",false);
      JsonNode created=send("POST",baseUrl+"/submissions?base64_encoded=false&wait=false",mapper.writeValueAsString(body));String token=created.path("token").asText("");if(token.isBlank())throw new ProviderUnavailable("Execution provider did not return a submission token");
      for(int i=0;i<12;i++){
        Thread.sleep(350);ExecutionOutcome result=status(token);if(!result.status().equals("In Queue")&&!result.status().equals("Processing"))return result;
      }
      return new ExecutionOutcome("Processing",null,null,null,"Execution is still processing. Retry status lookup later.",null,null,token);
    }catch(InterruptedException e){Thread.currentThread().interrupt();throw new ProviderUnavailable("Execution was interrupted");}catch(ProviderUnavailable|IllegalArgumentException e){throw e;}catch(Exception e){throw new ProviderUnavailable("Execution provider request failed");}
  }
  @Override public ExecutionOutcome status(String token){
    if(baseUrl.isBlank())throw new ProviderUnavailable("Code execution provider is not configured");
    try{JsonNode result=send("GET",baseUrl+"/submissions/"+java.net.URLEncoder.encode(token,java.nio.charset.StandardCharsets.UTF_8)+"?base64_encoded=false&fields=token,status,stdout,stderr,compile_output,message,time,memory",null);
      return new ExecutionOutcome(result.path("status").path("description").asText("Unknown"),bounded(result,"stdout"),bounded(result,"stderr"),bounded(result,"compile_output"),bounded(result,"message"),result.path("time").isNumber()?result.path("time").asDouble():null,result.path("memory").isNumber()?result.path("memory").asLong():null,token);
    }catch(Exception e){if(e instanceof ProviderUnavailable p)throw p;throw new ProviderUnavailable("Execution provider request failed");}
  }
  private String bounded(JsonNode node,String key){if(node.path(key).isNull()||node.path(key).isMissingNode())return null;String value=node.path(key).asText();return value.length()>12000?value.substring(0,12000)+"… output truncated":value;}
  private JsonNode send(String method,String url,String body)throws Exception{
    var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(7)).header("Accept","application/json");if(!apiKey.isBlank())builder.header("X-Auth-Token",apiKey);
    if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()<200||response.statusCode()>=300)throw new ProviderUnavailable("Execution provider returned HTTP "+response.statusCode());return mapper.readTree(response.body());
  }
}

class ProviderUnavailable extends RuntimeException { ProviderUnavailable(String message){super(message);} }

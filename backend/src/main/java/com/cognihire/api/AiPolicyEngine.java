package com.cognihire.api;

import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.Set;

/** Deterministic gates run before any model request. This narrows leakage risk but is not a perfect classifier. */
@Component
class AiPolicyEngine {
  record Decision(boolean allowed,String category,String reason) {}
  Decision evaluate(String request,boolean enabled,Set<String> permitted,int deliveredHints,int maxHints,boolean hasPriorConceptualDisclosure){
    if(!enabled)return new Decision(false,"NONE","AI assistance is disabled for this assessment.");
    if(request==null||request.isBlank()||request.length()>1500)return new Decision(false,"NONE","Please send a short question about your current approach.");
    String text=request.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
    if(matches(text,"ignore (all |the )?(previous|above|assessment|system) (instructions|rules|prompt)","reveal (the )?(hidden tests?|reference solution|system prompt)","show (me )?(the )?(hidden tests?|reference solution)","full solution","complete solution","solve this (whole |entire )?problem","write (the )?(entire |complete )?(answer|solution|program|code)","implement (the )?(complete|whole|entire)"))
      return new Decision(false,"NONE","I can’t provide a complete solution or reveal private assessment material. Share your approach and I can offer a limited hint.");
    if(hasPriorConceptualDisclosure&&matches(text,"(next|remaining) (line|lines|part|step)","write (the )?(code|implementation)","finish (the )?(function|method|implementation)","turn (that|it) into code","translate (that|it) into code"))
      return new Decision(false,"NONE","I can’t assemble an implementation across multiple replies. I can help you reason through a specific edge case.");
    String category=category(text);if(category.equals("UNKNOWN"))return new Decision(false,"NONE","Tell me what you have tried or which concept is unclear, and I’ll keep the guidance focused.");
    if(deliveredHints>=maxHints)return new Decision(false,"NONE","You’ve used the assistance allowance for this assessment.");
    if(!permitted.contains(category))return new Decision(false,"NONE","This type of assistance is not enabled for this assessment.");
    return new Decision(true,category,"");
  }
  private String category(String text){
    if(matches(text,"compiler error","compile error","syntax error","what does this (error|keyword|operator) mean","explain (this|the) (error|syntax|keyword)","language behavior"))return "SYNTAX";
    if(matches(text,"edge case","boundary case","what should i consider","base case","corner case"))return "EDGE_CASE";
    if(matches(text,"debug","why (does|is|isn't|is not)","what is wrong","failing test","runtime error","bug"))return "DEBUGGING";
    if(matches(text,"hint","approach","algorithm","concept","how should i think","where should i start","explain (the )?(concept|idea|tradeoff)"))return "CONCEPTUAL_HINT";
    return "UNKNOWN";
  }
  private boolean matches(String text,String... patterns){for(String pattern:patterns)if(java.util.regex.Pattern.compile("\\b"+pattern+"\\b",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find())return true;return false;}
  boolean responseIsTooRevealing(String response){
    if(response==null||response.isBlank()||response.length()>1200)return true;
    String text=response.toLowerCase(Locale.ROOT);
    if(text.contains("```")||text.matches("(?s).*\\b(for|while)\\s*\\([^)]*;[^)]*\\).*"))return true;
    long codeLines=response.lines().filter(line->line.strip().matches("(return|if|else|for|while|const|let|var|int|string|public|def|print|[{};]).*")).count();
    return codeLines>=4;
  }
}

final class AssistancePenaltyCalculator {
  private AssistancePenaltyCalculator(){}
  record Event(String category,String disclosureKey,boolean delivered) {}
  static double adjustedScore(double rawScore,java.util.List<Event> events,double configuredCap){
    if(!Double.isFinite(rawScore)||rawScore<0||rawScore>100)throw new IllegalArgumentException("Raw score must be between 0 and 100");
    if(!Double.isFinite(configuredCap)||configuredCap<0||configuredCap>0.30)throw new IllegalArgumentException("Penalty cap must be between 0 and 0.30");
    java.util.Set<String> seen=new java.util.HashSet<>();
    double total=events.stream().filter(Event::delivered).filter(event->event.disclosureKey()!=null&&seen.add(event.disclosureKey())).mapToDouble(event->switch(event.category()){case "SYNTAX"->0.02;case "CONCEPTUAL_HINT","DEBUGGING","EDGE_CASE"->0.05;case "SPECIFIC_GUIDANCE"->0.10;default->0.0;}).sum();
    return rawScore*(1-Math.min(total,configuredCap));
  }
}

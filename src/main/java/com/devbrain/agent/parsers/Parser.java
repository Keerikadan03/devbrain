package com.devbrain.agent.parsers;

import java.util.Map;

/**
 * Strategy interface for content parsers.
 * Each parser knows how to extract knowledge from a specific content type.
 * From Bits2Brain's parser architecture.
 */
public interface Parser {

    /** Returns the parser name (e.g., "url", "text", "image") */
    String getName();

    /** Processes input and returns extracted knowledge as a map */
    Map<String, Object> run(Map<String, Object> input);
}

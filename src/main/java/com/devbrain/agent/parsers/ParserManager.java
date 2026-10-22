package com.devbrain.agent.parsers;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of all parsers. Routes ingestion requests to the correct parser
 * based on the content type. Strategy pattern from Bits2Brain.
 */
@Slf4j
@Component
public class ParserManager {

    private final Map<String, Parser> parserMap = new HashMap<>();

    public ParserManager(List<Parser> parsers) {
        parsers.forEach(p -> parserMap.put(p.getName(), p));
    }

    @PostConstruct
    public void init() {
        log.info("[ParserManager] Registered parsers: {}", parserMap.keySet());
    }

    /**
     * Get a parser by name.
     * @throws IllegalArgumentException if no parser found for the given type
     */
    public Parser getParser(String type) {
        Parser parser = parserMap.get(type);
        if (parser == null) {
            throw new IllegalArgumentException("No parser registered for type: " + type
                    + ". Available: " + parserMap.keySet());
        }
        return parser;
    }

    public boolean hasParser(String type) {
        return parserMap.containsKey(type);
    }
}

package com.devbrain.model.neo4j;

import lombok.Getter;

/**
 * Types of relationships between knowledge nodes. Each has a color for the graph and a
 * directionality flag. A directional edge reads "FROM <type> TO", e.g. "A builds upon B"
 * (B is the foundation), "A is an example of B", "A supersedes B", "A depends on B".
 *
 *   Purple  #9b59b6  same concept    -- two notes about the same thing
 *   Yellow  #f1c40f  shared idea     -- overlapping but distinct concepts
 *   Red     #e74c3c  opposing view   -- conflicting opinions on the same topic
 *   Blue    #3498db  builds upon     -- FROM builds on the foundation TO (directional)
 *   Green   #2ecc71  example of      -- FROM is a concrete instance of TO (directional)
 *   Orange  #e67e22  supersedes      -- FROM evolved from / replaced TO (directional)
 *   Cyan    #00bcd4  depends on      -- FROM needs TO at runtime / in practice (directional)
 *   Pink    #e91e63  contrasts with  -- legitimate alternatives, neither wrong
 */
@Getter
public enum RelationType {

    SAME_CONCEPT("same_concept", "#9b59b6", false, "Same concept discussed in both notes"),
    SHARED_IDEA("shared_idea", "#f1c40f", false, "Overlapping ideas or shared opinions"),
    OPPOSING_VIEW("opposing_view", "#e74c3c", false, "Conflicting views on the same topic"),
    BUILDS_UPON("builds_upon", "#3498db", true, "One builds on the other, which is its foundation"),
    EXAMPLE_OF("example_of", "#2ecc71", true, "Concrete instance of an abstract concept"),
    SUPERSEDES("supersedes", "#e67e22", true, "One evolved from or replaced the other"),
    DEPENDS_ON("depends_on", "#00bcd4", true, "Practical runtime or system dependency"),
    CONTRASTS_WITH("contrasts_with", "#e91e63", false, "Legitimate alternatives for the same problem");

    private final String code;
    private final String color;
    private final boolean directional;
    private final String description;

    RelationType(String code, String color, boolean directional, String description) {
        this.code = code;
        this.color = color;
        this.directional = directional;
        this.description = description;
    }

    public String label() {
        return code.replace('_', ' ');
    }

    /** Looks up a type from the LLM's string; unknown values fall back to SHARED_IDEA. */
    public static RelationType fromCode(String code) {
        if (code == null) return SHARED_IDEA;
        for (RelationType rt : values()) {
            if (rt.code.equalsIgnoreCase(code.trim())) return rt;
        }
        try {
            return valueOf(code.trim().toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return SHARED_IDEA;
        }
    }

    public static boolean isKnown(String code) {
        for (RelationType rt : values()) {
            if (rt.code.equalsIgnoreCase(code)) return true;
        }
        return false;
    }

    /** All types are stored under one Neo4j relationship type, distinguished by properties. */
    public static final String NEO4J_TYPE = "KNOWLEDGE_REL";
}

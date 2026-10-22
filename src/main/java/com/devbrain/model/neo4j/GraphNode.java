package com.devbrain.model.neo4j;

import lombok.*;
import org.springframework.data.neo4j.core.schema.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Neo4j node for the knowledge graph.
 * Each node maps 1:1 with a KnowledgeNode in PostgreSQL (linked by uuid).
 * Relationships (SIMILAR) are created based on embedding similarity scores.
 */
@Node("Knowledge")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphNode {

    @Id
    private String uuid; // matches KnowledgeNode.id.toString()

    @Property
    private String title;

    @Property
    private String source;

    @Property
    private Instant createdAt;

    @Property
    private String extra; // JSON string for flexible metadata

    @Relationship(type = "SIMILAR", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<SimilarRelationship> similarTo = new HashSet<>();
}

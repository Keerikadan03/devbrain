package com.devbrain.model.neo4j;

import lombok.*;
import org.springframework.data.neo4j.core.schema.*;

/**
 * Weighted SIMILAR relationship between two knowledge nodes.
 * Score is the cosine similarity from the embedding comparison.
 */
@RelationshipProperties
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimilarRelationship {

    @RelationshipId
    private Long id;

    @Property
    private double score;

    @TargetNode
    private GraphNode target;
}

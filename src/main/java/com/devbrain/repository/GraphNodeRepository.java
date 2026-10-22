package com.devbrain.repository;

import com.devbrain.model.neo4j.GraphNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GraphNodeRepository extends Neo4jRepository<GraphNode, String> {

    Optional<GraphNode> findByTitle(String title);

    @Query("MATCH (n:Knowledge)-[r:SIMILAR]->(m:Knowledge) WHERE n.uuid = $uuid RETURN m, r ORDER BY r.score DESC")
    List<GraphNode> findSimilarNodes(String uuid);

    @Query("""
        MATCH (a:Knowledge {uuid: $fromId}), (b:Knowledge {uuid: $toId})
        MERGE (a)-[r:SIMILAR]->(b)
        ON CREATE SET r.score = $score
        ON MATCH SET r.score = $score
    """)
    void createSimilarRelationship(String fromId, String toId, double score);
}

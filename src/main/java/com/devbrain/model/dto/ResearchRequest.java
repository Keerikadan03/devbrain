package com.devbrain.model.dto;

import lombok.*;

/**
 * Request to kick off a multi-agent research session.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResearchRequest {
    private String query; // the research question
}

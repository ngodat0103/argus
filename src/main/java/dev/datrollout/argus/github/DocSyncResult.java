package dev.datrollout.argus.github;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;

@AllArgsConstructor
@JsonClassDescription(value = "A docs to hold sync Result")
public class DocSyncResult {
    @JsonPropertyDescription(value = "True if docs is outdated compared to the source, false if the docs is up-todate")
    boolean isOutOfSync;

    @JsonPropertyDescription("The proposed fix, It can't not be null if outOfSync=true")
    private String proposedSyncDocs;
}

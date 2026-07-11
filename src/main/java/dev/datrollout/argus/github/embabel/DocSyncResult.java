package dev.datrollout.argus.github.embabel;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "doc_sync_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@JsonClassDescription(value = "A docs to hold sync Result")
public class DocSyncResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonPropertyDescription(value = "True if docs is outdated compared to the source, false if the docs is up-todate")
    @Column(name = "is_out_of_sync", nullable = false)
    private boolean isOutOfSync;

    @JsonPropertyDescription("The proposed fix, It can't not be null if outOfSync=true")
    @Column(name = "proposed_sync_docs", columnDefinition = "text")
    private String proposedSyncDocs;

    @Column(name = "repository_full_name")
    private String repositoryFullName;

    @Column(name = "git_ref")
    private String ref;

    @Column(name = "before_sha")
    private String beforeSha;

    @Column(name = "after_sha")
    private String afterSha;

    @Column(name = "pull_request_url")
    private String pullRequestUrl;

    @JsonPropertyDescription("True if this push was a merge commit and was skipped without analysis")
    @Column(name = "ignore_merge_commit", nullable = false)
    private boolean ignoreMergeCommit;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Builder
    public DocSyncResult(
            boolean isOutOfSync,
            String proposedSyncDocs,
            String repositoryFullName,
            String ref,
            String beforeSha,
            String afterSha,
            String pullRequestUrl,
            boolean ignoreMergeCommit,
            OffsetDateTime createdAt) {
        this.isOutOfSync = isOutOfSync;
        this.proposedSyncDocs = proposedSyncDocs;
        this.repositoryFullName = repositoryFullName;
        this.ref = ref;
        this.beforeSha = beforeSha;
        this.afterSha = afterSha;
        this.pullRequestUrl = pullRequestUrl;
        this.ignoreMergeCommit = ignoreMergeCommit;
        this.createdAt = createdAt;
    }
}

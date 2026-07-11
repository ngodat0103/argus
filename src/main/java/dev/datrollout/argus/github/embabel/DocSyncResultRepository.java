package dev.datrollout.argus.github.embabel;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocSyncResultRepository extends JpaRepository<DocSyncResult, Long> {

    /**
     * Whether Argus has already run a real sync for this repository (ignoring skipped merge
     * commits). Used to choose the full initial sync vs the incremental (diff-driven) sync, so a
     * repo whose first event is a merge commit still gets a full initial sync afterwards.
     */
    boolean existsByRepositoryFullNameAndIgnoreMergeCommitFalse(String repositoryFullName);
}

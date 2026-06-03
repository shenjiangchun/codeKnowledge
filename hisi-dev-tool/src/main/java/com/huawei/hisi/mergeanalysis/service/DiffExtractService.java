package com.huawei.hisi.mergeanalysis.service;

import com.huawei.hisi.mergeanalysis.model.DiffResult;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class DiffExtractService {

    public List<String> listBranches(String projectPath) {
        Set<String> branchNames = new LinkedHashSet<>();
        try (Git git = Git.open(new File(projectPath))) {
            List<Ref> localBranches = git.branchList().call();
            for (Ref ref : localBranches) {
                branchNames.add(Repository.shortenRefName(ref.getName()));
            }

            List<Ref> remoteBranches = git.branchList()
                    .setListMode(ListBranchCommand.ListMode.REMOTE)
                    .call();
            for (Ref ref : remoteBranches) {
                branchNames.add(Repository.shortenRefName(ref.getName()));
            }
        } catch (Exception e) {
            log.error("Failed to list branches for {}: {}", projectPath, e.getMessage());
            throw new RuntimeException("Failed to list branches: " + e.getMessage(), e);
        }
        return new ArrayList<>(branchNames);
    }

    public DiffResult extractDiff(String projectPath, String sourceBranch, String targetBranch) {
        try (Git git = Git.open(new File(projectPath))) {
            Repository repository = git.getRepository();

            ObjectId sourceId = resolveRef(repository, sourceBranch);
            ObjectId targetId = resolveRef(repository, targetBranch);

            RevTree sourceTree;
            RevTree targetTree;
            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit sourceCommit = revWalk.parseCommit(sourceId);
                RevCommit targetCommit = revWalk.parseCommit(targetId);
                sourceTree = sourceCommit.getTree();
                targetTree = targetCommit.getTree();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<DiffResult.FileDiff> fileDiffs = new ArrayList<>();
            int totalAdditions = 0;
            int totalDeletions = 0;

            try (DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repository);
                formatter.setDetectRenames(true);

                List<DiffEntry> entries = formatter.scan(targetTree, sourceTree);

                for (DiffEntry entry : entries) {
                    out.reset();
                    formatter.format(entry);
                    String patch = out.toString(StandardCharsets.UTF_8);

                    String filePath = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                            ? entry.getOldPath()
                            : entry.getNewPath();

                    int additions = 0;
                    int deletions = 0;
                    for (String line : patch.split("\n")) {
                        if (line.startsWith("+") && !line.startsWith("+++")) {
                            additions++;
                        } else if (line.startsWith("-") && !line.startsWith("---")) {
                            deletions++;
                        }
                    }

                    fileDiffs.add(DiffResult.FileDiff.builder()
                            .filePath(filePath)
                            .changeType(entry.getChangeType().name())
                            .additions(additions)
                            .deletions(deletions)
                            .patch(patch)
                            .build());

                    totalAdditions += additions;
                    totalDeletions += deletions;
                }
            }

            return DiffResult.builder()
                    .sourceBranch(sourceBranch)
                    .targetBranch(targetBranch)
                    .totalFiles(fileDiffs.size())
                    .totalAdditions(totalAdditions)
                    .totalDeletions(totalDeletions)
                    .files(fileDiffs)
                    .build();

        } catch (Exception e) {
            log.error("Failed to extract diff between {} and {} in {}: {}",
                    sourceBranch, targetBranch, projectPath, e.getMessage());
            throw new RuntimeException("Failed to extract diff: " + e.getMessage(), e);
        }
    }

    private ObjectId resolveRef(Repository repository, String branchName) throws Exception {
        // Strip common prefixes that shortenRefName may leave
        String name = branchName;
        if (name.startsWith("origin/")) {
            String stripped = name.substring("origin/".length());
            ObjectId id = repository.resolve("refs/remotes/origin/" + stripped);
            if (id != null) return id;
        }

        ObjectId id = repository.resolve("refs/heads/" + name);
        if (id != null) return id;

        id = repository.resolve("refs/remotes/origin/" + name);
        if (id != null) return id;

        // Last resort: let JGit resolve it as-is (handles full refs/ paths, HEAD, etc.)
        id = repository.resolve(name);
        if (id != null) return id;

        throw new IllegalArgumentException("Cannot resolve branch: " + branchName);
    }
}

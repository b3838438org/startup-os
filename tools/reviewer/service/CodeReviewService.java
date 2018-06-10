/*
 * Copyright 2018 The StartupOS Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.startupos.tools.reviewer.service;

import com.google.common.flogger.FluentLogger;
import com.google.protobuf.Empty;
import com.google.startupos.common.FileUtils;
import com.google.startupos.common.TextDifferencer;
import com.google.startupos.common.firestore.FirestoreClient;
import com.google.startupos.common.flags.Flag;
import com.google.startupos.common.flags.FlagDesc;
import com.google.startupos.common.repo.GitRepoFactory;
import com.google.startupos.common.repo.Repo;
import com.google.startupos.tools.localserver.service.AuthService;
import com.google.startupos.tools.reviewer.service.Protos.Author;
import com.google.startupos.tools.reviewer.service.Protos.CreateDiffRequest;
import com.google.startupos.tools.reviewer.service.Protos.Diff;
import com.google.startupos.tools.reviewer.service.Protos.DiffNumberResponse;
import com.google.startupos.common.repo.Protos.File;
import com.google.startupos.tools.reviewer.service.Protos.FileRequest;
import com.google.startupos.tools.reviewer.service.Protos.FileResponse;
import com.google.startupos.tools.reviewer.service.Protos.DiffRequest;
import com.google.startupos.tools.reviewer.service.Protos.DiffFilesRequest;
import com.google.startupos.tools.reviewer.service.Protos.DiffFilesResponse;
import com.google.startupos.tools.reviewer.service.Protos.TextDiffRequest;
import com.google.startupos.tools.reviewer.service.Protos.TextDiffResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Paths;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import com.google.startupos.common.repo.Protos.Commit;
import com.google.startupos.common.repo.Protos.BranchInfo;
import com.google.common.collect.ImmutableList;

/*
 * CodeReviewService is a gRPC service (definition in proto/code_review.proto)
 */
@Singleton
public class CodeReviewService extends CodeReviewServiceGrpc.CodeReviewServiceImplBase {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @FlagDesc(name = "firestore_review_root", description = "Review root path in Firestore")
  private static final Flag<String> firestoreReviewRoot = Flag.create("/reviewer");

  private static final String DOCUMENT_FOR_LAST_DIFF_NUMBER = "data";

  private AuthService authService;
  private FileUtils fileUtils;
  private GitRepoFactory repoFactory;
  private String basePath;
  private TextDifferencer textDifferencer;

  @Inject
  public CodeReviewService(
      AuthService authService,
      FileUtils fileUtils,
      @Named("Base path") String basePath,
      GitRepoFactory repoFactory,
      TextDifferencer textDifferencer) {
    this.authService = authService;
    this.fileUtils = fileUtils;
    this.basePath = basePath;
    this.repoFactory = repoFactory;
    this.textDifferencer = textDifferencer;
  }

  private String readTextFile(File file) throws IOException {
    if (file.getWorkspace().isEmpty()) {
      // It's a file in head
      String repoPath = fileUtils.joinPaths(basePath, "head", file.getRepoId());
      Repo repo = repoFactory.create(repoPath);
      return repo.getFileContents(file.getCommitId(), file.getFilename());
    } else {
      // It's a file in a workspace
      if (file.getUser().isEmpty()) {
        // It's the current user
        if (file.getCommitId().isEmpty()) {
          // It's a file in the local filesystem (not in a repo)
          String filePath =
              fileUtils.joinPaths(
                  basePath, "ws", file.getWorkspace(), file.getRepoId(), file.getFilename());
          return fileUtils.readFile(filePath);
        } else {
          // It's a file in a repo
          String repoPath =
              fileUtils.joinPaths(basePath, "ws", file.getWorkspace(), file.getRepoId());
          Repo repo = repoFactory.create(repoPath);
          return repo.getFileContents(file.getCommitId(), file.getFilename());
        }
      } else {
        // It's another user
        if (file.getCommitId().isEmpty()) {
          // It's a file in the local filesystem (not in a repo)
          String filePath =
              fileUtils.joinPaths(
                  basePath,
                  "users",
                  file.getUser(),
                  "ws",
                  file.getWorkspace(),
                  file.getRepoId(),
                  file.getFilename());
          return fileUtils.readFile(filePath);
        } else {
          // It's a file in a repo
          String repoPath =
              fileUtils.joinPaths(
                  basePath, "users", file.getUser(), "ws", file.getWorkspace(), file.getRepoId());
          Repo repo = repoFactory.create(repoPath);
          return repo.getFileContents(file.getCommitId(), file.getFilename());
        }
      }
    }
  }

  private String getAbsolutePath(String relativePath) throws SecurityException {
    // normalize() resolves "../", to help prevent returning files outside rootPath
    String absolutePath = Paths.get(basePath, relativePath).normalize().toString();
    if (!absolutePath.startsWith(basePath)) {
      throw new SecurityException("Resulting path is not under root");
    }

    return absolutePath;
  }

  @Override
  public void getFile(FileRequest req, StreamObserver<FileResponse> responseObserver) {
    try {
      String filePath = fileUtils.joinPaths(basePath, "head", "startup-os", req.getFilename());
      responseObserver.onNext(
          FileResponse.newBuilder().setContent(fileUtils.readFile(filePath)).build());
      responseObserver.onCompleted();
    } catch (SecurityException | IOException e) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription(String.format("No such file %s", req.getFilename()))
              .asException());
    }
  }

  @Override
  public void createDiff(CreateDiffRequest req, StreamObserver<Empty> responseObserver) {
    FirestoreClient client =
        new FirestoreClient(authService.getProjectId(), authService.getToken());
    String diffPath = fileUtils.joinPaths(firestoreReviewRoot.get(), "data/diff");
    Diff diff = req.getDiff().toBuilder()
        .setAuthor(Author.newBuilder().setName(authService.getUserName()).build()).build();
    client.createDocument(diffPath, String.valueOf(diff.getNumber()), diff);
    responseObserver.onNext(Empty.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void getTextDiff(TextDiffRequest req, StreamObserver<TextDiffResponse> responseObserver) {
    try {
      String leftFileContents = readTextFile(req.getLeftFile());
      String rightFileContents = readTextFile(req.getRightFile());
      responseObserver.onNext(
          TextDiffResponse.newBuilder()
              .addAllChanges(textDifferencer.getAllTextChanges(leftFileContents, rightFileContents))
              .setLeftFileContents(leftFileContents)
              .setRightFileContents(rightFileContents)
              .build());
    } catch (IOException e) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription(String.format("TextDiffRequest: %s", req))
              .asException());
    }
    responseObserver.onCompleted();
  }

  // TODO: fix concurrency issues (if two different call method at same time)
  // could be done by wrapping in a transaction
  @Override
  public void getAvailableDiffNumber(
      Empty request, StreamObserver<DiffNumberResponse> responseObserver) {
    FirestoreClient client =
        new FirestoreClient(authService.getProjectId(), authService.getToken());
    DiffNumberResponse diffNumberResponse =
        (DiffNumberResponse)
            client.getDocument(
                firestoreReviewRoot.get() + "/" + DOCUMENT_FOR_LAST_DIFF_NUMBER,
                DiffNumberResponse.newBuilder());
    client.createDocument(
        firestoreReviewRoot.get(),
        DOCUMENT_FOR_LAST_DIFF_NUMBER,
        diffNumberResponse.toBuilder().setLastDiffId(diffNumberResponse.getLastDiffId() + 1));
    responseObserver.onNext(diffNumberResponse);
    responseObserver.onCompleted();
  }

  @Override
  public void getDiff(DiffRequest request, StreamObserver<Protos.Diff> responseObserver) {
    FirestoreClient client =
        new FirestoreClient(authService.getProjectId(), authService.getToken());

    String diffPath =
        fileUtils.joinPaths(
            firestoreReviewRoot.get(), "data/diff", String.valueOf(request.getDiffId()));
    Diff diff = (Diff) client.getDocument(diffPath, Diff.newBuilder());

    responseObserver.onNext(diff);
    responseObserver.onCompleted();
  }

  @Override
  public void getDiffFiles(DiffFilesRequest request, StreamObserver<DiffFilesResponse> responseObserver) {
    String workspacePath = fileUtils.joinPaths(basePath, "ws", request.getWorkspace());
    String branch = "D" + request.getDiffId();
    DiffFilesResponse.Builder response = DiffFilesResponse.newBuilder();
    try {
      fileUtils
          .listContents(workspacePath)
          .stream()
          .map(path -> fileUtils.joinPaths(workspacePath, path))
          .filter(path -> fileUtils.folderExists(path))
          .forEach(
              path -> {
                String repoName = Paths.get(path).getFileName().toString();
                Repo repo = repoFactory.create(path);
                ImmutableList<Commit> commits = repo.getCommits(branch);
                ImmutableList<File> uncommittedFiles = repo.getUncommittedFiles();
                response.addBranchInfo(BranchInfo.newBuilder()
                    .setDiffId(request.getDiffId())
                    .setRepoId(repoName)
                    .addAllCommit(commits)
                    .addAllUncommittedFile(uncommittedFiles)
                    .build());
              });
    } catch (IOException e) {
      e.printStackTrace();
    }

    logger.atInfo().log("DiffFiles request\n" + request.toString());
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  } 
}

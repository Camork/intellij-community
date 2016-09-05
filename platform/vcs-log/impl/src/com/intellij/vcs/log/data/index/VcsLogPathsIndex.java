/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.vcs.log.data.index;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Couple;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PathUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.StorageException;
import com.intellij.util.io.*;
import com.intellij.vcs.log.VcsFullCommitDetails;
import com.intellij.vcs.log.impl.VcsChangesLazilyParsedDetails;
import com.intellij.vcs.log.util.PersistentUtil;
import gnu.trove.THashMap;
import gnu.trove.TIntHashSet;
import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class VcsLogPathsIndex extends VcsLogFullDetailsIndex<Integer> {
  private static final Logger LOG = Logger.getInstance(VcsLogPathsIndex.class);
  private static final String NAME = "paths";
  private static final int VALUE = 239;

  @NotNull private final PersistentHashMap<Integer, Integer> myEmptyCommits;
  @NotNull private final PathsIndexer myPathsIndexer;

  public VcsLogPathsIndex(@NotNull String logId,
                          @NotNull Set<VirtualFile> roots,
                          @NotNull Disposable disposableParent) throws IOException {
    super(logId, NAME, VcsLogPersistentIndex.getVersion(), new PathsIndexer(
            PersistentUtil.createPersistentEnumerator(EnumeratorStringDescriptor.INSTANCE, "index-paths-ids", logId,
                                                      VcsLogPersistentIndex.getVersion()), roots),
          new NullableIntKeyDescriptor(), disposableParent);

    myEmptyCommits = PersistentUtil.createPersistentHashMap(EnumeratorIntegerDescriptor.INSTANCE, "index-no-" + NAME, logId,
                                                            VcsLogPersistentIndex.getVersion());
    myPathsIndexer = (PathsIndexer)myIndexer;
  }

  @Override
  protected void onNotIndexableCommit(int commit) throws StorageException {
    try {
      myEmptyCommits.put(commit, VALUE);
    }
    catch (IOException e) {
      throw new StorageException(e);
    }
  }

  @Override
  public boolean isIndexed(int commit) throws IOException {
    return super.isIndexed(commit) || myEmptyCommits.containsMapping(commit);
  }

  @Override
  public void flush() throws StorageException {
    super.flush();
    myEmptyCommits.force();
    myPathsIndexer.getPathsEnumerator().force();
  }

  public TIntHashSet getCommitsForPaths(@NotNull Collection<FilePath> paths) throws IOException, StorageException {
    Set<Integer> allPathIds = ContainerUtil.newHashSet();
    for (FilePath path : paths) {
      allPathIds.add(myPathsIndexer.myPathsEnumerator.enumerate(path.getPath()));
    }

    TIntHashSet result = new TIntHashSet();
    Set<Integer> renames = allPathIds;
    while (!renames.isEmpty()) {
      renames = addCommitsAndGetRenames(renames, allPathIds, result);
      allPathIds.addAll(renames);
    }

    return result;
  }

  @NotNull
  public Set<Integer> addCommitsAndGetRenames(@NotNull Set<Integer> newPathIds,
                                              @NotNull Set<Integer> allPathIds,
                                              @NotNull TIntHashSet commits)
    throws StorageException {
    Set<Integer> renames = ContainerUtil.newHashSet();
    for (Integer key : newPathIds) {
      iterateCommitIdsAndValues(key, (value, commit) -> {
        commits.add(commit);
        if (value != null && !allPathIds.contains(value)) {
          renames.add(value);
        }
      });
    }
    return renames;
  }

  @Override
  public void dispose() {
    super.dispose();
    try {
      myEmptyCommits.close();
    }
    catch (IOException e) {
      LOG.warn(e);
    }
    try {
      myPathsIndexer.getPathsEnumerator().close();
    }
    catch (IOException e) {
      LOG.warn(e);
    }
  }

  private static class PathsIndexer implements DataIndexer<Integer, Integer, VcsFullCommitDetails> {
    @NotNull private final PersistentEnumeratorBase<String> myPathsEnumerator;
    @NotNull private final Set<String> myRoots;

    private PathsIndexer(@NotNull PersistentEnumeratorBase<String> enumerator, @NotNull Set<VirtualFile> roots) {
      myPathsEnumerator = enumerator;
      myRoots = roots.stream().map(VirtualFile::getPath).collect(Collectors.toSet());
    }

    @NotNull
    @Override
    public Map<Integer, Integer> map(@NotNull VcsFullCommitDetails inputData) {
      Map<Integer, Integer> result = new THashMap<>();


      Collection<Couple<String>> moves;
      Collection<String> changedPaths;
      if (inputData instanceof VcsChangesLazilyParsedDetails) {
        changedPaths = ((VcsChangesLazilyParsedDetails)inputData).getModifiedPaths();
        moves = ((VcsChangesLazilyParsedDetails)inputData).getRenamedPaths();
      }
      else {
        moves = ContainerUtil.newHashSet();
        changedPaths = ContainerUtil.newHashSet();
        for (Change change : inputData.getChanges()) {
          if (change.getAfterRevision() != null) changedPaths.add(change.getAfterRevision().getFile().getPath());
          if (change.getBeforeRevision() != null) changedPaths.add(change.getBeforeRevision().getFile().getPath());
          if (change.getType().equals(Change.Type.MOVED)) {
            moves.add(Couple.of(change.getBeforeRevision().getFile().getPath(), change.getAfterRevision().getFile().getPath()));
          }
        }
      }

      getParentPaths(changedPaths).forEach(changedPath -> {
        try {
          result.put(myPathsEnumerator.enumerate(changedPath), null);
        }
        catch (IOException e) {
          LOG.error(e);
        }
      });
      moves.forEach(renamedPaths -> {
        try {
          int beforeId = myPathsEnumerator.enumerate(renamedPaths.first);
          int afterId = myPathsEnumerator.enumerate(renamedPaths.second);

          result.put(beforeId, afterId);
          result.put(afterId, beforeId);
        }
        catch (IOException e) {
          LOG.error(e);
        }
      });

      return result;
    }

    @NotNull
    private Collection<String> getParentPaths(@NotNull Collection<String> paths) {
      Set<String> result = ContainerUtil.newHashSet();
      for (String path : paths) {
        while (!path.isEmpty() && !result.contains(path)) {
          result.add(path);
          if (myRoots.contains(path)) break;

          path = PathUtil.getParentPath(path);
        }
      }
      return result;
    }

    @NotNull
    public PersistentEnumeratorBase<String> getPathsEnumerator() {
      return myPathsEnumerator;
    }
  }

  private static class NullableIntKeyDescriptor implements DataExternalizer<Integer> {
    @Override
    public void save(@NotNull DataOutput out, Integer value) throws IOException {
      if (value == null) {
        out.writeBoolean(false);
      }
      else {
        out.writeBoolean(true);
        out.writeInt(value);
      }
    }

    @Override
    public Integer read(@NotNull DataInput in) throws IOException {
      if (in.readBoolean()) {
        return in.readInt();
      }
      return null;
    }
  }
}

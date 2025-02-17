/*******************************************************************************
 * Copyright (c) 2008-2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *      Sonatype, Inc. - initial API and implementation
 *******************************************************************************/

package org.eclipse.m2e.core.internal.project.registry;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;
import org.eclipse.aether.version.VersionConstraint;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

import org.apache.maven.repository.LocalArtifactRepository;

import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.ArtifactKey;
import org.eclipse.m2e.core.project.IWorkspaceClassifierResolver;


public final class EclipseWorkspaceArtifactRepository extends LocalArtifactRepository implements WorkspaceReader {
  private static final String POM_EXTENSION = "pom"; //$NON-NLS-1$

  private static final GenericVersionScheme versionScheme = new GenericVersionScheme();

  private final transient ProjectRegistryManager.Context context;

  private static final ThreadLocal<Boolean> disabled = new ThreadLocal<>();

  private final WorkspaceRepository workspaceRepository;

  public EclipseWorkspaceArtifactRepository(ProjectRegistryManager.Context context) {
    this.context = context;
    this.workspaceRepository = new WorkspaceRepository("ide", getClass()); //$NON-NLS-1$
  }

  protected File resolveAsEclipseProject(String groupId, String artifactId, String baseVersion, String classifier,
      String extension) {
    if(isDisabled()) {
      return null;
    }

    if(context == null) { // XXX this is actually a bug
      return null;
    }

    // check in the workspace, note that workspace artifacts never have classifiers
    IFile pom = getWorkspaceArtifact(groupId, artifactId, baseVersion);
    if(pom == null || !pom.isAccessible()) {
      return null;
    }
    if(context.pom != null && pom.equals(context.pom) && !POM_EXTENSION.equals(extension)) {
      return null;
    }

    if(context.resolverConfiguration.shouldResolveWorkspaceProjects()) {
      IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
      IPath file = pom.getLocation();
      if(file == null) {
        return ProjectRegistryManager.toJavaIoFile(pom);
      }
      if(!POM_EXTENSION.equals(extension)) {
        MavenProjectFacade facade = context.state.getProjectFacade(pom);

        IWorkspaceClassifierResolver resolver = MavenPlugin.getWorkspaceClassifierResolverManager().getResolver();
        IPath location = resolver.resolveClassifier(facade, classifier);

        if(location == null) {
          location = facade.getOutputLocation();
        }

        if(location != null) {
          IResource res = root.findMember(location);
          if(res != null) {
            file = res.getLocation();
          }
        }
      }

      return file.toFile();
    }

    return null;
  }

  private IFile getWorkspaceArtifact(String groupId, String artifactId, String version) {
    Map<ArtifactKey, Collection<IFile>> workspaceArtifacts = context.state.getWorkspaceArtifacts(groupId, artifactId);
    if(workspaceArtifacts.isEmpty()) {
      return null;
    }
    VersionConstraint constraint;
    try {
      constraint = versionScheme.parseVersionConstraint(version);
    } catch(InvalidVersionSpecificationException e) {
      return null; // broken version range spec does not match anything
    }
    TreeMap<Version, ArtifactKey> matchingArtifacts = new TreeMap<>();
    // in vast majority of cases there will be single workspace artifact with matching groupId and artifactId
    for(ArtifactKey workspaceArtifact : workspaceArtifacts.keySet()) {
      try {
        Version workspaceVersion = versionScheme.parseVersion(workspaceArtifact.version());
        if(constraint.containsVersion(workspaceVersion)) {
          matchingArtifacts.put(workspaceVersion, workspaceArtifact);
        }
      } catch(InvalidVersionSpecificationException e) {
        // this can't happen with GenericVersionScheme
      }
    }
    if(matchingArtifacts.isEmpty()) {
      return null;
    }
    ArtifactKey matchingArtifact = matchingArtifacts.values().iterator().next();
    return workspaceArtifacts.get(matchingArtifact).iterator().next();
  }

  @Override
  public File findArtifact(Artifact artifact) {
    return resolveAsEclipseProject(artifact.getGroupId(), artifact.getArtifactId(), artifact.getBaseVersion(),
        artifact.getClassifier(), artifact.getExtension());
  }

  @Override
  public org.apache.maven.artifact.Artifact find(org.apache.maven.artifact.Artifact artifact) {
    File file = resolveAsEclipseProject(artifact.getGroupId(), artifact.getArtifactId(), artifact.getBaseVersion(),
        artifact.getClassifier(), artifact.getType());

    if(file != null) {
      artifact.setFile(file);
      artifact.setResolved(true);
    }

    return artifact;
  }

  @Override
  public boolean hasLocalMetadata() {
    return false; // XXX
  }

  public interface Disabled extends AutoCloseable {
    @Override
    void close();
  }

  public static Disabled setDisabled() {
    boolean isDisabled = isDisabled();
    disabled.set(true);
    return () -> disabled.set(isDisabled);
  }

  public static boolean isDisabled() {
    return Boolean.TRUE.equals(disabled.get());
  }

  @Override
  public int hashCode() {
    return 0; // no state
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof EclipseWorkspaceArtifactRepository;
  }

  @Override
  public List<String> findVersions(Artifact artifact) {
    return findVersions(artifact.getGroupId(), artifact.getArtifactId());
  }

  @Override
  public List<String> findVersions(org.apache.maven.artifact.Artifact artifact) {
    return findVersions(artifact.getGroupId(), artifact.getArtifactId());
  }

  private List<String> findVersions(String groupId, String artifactId) {
    ArrayList<String> versions = new ArrayList<>();

    if(isDisabled()) {
      return versions;
    }

    if(context == null) { // XXX this is actually a bug
      return versions;
    }

    for(MavenProjectFacade facade : context.state.getProjects()) {
      ArtifactKey artifactKey = facade.getArtifactKey();
      if(groupId.equals(artifactKey.groupId()) && artifactId.equals(artifactKey.artifactId())) {
        versions.add(artifactKey.version());
      }
    }

    return versions;
  }

  @Override
  public WorkspaceRepository getRepository() {
    return workspaceRepository;
  }

}

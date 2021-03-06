// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.history;

import com.intellij.ide.DataManager;
import com.intellij.ide.impl.DataManagerImpl;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.VcsInternalDataKeys;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsBundle;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.ui.VcsBalloonProblemNotifier;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.BufferedListConsumer;
import com.intellij.util.Consumer;
import com.intellij.util.ContentUtilEx;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.CalledInBackground;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

import static com.intellij.openapi.vcs.history.FileHistoryPanelImpl.sameHistories;

public class FileHistorySessionPartner implements VcsHistorySessionConsumer, Disposable {

  @NotNull private final AbstractVcs myVcs;
  @NotNull private final VcsHistoryProvider myVcsHistoryProvider;
  @NotNull private final FilePath myPath;
  @Nullable private final VcsRevisionNumber myStartingRevisionNumber;
  @NotNull private final FileHistoryRefresherI myRefresher;
  @NotNull private final LimitHistoryCheck myLimitHistoryCheck;
  @NotNull private final BufferedListConsumer<VcsFileRevision> myBuffer;
  @NotNull private final FileHistoryContentPanel myContentPanel;
  private volatile VcsAbstractHistorySession mySession = null;

  public FileHistorySessionPartner(@NotNull VcsHistoryProvider vcsHistoryProvider,
                                   @NotNull FilePath path,
                                   @Nullable VcsRevisionNumber startingRevisionNumber,
                                   @NotNull AbstractVcs vcs,
                                   @NotNull FileHistoryRefresherI refresher) {
    myVcsHistoryProvider = vcsHistoryProvider;
    myPath = path;
    myStartingRevisionNumber = startingRevisionNumber;
    myRefresher = refresher;
    myLimitHistoryCheck = new LimitHistoryCheck(vcs.getProject(), path.getPath());
    myVcs = vcs;
    myContentPanel = new FileHistoryContentPanel();

    Consumer<List<VcsFileRevision>> sessionRefresher = vcsFileRevisions -> {
      // TODO: Logic should be revised to just append some revisions to history panel instead of creating and showing new history session
      mySession.getRevisionList().addAll(vcsFileRevisions);
      VcsHistorySession copy = mySession.copyWithCachedRevision();
      ApplicationManager.getApplication().invokeLater(() -> myContentPanel.setHistorySession(copy));
    };
    myBuffer = new BufferedListConsumer<VcsFileRevision>(5, sessionRefresher, 1000) {
      @Override
      protected void invokeConsumer(@NotNull Runnable consumerRunnable) {
        // Do not invoke in arbitrary background thread as due to parallel execution this could lead to cases when invokeLater() (from
        // sessionRefresher) is scheduled at first for history session with (as an example) 10 revisions (new buffered list) and then with
        // 5 revisions (previous buffered list). And so incorrect UI is shown to the user.
        consumerRunnable.run();
      }
    };
  }

  @Nullable
  static FileHistoryRefresherI findExistingHistoryRefresher(@NotNull Project project,
                                                            @NotNull FilePath path,
                                                            @Nullable VcsRevisionNumber startingRevisionNumber) {
    JComponent component = ContentUtilEx.findContentComponent(getToolWindow(project).getContentManager(), comp ->
      comp instanceof FileHistoryContentPanel &&
      sameHistories(((FileHistoryContentPanel)comp).getPath(), ((FileHistoryContentPanel)comp).getRevision(), path,
                    startingRevisionNumber));
    if (component == null) return null;
    DataProvider dataProvider = DataManagerImpl.getDataProviderEx(component);
    if (dataProvider == null) return null;
    return VcsInternalDataKeys.FILE_HISTORY_REFRESHER.getData(dataProvider);
  }

  @CalledInBackground
  public boolean shouldBeRefreshed() {
    return mySession.shouldBeRefreshed();
  }

  @Override
  public void acceptRevision(VcsFileRevision revision) {
    myLimitHistoryCheck.checkNumber();
    myBuffer.consumeOne(revision);
  }

  @Override
  public void reportCreatedEmptySession(VcsAbstractHistorySession session) {
    if (mySession != null && session != null && mySession.getRevisionList().equals(session.getRevisionList())) return;
    mySession = session;
    if (mySession != null) {
      mySession.shouldBeRefreshed();  // to init current revision!

      List<VcsFileRevision> revisionList = mySession.getRevisionList();
      while (myLimitHistoryCheck.isOver(revisionList.size())) revisionList.remove(revisionList.size() - 1);
    }

    ApplicationManager.getApplication().invokeAndWait(() -> {
      if (mySession != null) {
        myContentPanel.setHistorySession(mySession.copyWithCachedRevision());
      }
    });
  }

  @NotNull
  private static ToolWindow getToolWindow(@NotNull Project project) {
    ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS);
    assert toolWindow != null : "Version Control ToolWindow should be available at this point.";
    return toolWindow;
  }

  @Override
  public void reportException(VcsException exception) {
    VcsBalloonProblemNotifier.showOverVersionControlView(myVcs.getProject(),
                                                         VcsBundle.message("message.title.could.not.load.file.history") + ": " +
                                                         exception.getMessage(), MessageType.ERROR);
  }

  @Override
  public void beforeRefresh() {
    myLimitHistoryCheck.reset();
  }

  public void createOrSelectContent() {
    ToolWindow toolWindow = getToolWindow(myVcs.getProject());
    ContentManager manager = toolWindow.getContentManager();
    boolean selectedExistingContent = ContentUtilEx.selectContent(manager, myContentPanel, true);
    if (!selectedExistingContent) {
      String tabName = myPath.getName();
      if (myStartingRevisionNumber != null) {
        tabName += " (" + VcsUtil.getShortRevisionString(myStartingRevisionNumber) + ")";
      }
      ContentUtilEx.addTabbedContent(manager, myContentPanel, "History", tabName, true, this);
    }
    toolWindow.activate(null);
  }

  @Override
  public void finished() {
    myBuffer.flush();
    ApplicationManager.getApplication().invokeAndWait(() -> {
      if (mySession == null) {
        // nothing to be done, exit
        return;
      }
      myContentPanel.finishRefresh();
    });
  }

  @Override
  public void dispose() {
  }

  private class FileHistoryContentPanel extends JBPanel {
    @Nullable private FileHistoryPanelImpl myFileHistoryPanel;

    private FileHistoryContentPanel() {
      super(new BorderLayout());
    }

    public void setHistorySession(@NotNull VcsHistorySession session) {
      if (myFileHistoryPanel == null) {
        myFileHistoryPanel = new FileHistoryPanelImpl(myVcs, myPath, myStartingRevisionNumber, session, myVcsHistoryProvider,
                                                      myRefresher, false);
        add(myFileHistoryPanel, BorderLayout.CENTER);
        DataManager.registerDataProvider(this, myFileHistoryPanel);
        Disposer.register(FileHistorySessionPartner.this, myFileHistoryPanel);
      }
      else if (!session.getRevisionList().isEmpty()) {
        myFileHistoryPanel.setHistorySession(session);
      }
    }

    public void finishRefresh() {
      if (myFileHistoryPanel != null) myFileHistoryPanel.finishRefresh();
    }

    @NotNull
    public FilePath getPath() {
      return myPath;
    }

    @Nullable
    public VcsRevisionNumber getRevision() {
      return myStartingRevisionNumber;
    }
  }
}

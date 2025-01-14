/*
 * Copyright (c) 1998-2018 University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package ucar.ui.widget;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.event.EventListenerList;

/**
 * This wraps a javax.swing.ProgressMonitor, which allows tasks to be canceled. This class adds
 * extra behavior to javax.swing.ProgressMonitor:
 * <ol>
 * <li> Pass in the ProgressMonitorTask you want to monitor.
 * <li> Throws an actionEvent (on the AWT event thread) when the task is done.
 * <li> If an error, pops up an error message.
 * <li> Get status: success/failed/cancel when task is done.
 * </ol>
 * <p/>
 * The ProgressMonitorTask is run in a background thread while a javax.swing.ProgressMonitor dialog
 * box shows progress. The task is checked every second to see if its done or canceled.
 * <p/>
 * <pre>
 * Example:
 *
 * AddDatasetTask task = new AddDatasetTask(datasets);
 * ProgressMonitor pm = new ProgressMonitor(task);
 * pm.addActionListener( new ActionListener() {
 *  public void actionPerformed(ActionEvent e) {
 *   if (e.getActionCommand().equals("success")) {
 *     doGoodStuff();
 *   }
 *  }
 * });
 * pm.start( this, "Add Datasets", datasets.size());
 *
 * (or)
 *
 * AddDatasetTask task = new AddDatasetTask(datasets);
 * ProgressMonitor pm = new ProgressMonitor(task, () -> doGoodStuff());
 * pm.start( this, "Add Datasets", datasets.size());
 * </pre>
 *
 * @author jcaron
 * {@link ProgressMonitorTask}
 */

public class ProgressMonitor {
  private javax.swing.ProgressMonitor pm;
  private ProgressMonitorTask task;
  private javax.swing.Timer timer;
  private int secs = 0;

  // event handling
  private EventListenerList listenerList = new EventListenerList();

  public ProgressMonitor(ProgressMonitorTask task) {
    this(task, null);
  }

  // Add a successListener to be called on event type "success".
  public ProgressMonitor(ProgressMonitorTask task, ActionListener successListener) {
    this.task = task;
    if (successListener != null) {
      addActionListener(new ActionListenerAdapter("success", successListener));
    }
  }

  public ProgressMonitorTask getTask() {
    return task;
  }

  /**
   * Add listener: action event sent when task is done. event.getActionCommand() =
   * <ul><li> "success"
   * <li> "error"
   * <li> "cancel"
   * <li> "done" if done, but success/error/cancel not set
   * </ul>
   */
  public void addActionListener(ActionListener l) {
    listenerList.add(ActionListener.class, l);
  }

  public void removeActionListener(ActionListener l) {
    listenerList.remove(ActionListener.class, l);
  }

  private void fireEvent(ActionEvent event) {
    // Guaranteed to return a non-null array
    Object[] listeners = listenerList.getListenerList();
    // Process the listeners last to first
    for (int i = listeners.length - 2; i >= 0; i -= 2) {
      ((ActionListener) listeners[i + 1]).actionPerformed(event);
    }
  }

  /**
   * Call this from awt event thread. The task is run in a background thread.
   *
   * @param top put ProgressMonitor on top of this component (may be null)
   * @param taskName display name of task
   * @param progressMaxCount maximum number of Progress indicator
   */
  public void start(java.awt.Component top, String taskName, int progressMaxCount) {
    // create ProgressMonitor
    pm = new javax.swing.ProgressMonitor(top, taskName, "", 0, progressMaxCount);
    pm.setMillisToDecideToPopup(1000);
    pm.setMillisToPopup(1000);

    // do task in a seperate, non-event, thread
    Thread taskThread = new Thread(task);
    taskThread.start();

    // create timer, whose events happen on the awt event Thread
    ActionListener watcher = new ActionListener() {
      public void actionPerformed(ActionEvent evt) {
        secs++;
        if (pm.isCanceled()) {
          task.cancel();
        } else {
          // indicate progress
          String note = task.getNote();
          pm.setNote(note == null ? secs + " secs" : note);
          int progress = task.getProgress();
          pm.setProgress(progress <= 0 ? secs : progress);
        }

        // need to make sure task acknowledges the cancel; so dont shut down
        // until the task is done
        if (task.isDone()) {
          timer.stop();
          pm.close();

          if (task.isError()) {
            javax.swing.JOptionPane.showMessageDialog(null, task.getErrorMessage());
          }

          if (task.isSuccess()) {
            fireEvent(new ActionEvent(this, 0, "success"));
          } else if (task.isError()) {
            fireEvent(new ActionEvent(this, 0, "error"));
          } else if (task.isCancel()) {
            fireEvent(new ActionEvent(this, 0, "cancel"));
          } else {
            fireEvent(new ActionEvent(this, 0, "done"));
          }
        }
      }
    };

    timer = new javax.swing.Timer(1000, watcher); // every second
    timer.start();
  }
}

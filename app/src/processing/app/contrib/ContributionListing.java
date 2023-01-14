/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Part of the Processing project - http://processing.org

  Copyright (c) 2013-16 The Processing Foundation
  Copyright (c) 2011-12 Ben Fry and Casey Reas

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License version 2
  as published by the Free Software Foundation.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along
  with this program; if not, write to the Free Software Foundation, Inc.
  59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package processing.app.contrib;

import java.awt.EventQueue;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import processing.app.Base;
import processing.app.UpdateCheck;
import processing.app.Util;
import processing.core.PApplet;
import processing.data.StringDict;
import processing.data.StringList;


public class ContributionListing {
  static volatile ContributionListing singleInstance;

  /**
   * Stable URL that will redirect to wherever the file is hosted.
   * Changed to use https in 4.0 beta 8 (returns same data).
   */
  static final String LISTING_URL = "https://download.processing.org/contribs";
  static final String LOCAL_FILENAME = "contribs.txt";

  /** Location of the listing file on disk, will be read and written. */
  private File listingFile;
  private boolean listDownloaded;
//  boolean listDownloadFailed;
  private ReentrantLock downloadingLock;

  final Set<AvailableContribution> availableContribs;
  private Map<String, Contribution> importToLibrary;
  private Set<Contribution> allContribs;

  Set<ListPanel> listPanels;


  private ContributionListing() {
    listPanels = new HashSet<>();
    availableContribs = new HashSet<>();
    importToLibrary = new HashMap<>();
    allContribs = ConcurrentHashMap.newKeySet();
    downloadingLock = new ReentrantLock();

    listingFile = Base.getSettingsFile(LOCAL_FILENAME);
    if (listingFile.exists()) {
      // On the EDT already, but do this later on the EDT so that the
      // constructor can finish more efficiently inside getInstance().
      EventQueue.invokeLater(() -> loadAvailableList(listingFile));
    }
  }


  static public ContributionListing getInstance() {
    if (singleInstance == null) {
      synchronized (ContributionListing.class) {
        if (singleInstance == null) {
          singleInstance = new ContributionListing();
        }
      }
    }
    return singleInstance;
  }


  static protected Set<Contribution> getAllContribs() {
    return getInstance().allContribs;
  }


  /**
   * Update the list of contribs with entries for what is installed.
   * If it matches an entry from contribs.txt, replace that entry.
   * If not, add it to the list as a new contrib.
   */
  protected void updateInstalled(Set<Contribution> installed) {
//    Map<Contribution, Contribution> replacements = new HashMap<>();
//    Set<Contribution> additions = new HashSet<>();

    for (Contribution contribution : installed) {
      Contribution existingContribution = findContribution(contribution);
      if (existingContribution != null) {
        if (existingContribution != contribution) {
          // don't replace contrib with itself
          replaceContribution(existingContribution, contribution);
//          replacements.put(existingContribution, contribution);
        }
      } else {
        addContribution(contribution);
//        additions.add(contribution);
      }
    }

//    for (Contribution existing : replacements.keySet()) {
//      replaceContribution(existing, replacements.get(existing));
//    }
//    for (Contribution adding : additions) {
//      addContribution(adding);
//    }
  }


  private Contribution findContribution(Contribution contribution) {
    for (Contribution c : allContribs) {
      if (c.getName().equals(contribution.getName()) &&
        c.getType() == contribution.getType()) {
        return c;
      }
    }
    return null;
  }


  // This could just be a remove followed by an add, but contributionChanged()
  // is a little weird, so that should be cleaned up first [fry 230114]
  protected void replaceContribution(Contribution oldContrib, Contribution newContrib) {
    if (oldContrib != null && newContrib != null) {
      if (oldContrib.getImports() != null) {
        for (String importName : oldContrib.getImports()) {
          importToLibrary.remove(importName);
        }
      }
      if (newContrib.getImports() != null) {
        for (String importName : newContrib.getImports()) {
          importToLibrary.put(importName, newContrib);
        }
      }
      allContribs.remove(oldContrib);
      allContribs.add(newContrib);

      for (ListPanel listener : listPanels) {
        listener.contributionChanged(oldContrib, newContrib);
      }
    }
  }


  private void addContribution(Contribution contribution) {
    if (contribution.getImports() != null) {
      for (String importName : contribution.getImports()) {
        getLibraryImportMap().put(importName, contribution);
      }
    }
    allContribs.add(contribution);

    for (ListPanel listener : listPanels) {
      listener.contributionAdded(contribution);
    }
  }


  protected void removeContribution(Contribution contribution) {
    if (contribution.getImports() != null) {
      for (String importName : contribution.getImports()) {
        getLibraryImportMap().remove(importName);
      }
    }
    allContribs.remove(contribution);

    for (ListPanel listener : listPanels) {
      listener.contributionRemoved(contribution);
    }
  }


  /**
   * Given a contribution that's already installed, find it in the list
   * of available contributions to see if there is an update available.
   */
  protected AvailableContribution findAvailableContribution(Contribution contrib) {
    synchronized (availableContribs) {
      for (AvailableContribution advertised : availableContribs) {
        if (advertised.getType() == contrib.getType() &&
            advertised.getName().equals(contrib.getName())) {
          return advertised;
        }
      }
    }
    return null;
  }


  // formerly addListener(), but the ListPanel was the only Listener
  protected void addListPanel(ListPanel listener) {
    listPanels.add(listener);
  }


  /**
   * Starts a new thread to download the advertised list of contributions.
   * Only one instance will run at a time.
   */
  public void downloadAvailableList(final Base base,
                                    final ContribProgress progress) {

    // TODO: replace with SwingWorker [jv]
    new Thread(() -> {
      downloadingLock.lock();

      try {
        URL url = new URL(LISTING_URL);
        File tempContribFile = Base.getSettingsFile("contribs.tmp");
        if (tempContribFile.exists() && !tempContribFile.canWrite()) {
          if (!tempContribFile.setWritable(true, false)) {
            System.err.println("Could not set " + tempContribFile + " writable");
          }
        }
        ContributionManager.download(url, makeContribsBlob(base),
                                     tempContribFile, progress);
        if (progress.notCanceled() && !progress.isException()) {
          if (listingFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            listingFile.delete();  // may silently fail, but below may still work
          }
          if (tempContribFile.renameTo(listingFile)) {
            listDownloaded = true;
//            listDownloadFailed = false;
            try {
              // TODO: run this in SwingWorker done() [jv]
              EventQueue.invokeAndWait(() -> {
                loadAvailableList(listingFile);
                base.tallyUpdatesAvailable();
              });
            } catch (InterruptedException e) {
              e.printStackTrace();
            } catch (InvocationTargetException e) {
              Throwable cause = e.getCause();
              if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
              } else {
                cause.printStackTrace();
              }
            }
//          } else {
//            listDownloadFailed = true;
          }
        }

      } catch (MalformedURLException e) {
        progress.setException(e);
        progress.finished();
      } finally {
        downloadingLock.unlock();
      }
    }, "Contribution List Downloader").start();
  }


  protected boolean isDownloaded() {
    return listDownloaded;
  }


  private void loadAvailableList(File file) {
    listingFile = file;

    availableContribs.clear();
    availableContribs.addAll(parseContribList(listingFile));
    for (Contribution contribution : availableContribs) {
      addContribution(contribution);
    }
  }


  /**
   * Bundles information about what contribs are installed, so that they can
   * be reported at the <a href="https://download.processing.org/stats/">stats</a> link.
   * (Eventually this may also be used to show relative popularity of contribs.)
   * Read more about it <a href="https://github.com/processing/processing4/wiki/FAQ#checking-for-updates-or-why-is-processing-connecting-to-the-network">in the FAQ</a>.</a>
   */
  private byte[] makeContribsBlob(Base base) {
    Set<Contribution> contribs = base.getInstalledContribs();
    StringList entries = new StringList();
    for (Contribution c : contribs) {
      String entry = c.getTypeName() + "=" +
        PApplet.urlEncode(String.format("name=%s\nurl=%s\nrevision=%d\nversion=%s",
          c.getName(), c.getUrl(),
          c.getVersion(), c.getBenignVersion()));
      entries.append(entry);
    }
    String joined =
      "id=" + UpdateCheck.getUpdateID() + "&" + entries.join("&");
    return joined.getBytes();
  }


  public boolean hasUpdates(Contribution contrib) {
    if (contrib.isInstalled()) {
      Contribution available = findAvailableContribution(contrib);
      return available != null &&
        (available.getVersion() > contrib.getVersion() &&
         available.isCompatible(Base.getRevision()));
    }
    return false;
  }


  /**
   * Get the human-readable version number from the available list.
   */
  protected String getLatestPrettyVersion(Contribution contrib) {
    Contribution newestContrib = findAvailableContribution(contrib);
    if (newestContrib != null) {
      return newestContrib.getPrettyVersion();
    }
    return null;
  }


  static private List<AvailableContribution> parseContribList(File file) {
    List<AvailableContribution> outgoing = new ArrayList<>();

    if (file != null && file.exists()) {
      String[] lines = PApplet.loadStrings(file);
      if (lines != null) {
        int start = 0;
        while (start < lines.length) {
          String type = lines[start];
          ContributionType contribType = ContributionType.fromName(type);
          if (contribType == null) {
            System.err.println("Error in contribution listing file on line " + (start + 1));
            // Scan forward for the next blank line
            int end = ++start;
            while (end < lines.length && !lines[end].trim().isEmpty()) {
              end++;
            }
            start = end + 1;

          } else {
            // Scan forward for the next blank line
            int end = ++start;
            while (end < lines.length && !lines[end].trim().isEmpty()) {
              end++;
            }

            String[] contribLines = PApplet.subset(lines, start, end - start);
            StringDict contribParams = Util.readSettings(file.getName(), contribLines, false);
            outgoing.add(new AvailableContribution(contribType, contribParams));
            start = end + 1;
          }
        }
      }
    }
    return outgoing;
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .


  /**
   * Used by JavaEditor to auto-import. Not known to be used by other Modes.
   */
  public Map<String, Contribution> getLibraryImportMap() {
    return importToLibrary;
  }
}

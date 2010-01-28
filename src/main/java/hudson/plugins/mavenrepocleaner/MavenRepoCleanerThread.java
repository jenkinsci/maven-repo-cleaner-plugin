/*
 * The MIT License
 * 
 * Copyright (c) 2004-2010, Andrew Bayer
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.mavenrepocleaner;

import antlr.ANTLRException;

import hudson.FilePath;
import hudson.Util;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.Serializable;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.logging.Logger;

/**
 * Clean up Maven repositories
 *
 * @author Andrew Bayer
 */
@Extension
public class MavenRepoCleanerThread extends AsyncPeriodicWork {
    private static MavenRepoCleanerThread theInstance;
    private final Calendar cal = new GregorianCalendar();

    // so that this can be easily accessed from sub-routine.
    private TaskListener listener;

    public MavenRepoCleanerThread() {
        super("maven-repo-cleanup");
        theInstance = this;
    }
    
    
    public long getRecurrencePeriod() {
        return HOUR;
    }
    
    protected void execute(TaskListener listener) throws InterruptedException, IOException {
        try {
            if(disabled) {
                LOGGER.warning("Disabled. Skipping execution");
                return;
            }
            
            this.listener = listener;
            
            while (new Date().getTime() - cal.getTimeInMillis() > 1000) {
                try {
                    checkTriggers(cal);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
                
                cal.add(Calendar.MINUTE, 1);
            }
        } finally {
            this.listener = null;
        }
    }
    
    public void checkTriggers(final Calendar cal) throws ANTLRException, IOException, InterruptedException  {
        Hudson inst = Hudson.getInstance();

        MavenRepoCleanerProperty.DescriptorImpl d = (MavenRepoCleanerProperty.DescriptorImpl)inst.getDescriptor(MavenRepoCleanerProperty.class);

        if (d!=null) {
            if (d.getCronTab() != null) {
                if (d.getCronTab().check(cal)) {
                    for (Node n : inst.getNodes())
                        if (n instanceof Slave) process((Slave)n, d.getExpirationDays());
                    
                    process(inst, d.getExpirationDays());
                }
            }
        }
    }
    
    public static void invoke() {
        theInstance.run();
    }

    private void process(Hudson h, int expirationDays) throws IOException, InterruptedException {
        File jobs = new File(h.getRootDir(), "jobs");
        File[] dirs = jobs.listFiles(DIR_FILTER);
        if(dirs==null)      return;
        for (File dir : dirs) {
            FilePath repo = new FilePath(new File(new File(dir, "workspace"), ".repository"));
            if(shouldBeDeleted(dir.getName(),repo,h,expirationDays)) {
                delete(repo);
            }
        }
    }

    private boolean shouldBeDeleted(String jobName, FilePath dir, Node n, int expirationDays) throws IOException, InterruptedException {
        // TODO: the use of remoting is not optimal.
        // One remoting can execute "exists", "lastModified", and "delete" all at once.
        TopLevelItem item = Hudson.getInstance().getItem(jobName);
        if(item==null) {
            // no such project anymore
            LOGGER.fine("Repository directory "+dir+" is not owned by any project");
            return true;
        }

        if(!dir.exists())
            return false;
        
        long now = new Date().getTime();

        // If the marker file doesn't already exist, create it.
        FilePath markerFile = new FilePath(dir, ".cleanupMarker");
        if (!markerFile.exists()) {
            // Giving marker file a modification time of now - 5 minutes, so that a check tomorrow
            // will show it greater than 24 hours old.
            markerFile.touch(now-(5*MIN));
        }

        
        
        if (item instanceof AbstractProject) {
            AbstractProject p = (AbstractProject) item;
            
            MavenRepoCleanerProperty mrcp = (MavenRepoCleanerProperty)p.getProperty(MavenRepoCleanerProperty.class);

            if (mrcp!=null) {
                if (mrcp.isNotOnThisProject()) {
                    LOGGER.fine("Repository cleaning disabled for job " + jobName);
                    return false;
                }
            }
            
            if (p.isBuilding()) {
                LOGGER.fine("Repository directory " + dir + " belongs to a currently running build, so deletion is vetoed.");
                return false;
            }

            MavenRepoCleanerProperty.DescriptorImpl d = (MavenRepoCleanerProperty.DescriptorImpl)mrcp.getDescriptor();

            // If expirationStyle is 1, compare against directory's last modified time.
            if (d.getExpirationStyle()==1) {
                // if younger than the given range, keep it
                if(dir.lastModified() + expirationDays * DAY > now) {
                    LOGGER.fine("Repository directory "+dir+" is only "+ Util.getTimeSpanString(now-dir.lastModified())+" old, so not deleting");
                    return false;
                }
            }
            // If expirationStyle is 0, compare against marker file's last modified time.
            else if (d.getExpirationStyle()==0) {
                // if younger than the given range, keep it
                if(markerFile.lastModified() + expirationDays * DAY > now) {
                    LOGGER.fine("Repository directory marker file "+markerFile+" is only "+ Util.getTimeSpanString(now-markerFile.lastModified())+" old, so not deleting");
                    return false;
                }
            }
            // If expirationStyle is 2, just return true - we're deleting regardless.
            else if (d.getExpirationStyle()==2) {
                LOGGER.fine("Repository directory "+dir+" should be deleted regardless of age");
                return true;
            }
        }

        LOGGER.fine("Going to delete repository directory "+dir);
        return true;
    }

    private void process(Slave s, int expirationDays) throws InterruptedException {
        listener.getLogger().println("Scanning "+s.getNodeName());

        try {
            FilePath path = s.getWorkspaceRoot();
            if(path==null)  return;

            List<FilePath> dirs = path.list(DIR_FILTER);
            if(dirs ==null) return;
            for (FilePath dir : dirs) {
                FilePath repo = new FilePath(dir, ".repository");
                if(shouldBeDeleted(dir.getName(),repo,s,expirationDays))
                    delete(repo);
            }
        } catch (IOException e) {
            e.printStackTrace(listener.error("Failed on "+s.getNodeName()));
        }
    }

    private void delete(FilePath dir) throws InterruptedException {
        try {
            listener.getLogger().println("Deleting "+dir);
            dir.deleteRecursive();
        } catch (IOException e) {
            e.printStackTrace(listener.error("Failed to delete "+dir));
        }
    }


    private static class DirectoryFilter implements FileFilter, Serializable {
        public boolean accept(File f) {
            return f.isDirectory();
        }
        private static final long serialVersionUID = 1L;
    }

    private static final FileFilter DIR_FILTER = new DirectoryFilter();

    private static final Logger LOGGER = Logger.getLogger(MavenRepoCleanerThread.class.getName());

    /**
     * Can be used to disable workspace clean up.
     */
    public static boolean disabled = Boolean.getBoolean(MavenRepoCleanerThread.class.getName()+".disabled");
}

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

import static hudson.Util.fixEmpty;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.scheduler.CronTab;
import hudson.scheduler.CronTabList;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.DataBoundConstructor;
import net.sf.json.JSONObject;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Arrays;
import java.util.List;

/**
 * 
 *
 * @author Andrew Bayer
 */
public class MavenRepoCleanerProperty extends JobProperty<AbstractProject<?,?>> {
    private boolean notOnThisProject = false;
    
    @DataBoundConstructor
    public MavenRepoCleanerProperty(boolean notOnThisProject) {
        this.notOnThisProject = notOnThisProject;
    }

    public boolean isNotOnThisProject() {
        return notOnThisProject;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends JobPropertyDescriptor {
        private String cronSpec;
        private int expirationDays = 7;
        private int expirationStyle = 1;
        
        public DescriptorImpl() {
            super(MavenRepoCleanerProperty.class);
            load();
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return AbstractProject.class.isAssignableFrom(jobType);
        }

        public String getDisplayName() {
            return "Maven Repo Cleaner";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject o) throws FormException {
            cronSpec = fixEmpty(req.getParameter("mavenrepocleaner.cronSpec")).trim();
            // Default to empty string.
            if (cronSpec == null) {
                cronSpec = "";
            }
            String expDays = fixEmpty(req.getParameter("mavenrepocleaner.expirationDays"));
            if (expDays != null) {
                try {
                    expirationDays = DecimalFormat.getIntegerInstance().parse(expDays).intValue();
                } catch (ParseException e) {
                    expirationDays = 7;
                }
            } else {
                expirationDays = 7;
            }

            String expStyle = fixEmpty(req.getParameter("mavenrepocleaner.expirationStyle"));
            if (expStyle != null) {
                if (expStyle.equals("added")) {
                    expirationStyle = 0;
                }
                else if (expStyle.equals("changed")) {
                    expirationStyle = 1;
                }
                else if (expStyle.equals("regardless")) {
                    expirationStyle = 2;
                }
                else {
                    expirationStyle = 1;
                }
            }
            
            save();
            return true;
        }
        
        @Override
        public JobProperty<?> newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            if (formData.has("notOnThisProject")) {
                formData.put("notOnThisProject", true);
            }
            else {
                formData.put("notOnThisProject", false);
            }
            MavenRepoCleanerProperty mrcp = req.bindJSON(MavenRepoCleanerProperty.class,formData);
            return mrcp;
        }

        public String getCronSpec() {
            return cronSpec;
        }

        public int getExpirationDays() {
            return expirationDays;
        }

        public int getExpirationStyle() {
            return expirationStyle;
        }
        
        public CronTabList getCronTab() throws ANTLRException {
            // If the cron spec isn't null or empty, create and return the CronTabList. Otherwise return an empty one.
            if ((cronSpec!=null) && (!cronSpec.equals(""))) {
                return CronTabList.create(cronSpec);
            }
            else {
                return new CronTabList(Collections.<CronTab>emptyList());
            }
        }
            
        
    }

}

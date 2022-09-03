package org.jenkinsci.plugins.mavenrepocleaner;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.maven.AbstractMavenProject;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class MavenRepoCleanerPostBuildTask extends Recorder {

    @DataBoundConstructor
    public MavenRepoCleanerPostBuildTask() {

    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {

        final long started = build.getTimeInMillis();
        FilePath.FileCallable<Collection<String>> cleanup =
            new FileCallableImpl(started);
        Collection<String> removed = build.getWorkspace().child(".repository").act(cleanup);
        if (removed.size() > 0) {
            listener.getLogger().println( removed.size() + " unused artifacts removed from private maven repository" );
        }
        return true;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            super(MavenRepoCleanerPostBuildTask.class);
        }

        @Override
        public String getDisplayName() {
            return "Cleanup maven repository for unused artifacts";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return FreeStyleProject.class.isAssignableFrom(jobType)
                    || AbstractMavenProject.class.isAssignableFrom(jobType);
        }
    }
    private static class FileCallableImpl implements FilePath.FileCallable<Collection<String>> {
        private final long started;
        public FileCallableImpl(long started) {
            this.started = started;
        }
        public Collection<String> invoke(File repository, VirtualChannel channel) throws IOException, InterruptedException {
            return new RepositoryCleaner(started).clean(repository);
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
            // no much to control here
        }
    }
}

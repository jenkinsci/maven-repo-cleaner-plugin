package org.jenkinsci.plugins.mavenrepocleaner;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import hudson.os.PosixAPI;
import org.apache.commons.io.DirectoryWalker;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Snapshot;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.index.artifact.M2GavCalculator;
import org.jruby.ext.posix.FileStat;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Hello world!
 *
 */
public class RepositoryCleaner extends DirectoryWalker
{
    private M2GavCalculator gavCalculator = new M2GavCalculator();
    private long olderThan;
    private String root;

    public RepositoryCleaner(long timestamp) {
        this.olderThan = timestamp / 1000;
    }

    public Collection<String> clean(File repository) throws IOException {
        this.root = repository.getAbsolutePath();
        Collection<String> result = new ArrayList<String>();
        walk(repository, result);
        return result;
    }

    protected final void handleDirectoryStart(File directory, int depth, Collection results) throws IOException {

        for (File file : directory.listFiles()) {
            if (file.isDirectory()) continue;
            String fileName = file.getName();

            if (fileName.endsWith(".sha1") || fileName.endsWith(".md5")) continue;

            String location = file.getAbsolutePath().substring(root.length());
            Gav gav = gavCalculator.pathToGav(location);
            if (gav == null) continue; // Not an artifact

            olderThan(file, gav, results);
        }

        if ( directory.listFiles(new MetadataFileFilter()).length == 0 ) {
            for (File file : directory.listFiles()) {
                file.delete();
            }
            directory.delete();
        }

    }

    private void olderThan(File file, Gav artifact, Collection results) {
        FileStat fs = PosixAPI.get().lstat(file.getPath());
        long lastAccessTime = fs.atime();
        if (lastAccessTime < olderThan) {
            // This artifact hasn't been accessed during build
            clean(file, artifact, results);
        }
    }

    private void clean(File file, Gav artifact, Collection results) {
        File directory = file.getParentFile();
        String fineName = gavCalculator.calculateArtifactName(artifact);
        new File(directory, fineName + ".md5").delete();
        new File(directory, fineName + ".sha1").delete();
        file.delete();
        results.add(gavCalculator.gavToPath(artifact));
    }

    private static class MetadataFileFilter implements FileFilter {

        private final List<String> metadata =
            Arrays.asList(new String[]
                    {"_maven.repositories", "maven-metadata.xml", "maven-metadata.xml.md5", "maven-metadata.xml.sha1"});

        public boolean accept(File file) {
            return !metadata.contains(file.getName());
        }
    }
}


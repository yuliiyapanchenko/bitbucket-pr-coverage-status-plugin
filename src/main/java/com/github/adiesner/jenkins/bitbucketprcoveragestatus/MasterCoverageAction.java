/*

    Copyright 2015-2016 Artem Stasiuk

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

*/
package com.github.adiesner.jenkins.bitbucketprcoveragestatus;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import jenkins.tasks.SimpleBuildStep;
import lombok.Getter;
import lombok.Setter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;

/**
 * Record coverage of Jenkins Build and assume it as master coverage.
 * Master coverage will be used to compare Pull Request coverage and provide status message in Pull Request.
 * Optional step as coverage could be taken from Sonar. Take a look on {@link Configuration}
 *
 * @see CompareCoverageAction
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class MasterCoverageAction extends Recorder implements SimpleBuildStep {

    public static final String DISPLAY_NAME = "Record Master Coverage";
    private static final long serialVersionUID = 1L;

    @Getter @Setter @DataBoundSetter
    private Map<String, String> scmVars;

    @Getter @Setter @DataBoundSetter
    private boolean disableSimpleCov;

    @DataBoundConstructor
    public MasterCoverageAction() {
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public void perform(final Run build, final FilePath workspace, final Launcher launcher,
                        final TaskListener listener) throws InterruptedException, IOException {
        if (build.getResult() != Result.SUCCESS) return;

        final PrintStream buildLog = listener.getLogger();
        final String gitUrl = PrIdAndUrlUtils.getGitUrlWithBranch(build, listener);

        EnvVars envVars = new EnvVars();
        envVars = build.getEnvironment(listener);
        // get coverage from jacoco plugin
        final float masterCoverage = Float.parseFloat(envVars.get("lineCoverage")) / 100; // ServiceRegistry.getCoverageRepository(isDisableSimpleCov()).get(workspace);
        buildLog.println("Master coverage " + Percent.toWholeString(masterCoverage) + " for " + gitUrl);
        Configuration.setMasterCoverage(gitUrl, masterCoverage);
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override
        @Nonnull
        public String getDisplayName() {
            return DISPLAY_NAME;
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

    }

}

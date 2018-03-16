package io.jenkins.plugins.openmeta;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.kohsuke.stapler.Stapler;

import java.io.File;

public class OpenMetaBuilderTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();


    @Test
    public void testConfigRoundtrip() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        OpenMetaBuilder builder = new OpenMetaBuilder();
        builder.setModelName("test_model.xme");
        project.getBuildersList().add(builder);
        project = jenkins.configRoundtrip(project);
        //jenkins.assertEqualDataBoundBeans(new OpenMetaBuilder(name), project.getBuildersList().get(0));
    }

    @Test
    public void testBuild() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        OpenMetaBuilder builder = new OpenMetaBuilder();
        String testFile = System.getenv("USERPROFILE") + "\\Documents\\openmeta-examples-and-templates\\value-aggregator\\value-aggregator.xme";
        if (!new File(testFile).canRead()) {
            return;
        }
        builder.setModelName(testFile);
        project.getBuildersList().add(builder);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        // new OpenMetaBuilder.DescriptorImpl().doCheckModelName(build.getProject(), testFile);
    }

    @Test
    public void testScriptedPipeline() throws Exception {
        String testFile = System.getenv("USERPROFILE") + "\\Documents\\openmeta-examples-and-templates\\value-aggregator\\value-aggregator.xme";
        String agentLabel = "my-agent";
        jenkins.createOnlineSlave(Label.get(agentLabel));
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-scripted-pipeline");
        String pipelineScript
                = "node {\n"
                + "  openMetaTestBench modelName: '" + testFile.replace("\\", "\\\\") + "'\n"
                + "}";
        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));
        if (!new File(testFile).canRead()) {
            return;
        }
        WorkflowRun completedBuild = jenkins.assertBuildStatusSuccess(job.scheduleBuild2(0));
    }

}
package io.jenkins.plugins.openmeta;

import hudson.*;
import hudson.model.*;
import hudson.remoting.Callable;
import hudson.remoting.ChannelClosedException;
import hudson.remoting.Future;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.util.FormValidation;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.*;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;

public class OpenMetaBuilder extends Builder implements SimpleBuildStep {

    private String modelName;
    // max_configs
    private String excludePatterns = "";
    private String maxConfigs = "";

    private static final Logger LOGGER = Logger.getLogger(OpenMetaBuilder.class.getName());


    @DataBoundConstructor
    public OpenMetaBuilder() {
    }

    public String getModelName() {
        return modelName;
    }

    @DataBoundSetter
    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getExcludePatterns() {
        return excludePatterns;
    }

    @DataBoundSetter
    public void setExcludePatterns(String excludePatterns) {
        this.excludePatterns = excludePatterns;
    }

    @DataBoundSetter
    public void setMaxConfigs(String maxConfigs) {
        this.maxConfigs = maxConfigs;
    }

    public String getMaxConfigs() {
        return maxConfigs;
    }

    /**
     * Creates a script file in a temporary name in the specified directory.
     */
    public FilePath createScriptFile(@Nonnull FilePath dir) throws IOException, InterruptedException {
        return dir.createTextTempFile("jenkins", ".cmd", getContents(), false);
    }

    public String getContents() {
        String excludes = "";
        if (excludePatterns.equals("") == false) {
            for (String exclude : excludePatterns.split(",")) {
                excludes += " -e " + exclude;
            }
        }
        return "FOR /F \"skip=2 tokens=2,*\" %%A IN ('%SystemRoot%\\SysWoW64\\REG.exe query \"HKLM\\software\\META\" /v \"META_PATH\"') DO SET META_PATH=%%B\r\n" +
                "\"%META_PATH%bin\\Python27\\Scripts\\python.exe\" \"%META_PATH%bin\\RunTestBenches.py\" " +
                "\"" + modelName + "\"" + " -- -v --with-xunit --xunit-file=openmeta-testbenches-result.xml " +
                excludes + "\r\n";
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath filePath, @Nonnull Launcher launcher, @Nonnull TaskListener taskListener) throws InterruptedException, IOException {
        FilePath ws = filePath;
        TaskListener listener = taskListener;
        FilePath script = null;
        int r = -1;
        try {
            try {
                script = createScriptFile(ws);
            } catch (IOException e) {
                Util.displayIOException(e, listener);
                Functions.printStackTrace(e, listener.fatalError("Could not write script file"));
                //return false;
                run.setResult(Result.FAILURE);
                return;
            }

            try {
                // envs(envVars)
                EnvVars envVars = run.getEnvironment(taskListener);
                // on Windows environment variables are converted to all upper case,
                // but no such conversions are done on Unix, so to make this cross-platform,
                // convert variables to all upper cases.
                for(Map.Entry<String,String> e : run.getCharacteristicEnvVars().entrySet()) {
                    envVars.put(e.getKey(), e.getValue());
                }

                r = launcher.launch().cmds("cmd.exe", "/c", script.getRemote()).envs(envVars).stdout(listener).pwd(ws).start().join();

                if (r != 0) {
                    run.setResult(Result.UNSTABLE);
                }
                JUnitResultArchiver jUnitPublisher = new JUnitResultArchiver("openmeta-testbenches-result.xml");
                jUnitPublisher.perform(run, filePath, launcher, taskListener);
            } catch (IOException e) {
                Util.displayIOException(e, listener);
                Functions.printStackTrace(e, listener.fatalError("Command failed"));
            }
        } finally {
            try {
                if (script != null) {
                    script.delete();
                }
            } catch (IOException e) {
                if (r == -1 && e.getCause() instanceof ChannelClosedException) {
                    // JENKINS-5073
                    // r==-1 only when the execution of the command resulted in IOException,
                    // and we've already reported that error. A common error there is channel
                    // losing a connection, and in that case we don't want to confuse users
                    // by reporting the 2nd problem. Technically the 1st exception may not be
                    // a channel closed error, but that's rare enough, and JENKINS-5073 is common enough
                    // that this suppressing of the error would be justified
                    LOGGER.log(Level.FINE, "Script deletion failed", e);
                } else {
                    Util.displayIOException(e, listener);
                    Functions.printStackTrace(e, listener.fatalError("Could not delete " + script));
                }
            } catch (Exception e) {
                Functions.printStackTrace(e, listener.fatalError("Could not delete " + script));
            }
        }
    }

    @Symbol("openMetaTestBench")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public FormValidation doCheckMaxConfigs(@QueryParameter String maxConfigs)
                throws IOException, ServletException {
            if (!maxConfigs.equals("")) {
                try {
                    Integer.parseInt(maxConfigs);
                } catch (NumberFormatException e) {
                    return FormValidation.error("Max Configs must be a number");
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckModelName(
                @AncestorInPath AbstractProject project,
                @QueryParameter String value) throws IOException, InterruptedException, ExecutionException {
            if (project == null || value.equals("")) {
                return FormValidation.ok();
            }
            FilePath workspace = project.getSomeWorkspace();
            if (workspace == null) {
                return FormValidation.ok();
            }
            FormValidation exists = workspace.validateRelativePath(value, true, true);
            if (!exists.equals(FormValidation.ok())) {
                return exists;
            }

            if (value.endsWith(".mga")) {
                // unfortunately, .mga does not have a magic number. hope for the best
            } else {
                final String absPath = workspace.child(value).absolutize().getRemote();
                Future<String> check = project.getSomeWorkspace().getChannel().callAsync(new XmeChecker(absPath));


                String contents = check.get();
                if (contents.indexOf("<!DOCTYPE project SYSTEM \"mga") == -1) {
                    return FormValidation.warning("Not a GME XME file");
                }
            }
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.OpenMetaBuilder_DescriptorImpl_DisplayName();
        }

    }
}

class XmeChecker implements Callable<String, IOException> {
    private static final long serialVersionUID = 1L;
    private final String absPath;

    public XmeChecker(String absPath)
    {
        this.absPath = absPath;
    }

    @Override
    public void checkRoles(RoleChecker roleChecker) throws SecurityException {
    }

    @Override
    public String call() throws IOException {
        InputStreamReader is = new InputStreamReader(new FileInputStream(absPath), "UTF8");
        BufferedReader reader = new BufferedReader(is);
        try {
            String ret;
            ret = reader.readLine();
            ret += reader.readLine();
            ret += reader.readLine();
            ret += reader.readLine();
            return ret;
        }
        finally {
            reader.close();
        }
    }
}

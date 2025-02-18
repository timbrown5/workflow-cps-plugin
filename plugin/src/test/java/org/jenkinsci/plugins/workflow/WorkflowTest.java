/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow;

import com.google.common.base.Function;
import hudson.EnvVars;
import hudson.Functions;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import jenkins.model.Jenkins;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.actions.EnvironmentAction;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockQueueItemAuthenticator;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Tests of workflows that involve restarting Jenkins in the middle.
 */
public class WorkflowTest extends SingleJobTestBase {

    /**
     * Restart Jenkins while workflow is executing to make sure it suspends all right
     */
    @Test public void demo() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                p = jenkins().createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("semaphore 'wait'", false));
                startBuilding();
                SemaphoreStep.waitForStart("wait/1", b);
                assertTrue(b.isBuilding());
                liveness();
            }
        });
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                rebuildContext(story.j);
                for (int i = 0; i < 600 && !Queue.getInstance().isEmpty(); i++) {
                    Thread.sleep(100);
                }
                liveness();
                SemaphoreStep.success("wait/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
            }
        });
    }
    private void liveness() {
        assertFalse(jenkins().toComputer().isIdle());
        Executor e = b.getOneOffExecutor();
        assertNotNull(e);
        assertEquals(e, b.getExecutor());
        assertTrue(e.isActive());
        /* TODO seems flaky:
        assertFalse(e.isAlive());
        */
    }

    /**
     * ability to invoke body needs to survive beyond Jenkins restart.
     */
    @Test public void invokeBodyLaterAfterRestart() throws Exception {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                p = jenkins().createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition(
                    "int count=0;\n" +
                    "retry(3) {\n" +
                    "    semaphore 'wait'\n" +
                    "    if (count++ < 2) {\n" + // forcing retry
                    "        error 'died'\n" +
                    "    }\n" +
                    "}", false));

                startBuilding();
                SemaphoreStep.waitForStart("wait/1", b);
                assertTrue(b.isBuilding());
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                rebuildContext(story.j);

                // resume execution and cause the retry to invoke the body again
                SemaphoreStep.success("wait/1", null);
                SemaphoreStep.success("wait/2", null);
                SemaphoreStep.success("wait/3", null);

                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
                assertTrue(e.programPromise.get().closures.isEmpty());
            }
        });
    }

    @Test public void authentication() throws Exception {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                jenkins().setSecurityRealm(story.j.createDummySecurityRealm());
                jenkins().save();
                QueueItemAuthenticatorConfiguration.get().getAuthenticators().add(new MockQueueItemAuthenticator(Collections.singletonMap("demo", User.getById("someone", true).impersonate())));
                p = jenkins().createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("checkAuth()", false));
                ScriptApproval.get().preapproveAll();
                startBuilding();
                waitForWorkflowToSuspend();
                assertTrue(b.isBuilding());
                story.j.waitForMessage("running as someone", b);
                CheckAuth.finish(false);
                waitForWorkflowToSuspend();
                assertTrue(b.isBuilding());
                story.j.waitForMessage("still running as someone", b);
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                assertEquals(JenkinsRule.DummySecurityRealm.class, jenkins().getSecurityRealm().getClass());
                rebuildContext(story.j);
                story.j.waitForMessage("again running as someone", b);
                CheckAuth.finish(true);
                story.j.assertLogContains("finally running as someone", story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b)));
            }
        });
    }
    public static final class CheckAuth extends AbstractStepImpl {
        @DataBoundConstructor public CheckAuth() {}
        @TestExtension("authentication") public static final class DescriptorImpl extends AbstractStepDescriptorImpl {
            public DescriptorImpl() {
                super(Execution.class);
            }
            @Override public String getFunctionName() {
                return "checkAuth";
            }
            @Override
            public String getDisplayName() {
                return getFunctionName(); // TODO would be nice for this to be the default, perhaps?
            }
        }
        public static final class Execution extends AbstractStepExecutionImpl {
            @StepContextParameter transient TaskListener listener;
            @StepContextParameter transient FlowExecution flow;
            @Override public boolean start() throws Exception {
                listener.getLogger().println("running as " + Jenkins.getAuthentication().getName() + " from " + Thread.currentThread().getName());
                return false;
            }
            @Override public void onResume() {
                super.onResume();
                try {
                    listener.getLogger().println("again running as " + flow.getAuthentication().getName() + " from " + Thread.currentThread().getName());
                } catch (Exception x) {
                    getContext().onFailure(x);
                }
            }
        }
        public static void finish(final boolean terminate) {
            StepExecution.applyAll(Execution.class, new Function<Execution,Void>() {
                @Override public Void apply(Execution input) {
                    try {
                        input.listener.getLogger().println((terminate ? "finally" : "still") + " running as " + input.flow.getAuthentication().getName() + " from " + Thread.currentThread().getName());
                        if (terminate) {
                            input.getContext().onSuccess(null);
                        }
                    } catch (Exception x) {
                        input.getContext().onFailure(x);
                    }
                    return null;
                }
            });
        }
    }

    @Issue("JENKINS-30122")
    @Test public void authenticationInSynchronousStep() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                jenkins().setSecurityRealm(story.j.createDummySecurityRealm());
                jenkins().save();
                QueueItemAuthenticatorConfiguration.get().getAuthenticators().add(new MockQueueItemAuthenticator(Collections.singletonMap("demo", User.getById("someone", true).impersonate())));
                p = jenkins().createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("echo \"ran as ${auth()}\"", true));
                b = story.j.assertBuildStatusSuccess(p.scheduleBuild2(0));
                story.j.assertLogContains("ran as someone", b);
            }
        });
    }
    public static final class CheckAuthSync extends AbstractStepImpl {
        @DataBoundConstructor public CheckAuthSync() {}
        @TestExtension("authenticationInSynchronousStep") public static final class DescriptorImpl extends AbstractStepDescriptorImpl {
            public DescriptorImpl() {
                super(Execution.class);
            }
            @Override public String getFunctionName() {
                return "auth";
            }
            @Override public String getDisplayName() {
                return getFunctionName();
            }
        }
        public static final class Execution extends AbstractSynchronousNonBlockingStepExecution<String> {
            @Override protected String run() throws Exception {
                return Jenkins.getAuthentication().getName();
            }
        }
    }

    @Issue("JENKINS-52189")
    @Test
    public void notifyFlowStartNode() {
        story.then(s->{
            WorkflowJob j = jenkins().createProject(WorkflowJob.class, "bob");
            j.setDefinition(new CpsFlowDefinition("echo 'I did a thing'", true));
            WorkflowRun r = story.j.buildAndAssertSuccess(j);
            FlowStartNodeListener listener = jenkins().getExtensionList(FlowStartNodeListener.class).get(0);
            assertTrue(listener.execNames.contains(r.getExecution().toString()));
        });
    }

    @TestExtension("notifyFlowStartNode")
    public static class FlowStartNodeListener implements GraphListener {
        List<String> execNames = new ArrayList<>();

        @Override
        public void onNewHead(FlowNode node) {
            if (node instanceof FlowStartNode) {
                execNames.add(node.getExecution().toString());
            }
        }
    }

    @Issue("JENKINS-29952")
    @Test public void env() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                Map<String,String> slaveEnv = new HashMap<>();
                slaveEnv.put("BUILD_TAG", null);
                slaveEnv.put("PERMACHINE", "set");
                createSpecialEnvSlave(story.j, "agent", null, slaveEnv);
                p = jenkins().createProject(WorkflowJob.class, "demo");
                p.setDefinition(new CpsFlowDefinition("node('agent') {\n"
                        + "  if (isUnix()) {sh 'echo tag=$BUILD_TAG PERMACHINE=$PERMACHINE'} else {bat 'echo tag=%BUILD_TAG% PERMACHINE=%PERMACHINE%'}\n"
                        + "  env.BUILD_TAG='custom'\n"
                        + "  if (isUnix()) {sh 'echo tag2=$BUILD_TAG'} else {bat 'echo tag2=%BUILD_TAG%'}\n"
                        + "  env.STUFF='more'\n"
                        + "  semaphore 'env'\n"
                        + "  env.BUILD_TAG=\"${env.BUILD_TAG}2\"\n"
                        + "  if (isUnix()) {sh 'echo tag3=$BUILD_TAG stuff=$STUFF'} else {bat 'echo tag3=%BUILD_TAG% stuff=%STUFF%'}\n"
                        + "  if (isUnix()) {env.PATH=\"/opt/stuff/bin:${env.PATH}\"} else {env.PATH=$/c:\\whatever;${env.PATH}/$}\n"
                        + "  if (isUnix()) {sh 'echo shell PATH=$PATH'} else {bat 'echo shell PATH=%PATH%'}\n"
                        + "  echo \"groovy PATH=${env.PATH}\"\n"
                        + "  echo \"simplified groovy PATH=${PATH}\"\n"
                        + "}", true));
                startBuilding();
                SemaphoreStep.waitForStart("env/1", b);
                assertTrue(b.isBuilding());
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                rebuildContext(story.j);
                SemaphoreStep.success("env/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
                story.j.assertLogContains("tag=jenkins-demo-1 PERMACHINE=set", b);
                story.j.assertLogContains("tag2=custom", b);
                story.j.assertLogContains("tag3=custom2 stuff=more", b);
                String prefix = Functions.isWindows() ? "c:\\whatever;" : "/opt/stuff/bin:";
                story.j.assertLogContains("shell PATH=" + prefix, b);
                story.j.assertLogContains("groovy PATH=" + prefix, b);
                story.j.assertLogContains("simplified groovy PATH=" + prefix, b);
                EnvironmentAction a = b.getAction(EnvironmentAction.class);
                assertNotNull(a);
                assertEquals("custom2", a.getEnvironment().get("BUILD_TAG"));
                assertEquals("more", a.getEnvironment().get("STUFF"));
                assertNotNull(a.getEnvironment().get("PATH"));
                // Show that EnvActionImpl binding is a fallback only for things which would otherwise have been undefined:
                p.setDefinition(new CpsFlowDefinition(
                    "env.env = 'env.env'\n" +
                    "env.echo = 'env.echo'\n" +
                    "env.circle = 'env.circle'\n" +
                    "env.var = 'env.var'\n" +
                    "env.global = 'env.global'\n" +
                    "global = 'global'\n" +
                    "circle {\n" +
                    "  def var = 'value'\n" +
                    "  echo \"${var} vs. ${echo} vs. ${circle} vs. ${global}\"\n" +
                    "}", true));
                story.j.assertLogContains("value vs. env.echo vs. env.circle vs. global", story.j.buildAndAssertSuccess(p));
            }
        });
    }

    // TODO add to jenkins-test-harness
    /**
     * Akin to {@link JenkinsRule#createSlave(String, String, EnvVars)} but allows {@link Computer#getEnvironment} to be controlled rather than directly modifying launchers.
     * @param env variables to override in {@link Computer#getEnvironment}; null values will get unset even if defined in the test environment
     * @see <a href="https://github.com/jenkinsci/jenkins/pull/1553/files#r23784822">explanation in core PR 1553</a>
     */
    public static Slave createSpecialEnvSlave(JenkinsRule rule, String nodeName, @CheckForNull String labels, Map<String,String> env) throws Exception {
        @SuppressWarnings("deprecation") // keep consistency with original signature rather than force the caller to pass in a TemporaryFolder rule
        File remoteFS = rule.createTmpDir();
        SpecialEnvSlave slave = new SpecialEnvSlave(remoteFS, rule.createComputerLauncher(/* yes null */null), nodeName, labels != null ? labels : "", env);
        rule.jenkins.addNode(slave);
        return slave;
    }
    private static class SpecialEnvSlave extends Slave {
        private final Map<String,String> env;
        SpecialEnvSlave(File remoteFS, ComputerLauncher launcher, String nodeName, @NonNull String labels, Map<String,String> env) throws Descriptor.FormException, IOException {
            super(nodeName, remoteFS.getAbsolutePath(), launcher);
            setNumExecutors(1);
            setLabelString(labels);
            setMode(Mode.NORMAL);
            setRetentionStrategy(RetentionStrategy.NOOP);
            this.env = env;
        }
        @Override public Computer createComputer() {
            return new SpecialEnvComputer(this, env);
        }
    }
    private static class SpecialEnvComputer extends SlaveComputer {
        private final Map<String,String> env;
        SpecialEnvComputer(SpecialEnvSlave slave, Map<String,String> env) {
            super(slave);
            this.env = env;
        }
        @Override public EnvVars getEnvironment() throws IOException, InterruptedException {
            EnvVars env2 = super.getEnvironment();
            env2.overrideAll(env);
            return env2;
        }
    }

}

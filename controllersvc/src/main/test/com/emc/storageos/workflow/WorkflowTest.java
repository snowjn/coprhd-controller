package com.emc.storageos.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.Controller;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.Task;
import com.emc.storageos.db.client.model.WorkflowStep;
import com.emc.storageos.db.client.model.util.TaskUtils;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.joiner.Joiner;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.util.ControllersvcTestBase;
import com.emc.storageos.workflow.Workflow.StepState;

/**
 * This Junit uses the ControllersvcTestBase to start controllersvc and run it within the Junit.
 * Please see that class for required environmental setup / restrictions.
 * The controllersvc is started from within setup().
 *
 */
public class WorkflowTest extends ControllersvcTestBase implements Controller {
    private static final int SLEEP_MILLIS = 3000;
    private static final URI nullURI = NullColumnValueGetter.getNullURI();
    protected static final Logger log = LoggerFactory.getLogger(WorkflowTest.class);
    private static int sleepMillis = 0; // sleep time for each step in milliseconds

    // Set of injected failure steps. It's level * 100 + step
    private static final Set<Integer> injectedFailures = new HashSet<Integer>();

    private boolean hasInjectedFailure(int level, int step) {
        return injectedFailures.contains(level * 100 + step);
    }

    private void addInjectedFailure(int level, int step) {
        injectedFailures.add(level * 100 + step);
    }

    private void removeInjectedFailure(int level, int step) {
        injectedFailures.remove(level * 100 + step);
    }

    @Before
    public void setup() {
        startControllersvc();
        // Add our controller to the set of supported controllers.
        Set<Controller> controllerSet = new HashSet<Controller>();
        controllerSet.addAll(dispatcher.getControllerMap().values());
        controllerSet.add(this);
        dispatcher.setController(controllerSet);
    }

    private static Map<String, WorkflowState> taskStatusMap = new HashMap<String, WorkflowState>();

    @Test
    /**
     * This test a simple one step passing workflow.
     */
    public void test01_one_wf_one_step_simple() {
        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        Object[] args = new Object[1];
        String taskId = UUID.randomUUID().toString();
        args[0] = taskId;
        workflowService.setSuspendClassMethodTestOnly(null);
        workflowService.setSuspendOnErrorTestOnly(true);
        injectedFailures.clear();
        taskStatusMap.put(taskId, WorkflowState.CREATED);
        Workflow workflow = workflowService.getNewWorkflow(this, testname, false, taskId);
        workflow.createStep(testname, "nop", null, nullURI, this.getClass().getName(), false, this.getClass(),
                nopMethod(1, 1), nopMethod(1, 1), null);
        workflow.executePlan(null, "success", new WorkflowCallback(), args, null, null);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog(String.format("task %s state %s", taskId, state));
        assertTrue(state == WorkflowState.SUCCESS);
        printLog(testname + " completed");
    }

    @Test
    /**
     * This tests a single level, three step workflow that passes.
     */
    public void test02_one_wf_three_steps_simple() {
        // Expected results for this test case
        final String[] testSuccessSteps = { "L0S1 sub", "L0S2 sub", "L0S3 sub" };
        final String[] testErrorSteps = {};
        final String[] testCancelledSteps = {};
        final String[] testSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        // Don't cause in failures
        workflowService.setSuspendClassMethodTestOnly(null);
        workflowService.setSuspendOnErrorTestOnly(true);
        injectedFailures.clear();
        String taskId = UUID.randomUUID().toString();
        generate3StepWF(0, 1, taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Top level workflow state: " + state);
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        assertTrue(state == WorkflowState.SUCCESS);
        validateStepStates(stepMap, testSuccessSteps, testErrorSteps, testCancelledSteps, testSuspendedSteps);
        printLog(testname + " completed");
    }

    @Test
    /**
     * This tests a three level hierarchical workflow that passes.
     */
    public void test03_three_level_wf_three_steps_each_simple() {
        // Expected results for this test case
        final String[] testSuccessSteps = { "L0S1 sub", "L0S2 sub", "L0S3 sub",
                "L1S1 sub", "L1S2 sub", "L1S3 sub", "L2S1 sub", "L2S2 sub", "L2S3 sub" };
        final String[] testErrorSteps = {};
        final String[] testCancelledSteps = {};
        final String[] testSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        // Don't cause in failures
        workflowService.setSuspendClassMethodTestOnly(null);
        workflowService.setSuspendOnErrorTestOnly(true);
        injectedFailures.clear();
        String taskId = UUID.randomUUID().toString();
        generate3StepWF(0, 3, taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Top level workflow state: " + state);
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        assertTrue(state == WorkflowState.SUCCESS);
        validateStepStates(stepMap, testSuccessSteps, testErrorSteps, testCancelledSteps, testSuspendedSteps);
        printLog(testname + " completed");
    }

    @Test
    /**
     * This tests a three level hierarchical workflow where the lowest level last step fails.
     * After the workflow suspends, remove the error and resume the workflow.
     * The resulting workflow should pass all steps.
     */
    public void test04_three_level_wf_error_level2_step3_with_retry() {
        // Expected results for this test case
        final String[] testaSuccessSteps = { "L0S1 sub", "L1S1 sub", "L2S1 sub", "L2S2 sub" };
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = { "L0S3 sub", "L1S3 sub" };
        final String[] testaSuspendedSteps = { "L0S2 sub", "L1S2 sub", "L2S3 sub" };

        final String[] testbSuccessSteps = { "L0S1 sub", "L0S2 sub", "L0S3 sub",
                "L1S1 sub", "L1S2 sub", "L1S3 sub", "L2S1 sub", "L2S2 sub", "L2S3 sub" };
        final String[] testbErrorSteps = {};
        final String[] testbCancelledSteps = {};
        final String[] testbSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        workflowService.setSuspendClassMethodTestOnly(null);
        workflowService.setSuspendOnErrorTestOnly(true);
        injectedFailures.clear();
        addInjectedFailure(2, 3); // level 2, step 3
        String taskId = UUID.randomUUID().toString();
        Workflow workflow = generate3StepWF(0, 3, taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Top level workflow state: " + state);
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        assertTrue(state == WorkflowState.SUSPENDED_ERROR);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);
        if (state == WorkflowState.SUSPENDED_ERROR) {
            // clear the error and try and resume.
            injectedFailures.clear();
            String resumeTaskId = UUID.randomUUID().toString();
            workflowService.resumeWorkflow(workflow.getWorkflowURI(), resumeTaskId);
            taskStatusMap.put(taskId, WorkflowState.CREATED);
            state = waitOnWorkflowComplete(taskId);
            printLog("Top level workflow state after resume: " + state);
            stepMap = readWorkflowFromDb(taskId);
            assertTrue(state == WorkflowState.SUCCESS);
            validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        }
        printLog(testname + " completed");
    }

    @Test
    /**
     * This tests a two level hierarchical workflow where the lowest level last step fails.
     * After the workflow suspends, remove the error and resume the workflow.
     * The resulting workflow should pass all steps.
     */
    public void test15_two_level_wf_error_level2_step3_with_retry() {
        // Expected results for this test case
        final String[] testaSuccessSteps = { "L0S1 sub", "L1S1 sub", "L1S2 sub" };
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = { "L0S3 sub", };
        final String[] testaSuspendedSteps = { "L0S2 sub", "L1S3 sub" };

        final String[] testbSuccessSteps = { "L0S1 sub", "L0S2 sub", "L0S3 sub",
                "L1S1 sub", "L1S2 sub", "L1S3 sub" };
        final String[] testbErrorSteps = {};
        final String[] testbCancelledSteps = {};
        final String[] testbSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        workflowService.setSuspendClassMethodTestOnly(null);
        workflowService.setSuspendOnErrorTestOnly(true);
        injectedFailures.clear();
        addInjectedFailure(1, 3); // level 2, step 3
        String taskId = UUID.randomUUID().toString();
        Workflow workflow = generate3StepWF(0, 2, taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Top level workflow state: " + state);
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        assertTrue(state == WorkflowState.SUSPENDED_ERROR);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);
        if (state == WorkflowState.SUSPENDED_ERROR) {
            // clear the error and try and resume.
            injectedFailures.clear();
            String resumeTaskId = UUID.randomUUID().toString();
            workflowService.resumeWorkflow(workflow.getWorkflowURI(), resumeTaskId);
            taskStatusMap.put(taskId, WorkflowState.CREATED);
            state = waitOnWorkflowComplete(taskId);
            printLog("Top level workflow state after resume: " + state);
            stepMap = readWorkflowFromDb(taskId);
            assertTrue(state == WorkflowState.SUCCESS);
            validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        }
        printLog(testname + " completed");
    }

    @Test
    /**
     * This tests a three level hierarchical workflow where the lowest level last step fails.
     * After the workflow suspends, rollback the workflow. Then it verifies all the steps were
     * correctly cancelled or rolled back.
     */
    public void test05_three_level_wf_error_level2_step3_with_rollback() {
        // Expected results for this test case
        final String[] testaSuccessSteps = { "L0S1 sub", "L1S1 sub", "L2S1 sub", "L2S2 sub" };
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = { "L0S3 sub", "L1S3 sub" };
        final String[] testaSuspendedSteps = { "L0S2 sub", "L1S2 sub", "L2S3 sub" };

        final String[] testbSuccessSteps = { "L0S1 sub", "L1S1 sub", "L2S1 sub", "L2S2 sub",
                "Rollback L0S1 sub", "Rollback L0S2 sub", "Rollback L1S1 sub", "Rollback L1S2 sub", "Rollback L2S3 sub",
                "Rollback L2S1 sub",
                "Rollback L2S2 sub" };
        final String[] testbErrorSteps = { "L0S2 sub", "L1S2 sub", "L2S3 sub" };
        final String[] testbCancelledSteps = { "L0S3 sub", "L1S3 sub" };
        final String[] testbSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        workflowService.setSuspendClassMethodTestOnly(null);
        workflowService.setSuspendOnErrorTestOnly(true);
        injectedFailures.clear();
        addInjectedFailure(2, 3); // level 2, step 3
        String taskId = UUID.randomUUID().toString();
        Workflow workflow = generate3StepWF(0, 3, taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Top level workflow state: " + state);
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        assertTrue(state == WorkflowState.SUSPENDED_ERROR);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);
        if (state == WorkflowState.SUSPENDED_ERROR) {
            String rollbackTaskId = UUID.randomUUID().toString();
            taskStatusMap.remove(taskId);
            injectedFailures.clear();
            workflowService.rollbackWorkflow(workflow.getWorkflowURI(), rollbackTaskId);
            state = waitOnWorkflowComplete(taskId);
            printLog("Top level workflow state after rollback: " + state);
            stepMap = readWorkflowFromDb(taskId);
            validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        }
        printLog(testname + " completed");
    }

    @Test
    /**
     * This test does a suspend of a selected step, followed by a resume after the workflow is suspended.
     * The result should be a successfully completed workflow.
     */
    public void test06_one_wf_method_suspend_third_step_resume() {
        // Expected results for this test case
        final String[] testaSuccessSteps = { "L0S1 sub", "L0S2 sub" };
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = {};
        final String[] testaSuspendedSteps = { "L0S3 sub" };

        final String[] testbSuccessSteps = { "L0S1 sub", "L0S2 sub", "L0S3 sub" };
        final String[] testbErrorSteps = {};
        final String[] testbCancelledSteps = {};
        final String[] testbSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        workflowService.setSuspendClassMethodTestOnly(null);
        workflowService.setSuspendOnErrorTestOnly(true);
        injectedFailures.clear();
        sleepMillis = SLEEP_MILLIS;
        String taskId = UUID.randomUUID().toString();
        Workflow workflow = generate3StepWF(0, 1, taskId);
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        WorkflowStep step3 = stepMap.get("L0S3 sub");
        workflowService.suspendWorkflowStep(workflow.getWorkflowURI(), step3.getId(), UUID.randomUUID().toString());
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after suspend: " + state);
        assertTrue(state == WorkflowState.SUSPENDED_NO_ERROR);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);

        taskStatusMap.put(taskId, WorkflowState.CREATED);
        workflowService.resumeWorkflow(workflow.getWorkflowURI(), UUID.randomUUID().toString());
        state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after resume: " + state);
        assertTrue(state == WorkflowState.SUCCESS);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        sleepMillis = 0;
        printLog(testname + " completed");
    }

    @Test
    /**
     * This test sets a class and method to suspend, makes sure it suspends, and continues it.
     * The result should be a fully successful workflow.
     */
    public void test07_one_wf_three_steps_method_suspend_second_step_resume_task_verification() {
        // Expected results for this test case
        final String[] testaSuccessSteps = { "L0S1 sub" };
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = { "L0S3 sub" };
        final String[] testaSuspendedSteps = { "L0S2 sub" };

        final String[] testbSuccessSteps = { "L0S1 sub", "L0S2 sub", "L0S3 sub" };
        final String[] testbErrorSteps = {};
        final String[] testbCancelledSteps = {};
        final String[] testbSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        injectedFailures.clear();
        sleepMillis = SLEEP_MILLIS;

        // We're not allowed to (and probably shouldn't) change system properties in the unit tester.
        // So we can override the class/method directly in the Workflow Service.
        workflowService.setSuspendClassMethodTestOnly(this.getClass().getSimpleName() + ".sub");
        workflowService.setSuspendOnErrorTestOnly(true);

        String taskId = UUID.randomUUID().toString();

        // Generate a three step workflow.
        Workflow workflow = generate3StepWF(0, 1, taskId);
        Operation op = dbClient.createTaskOpStatus(com.emc.storageos.db.client.model.Workflow.class, workflow.getWorkflowURI(),
                taskId, ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME);
        com.emc.storageos.db.client.model.Workflow dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        dbWF.getOpStatus().put(taskId, op);
        Task wfTask = toTask(dbWF, taskId, op);

        // Gather the steps from the DB and wait for the workflow to hit a known "stopped" state
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after suspend: " + state);
        assertTrue(state == WorkflowState.SUSPENDED_NO_ERROR);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("suspended_no_error"));
        // Verify the completion state was filled in
        dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        assertNotNull(dbWF.getCompletionState());
        assertEquals(String.format("Workflow completion state found: " + dbWF.getCompletionState()), dbWF.getCompletionState(),
                "SUSPENDED_NO_ERROR");

        taskStatusMap.put(taskId, WorkflowState.CREATED);
        workflowService.resumeWorkflow(workflow.getWorkflowURI(), UUID.randomUUID().toString());

        // This will make sure the task goes into the pending state. Note: it's time-dependent so if you're debugging or
        // changing the sleep times, this may not work properly.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("pending"));

        state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after resume: " + state);
        assertTrue(state == WorkflowState.SUCCESS);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("ready"));
        // Verify the completion state was filled in
        dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        assertNotNull(dbWF.getCompletionState());
        assertEquals(String.format("Workflow completion state found: " + dbWF.getCompletionState()), dbWF.getCompletionState(),
                "SUCCESS");
        sleepMillis = 0;
        printLog(testname + " completed");
    }

    @Test
    /**
     * This test sets a class and method to suspend, makes sure it suspends, and continues it.
     * The result should be a fully successful workflow.
     */
    public void test08_one_wf_three_steps_method_suspend_first_step_resume_task_verification() {
        // Expected results for this test case
        final String[] testaSuccessSteps = {};
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = { "L0S3 sub", "L0S2 sub" };
        final String[] testaSuspendedSteps = { "L0S1 sub" };

        final String[] testbSuccessSteps = { "L0S1 sub", "L0S2 sub", "L0S3 sub" };
        final String[] testbErrorSteps = {};
        final String[] testbCancelledSteps = {};
        final String[] testbSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        injectedFailures.clear();
        sleepMillis = SLEEP_MILLIS;

        // We're not allowed to (and probably shouldn't) change system properties in the unit tester.
        // So we can override the class/method directly in the Workflow Service.
        workflowService.setSuspendClassMethodTestOnly(this.getClass().getSimpleName() + ".deepfirstnop");
        workflowService.setSuspendOnErrorTestOnly(true);

        String taskId = UUID.randomUUID().toString();
        String[] args = new String[1];
        args[0] = taskId;
        taskStatusMap.put(taskId, WorkflowState.CREATED);
        Workflow workflow = workflowService.getNewWorkflow(this, "generate3StepWF", false, taskId);
        WorkflowTaskCompleter completer = new WorkflowTaskCompleter(workflow.getWorkflowURI(), taskId);
        // first step
        String lastStep = workflow.createStep("first deep", genMsg(0, 1, "sub"), null, nullURI,
                this.getClass().getName(), false, this.getClass(), deepfirstnopMethod(0, 1), deepfirstnopMethod(0, 1), null);
        // second step
        lastStep = workflow.createStep("second", genMsg(0, 2, "sub"), lastStep, nullURI,
                this.getClass().getName(), false, this.getClass(), subMethod(0, 1, 2), nopMethod(0, 2), null);
        // third step
        lastStep = workflow.createStep("third deep", genMsg(0, 3, "sub"), lastStep, nullURI,
                this.getClass().getName(), false, this.getClass(), deeplastnopMethod(0, 3), deeplastnopMethod(0, 3), null);

        Operation op = dbClient.createTaskOpStatus(com.emc.storageos.db.client.model.Workflow.class, workflow.getWorkflowURI(),
                taskId, ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME);
        // Execute and go
        workflow.executePlan(completer, String.format("Workflow level %d successful", 0), new WorkflowCallback(), args, null, null);

        // Generate a three step workflow.
        com.emc.storageos.db.client.model.Workflow dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        dbWF.getOpStatus().put(taskId, op);
        Task wfTask = toTask(dbWF, taskId, op);

        // Gather the steps from the DB and wait for the workflow to hit a known "stopped" state
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after suspend: " + state);
        assertTrue(state == WorkflowState.SUSPENDED_NO_ERROR);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("suspended_no_error"));
        // Verify the completion state was filled in
        dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        assertNotNull(dbWF.getCompletionState());
        assertEquals(String.format("Workflow completion state found: " + dbWF.getCompletionState()), dbWF.getCompletionState(),
                "SUSPENDED_NO_ERROR");

        taskStatusMap.put(taskId, WorkflowState.CREATED);
        workflowService.resumeWorkflow(workflow.getWorkflowURI(), UUID.randomUUID().toString());
        state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after resume: " + state);
        assertTrue(state == WorkflowState.SUCCESS);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("ready"));
        // Verify the completion state was filled in
        dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        assertNotNull(dbWF.getCompletionState());
        assertEquals(String.format("Workflow completion state found: " + dbWF.getCompletionState()), dbWF.getCompletionState(),
                "SUCCESS");
        sleepMillis = 0;
        printLog(testname + " completed");
    }

    @Test
    /**
     * This test sets a class and method to suspend, makes sure it suspends, and continues it.
     * The result should be a fully successful workflow.
     */
    public void test09_one_wf_one_step_method_suspend_first_step_resume_task_verification() {
        // Expected results for this test case
        final String[] testaSuccessSteps = {};
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = {};
        final String[] testaSuspendedSteps = { "L0S1 sub" };

        final String[] testbSuccessSteps = { "L0S1 sub" };
        final String[] testbErrorSteps = {};
        final String[] testbCancelledSteps = {};
        final String[] testbSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        injectedFailures.clear();
        sleepMillis = SLEEP_MILLIS;

        // We're not allowed to (and probably shouldn't) change system properties in the unit tester.
        // So we can override the class/method directly in the Workflow Service.
        workflowService.setSuspendClassMethodTestOnly(this.getClass().getSimpleName() + ".deepfirstnop");
        workflowService.setSuspendOnErrorTestOnly(true);

        String taskId = UUID.randomUUID().toString();
        String[] args = new String[1];
        args[0] = taskId;
        taskStatusMap.put(taskId, WorkflowState.CREATED);
        Workflow workflow = workflowService.getNewWorkflow(this, "generate3StepWF", false, taskId);
        WorkflowTaskCompleter completer = new WorkflowTaskCompleter(workflow.getWorkflowURI(), taskId);
        // first step
        workflow.createStep("first deep", genMsg(0, 1, "sub"), null, nullURI,
                this.getClass().getName(), false, this.getClass(), deepfirstnopMethod(0, 1), deepfirstnopMethod(0, 1), null);

        Operation op = dbClient.createTaskOpStatus(com.emc.storageos.db.client.model.Workflow.class, workflow.getWorkflowURI(),
                taskId, ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME);
        // Execute and go
        workflow.executePlan(completer, String.format("Workflow level %d successful", 0), new WorkflowCallback(), args, null, null);

        // Generate a three step workflow.
        com.emc.storageos.db.client.model.Workflow dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        dbWF.getOpStatus().put(taskId, op);
        Task wfTask = toTask(dbWF, taskId, op);

        // Gather the steps from the DB and wait for the workflow to hit a known "stopped" state
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after suspend: " + state);
        assertTrue(state == WorkflowState.SUSPENDED_NO_ERROR);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("suspended_no_error"));
        // Verify the completion state was filled in
        dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        assertNotNull(dbWF.getCompletionState());
        assertEquals(String.format("Workflow completion state found: " + dbWF.getCompletionState()), dbWF.getCompletionState(),
                "SUSPENDED_NO_ERROR");

        taskStatusMap.put(taskId, WorkflowState.CREATED);
        workflowService.resumeWorkflow(workflow.getWorkflowURI(), UUID.randomUUID().toString());
        state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after resume: " + state);
        assertTrue(state == WorkflowState.SUCCESS);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("ready"));
        // Verify the completion state was filled in
        dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        assertNotNull(dbWF.getCompletionState());
        assertEquals(String.format("Workflow completion state found: " + dbWF.getCompletionState()), dbWF.getCompletionState(),
                "SUCCESS");
        sleepMillis = 0;
        printLog(testname + " completed");
    }

    @Test
    /**
     * This test creates a two layer workflow where a step in the inner WF gets suspended.  Verify the top-level task.
     * The result should be a fully successful workflow.
     */
    public void test10_two_wf_three_steps_method_suspend_last_step_resume_task_verification() {
        // Expected results for this test case
        final String[] testaSuccessSteps = { "L0S1 sub", "L1S1 sub", "L1S2 sub" };
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = { "L0S3 sub" };
        final String[] testaSuspendedSteps = { "L0S2 sub", "L1S3 sub", };

        final String[] testbSuccessSteps = { "L0S1 sub", "L0S2 sub", "L0S3 sub", "L1S1 sub", "L1S2 sub", "L1S3 sub" };
        final String[] testbErrorSteps = {};
        final String[] testbCancelledSteps = {};
        final String[] testbSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        injectedFailures.clear();
        sleepMillis = SLEEP_MILLIS;

        // We're not allowed to (and probably shouldn't) change system properties in the unit tester.
        // So we can override the class/method directly in the Workflow Service.
        workflowService.setSuspendClassMethodTestOnly(this.getClass().getSimpleName() + ".deeplastnop");
        workflowService.setSuspendOnErrorTestOnly(true);

        String taskId = UUID.randomUUID().toString();

        // Generate a three step workflow.
        Workflow workflow = generate3StepWF(0, 2, taskId);
        // NOTE: Creating the Task object AFTER executing the workflow may lead to odd Task results.
        Operation op = dbClient.createTaskOpStatus(com.emc.storageos.db.client.model.Workflow.class, workflow.getWorkflowURI(),
                taskId, ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME);
        com.emc.storageos.db.client.model.Workflow dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        dbWF.getOpStatus().put(taskId, op);
        Task wfTask = toTask(dbWF, taskId, op);

        // Gather the steps from the DB and wait for the workflow to hit a known "stopped" state
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after suspend: " + state);
        assertTrue(state == WorkflowState.SUSPENDED_NO_ERROR);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("suspended_no_error"));
        // Verify the completion state was filled in
        dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        assertNotNull(dbWF.getCompletionState());
        assertEquals(String.format("Workflow completion state found: " + dbWF.getCompletionState()), dbWF.getCompletionState(),
                "SUSPENDED_NO_ERROR");

        taskStatusMap.put(taskId, WorkflowState.CREATED);
        workflowService.resumeWorkflow(workflow.getWorkflowURI(), UUID.randomUUID().toString());
        state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after resume: " + state);
        assertTrue(state == WorkflowState.SUCCESS);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("ready"));
        // Verify the completion state was filled in
        dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        assertNotNull(dbWF.getCompletionState());
        assertEquals(String.format("Workflow completion state found: " + dbWF.getCompletionState()), dbWF.getCompletionState(),
                "SUCCESS");
        sleepMillis = 0;
        printLog(testname + " completed");
    }

    @Test
    /**
     * This test creates a two layer workflow where a step in the inner WF gets suspended.  Verify the top-level task.
     * The result should be a fully successful workflow.
     */
    public void test11_two_wf_three_steps_method_suspend_first_step_resume_task_verification() {
        // Expected results for this test case
        final String[] testaSuccessSteps = { "L0S1 sub" };
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = { "L1S2 sub", "L0S3 sub", "L1S3 sub" };
        final String[] testaSuspendedSteps = { "L1S1 sub", "L0S2 sub" };

        final String[] testbSuccessSteps = { "L0S1 sub", "L0S2 sub", "L0S3 sub", "L1S1 sub", "L1S2 sub", "L1S3 sub" };
        final String[] testbErrorSteps = {};
        final String[] testbCancelledSteps = {};
        final String[] testbSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        injectedFailures.clear();
        sleepMillis = SLEEP_MILLIS;

        // We're not allowed to (and probably shouldn't) change system properties in the unit tester.
        // So we can override the class/method directly in the Workflow Service.
        workflowService.setSuspendClassMethodTestOnly(this.getClass().getSimpleName() + ".deepfirstnop");
        workflowService.setSuspendOnErrorTestOnly(true);

        String taskId = UUID.randomUUID().toString();

        // Generate a three step workflow.
        Workflow workflow = generate3StepWF(0, 2, taskId);
        Operation op = dbClient.createTaskOpStatus(com.emc.storageos.db.client.model.Workflow.class, workflow.getWorkflowURI(),
                taskId, ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME);
        com.emc.storageos.db.client.model.Workflow dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        dbWF.getOpStatus().put(taskId, op);
        Task wfTask = toTask(dbWF, taskId, op);

        // Gather the steps from the DB and wait for the workflow to hit a known "stopped" state
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after suspend: " + state);
        assertTrue(state == WorkflowState.SUSPENDED_NO_ERROR);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("suspended_no_error"));
        // Verify the completion state was filled in
        dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        assertNotNull(dbWF.getCompletionState());
        assertEquals(String.format("Workflow completion state found: " + dbWF.getCompletionState()), dbWF.getCompletionState(),
                "SUSPENDED_NO_ERROR");

        // Since this will retry the upper-level workflow, it will generate another 3 step workflow, which will also
        // suspend if we don't remove it.
        workflowService.setSuspendClassMethodTestOnly(null);

        // Make sure the task is "pending" while it is running
        taskStatusMap.put(taskId, WorkflowState.CREATED);
        workflowService.resumeWorkflow(workflow.getWorkflowURI(), UUID.randomUUID().toString());
        state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after resume: " + state);
        assertTrue(state == WorkflowState.SUCCESS);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("ready"));
        // Verify the completion state was filled in
        dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        assertNotNull(dbWF.getCompletionState());
        assertEquals(String.format("Workflow completion state found: " + dbWF.getCompletionState()), dbWF.getCompletionState(),
                "SUCCESS");
        sleepMillis = 0;
        printLog(testname + " completed");
    }

    @Test
    /**
     * This test does a suspend of a selected step, followed by a resume after the workflow is suspended.
     * The result should be a successfully completed workflow.
     */
    public void test12_one_wf_three_steps_method_suspend_resume() {
        // Expected results for this test case
        final String[] testaSuccessSteps = { "L0S1 sub" };
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = { "L0S3 sub" };
        final String[] testaSuspendedSteps = { "L0S2 sub" };

        final String[] testbSuccessSteps = { "L0S1 sub", "L0S2 sub", "L0S3 sub" };
        final String[] testbErrorSteps = {};
        final String[] testbCancelledSteps = {};
        final String[] testbSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        injectedFailures.clear();
        workflowService.setSuspendClassMethodTestOnly(this.getClass().getSimpleName() + ".sub");
        workflowService.setSuspendOnErrorTestOnly(true);

        sleepMillis = SLEEP_MILLIS;
        String taskId = UUID.randomUUID().toString();
        Workflow workflow = generate3StepWF(0, 1, taskId);
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        WorkflowStep step2 = stepMap.get("L0S2 sub");
        workflowService.suspendWorkflowStep(workflow.getWorkflowURI(), step2.getId(), UUID.randomUUID().toString());
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after suspend: " + state);
        assertTrue(state == WorkflowState.SUSPENDED_NO_ERROR);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);

        taskStatusMap.put(taskId, WorkflowState.CREATED);
        workflowService.resumeWorkflow(workflow.getWorkflowURI(), UUID.randomUUID().toString());
        state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after resume: " + state);
        assertTrue(state == WorkflowState.SUCCESS);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        sleepMillis = 0;
        printLog(testname + " completed");
    }

    @Test
    /**
     * This tests a two level hierarchical workflow where the lowest level last step fails.
     * After the workflow suspends, rollback the workflow. Then it verifies all the steps were
     * correctly cancelled or rolled back.
     */
    public void test13_two_level_wf_three_steps_error_on_level_1_step_3_rollback() {
        // Expected results for this test case
        final String[] testaSuccessSteps = { "L0S1 sub", "L1S1 sub" };
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = { "L0S3 sub" };
        final String[] testaSuspendedSteps = { "L0S2 sub", "L1S3 sub" };

        final String[] testbSuccessSteps = { "L0S1 sub", "L1S1 sub",
                "Rollback L0S1 sub", "Rollback L0S2 sub", "Rollback L1S1 sub", "Rollback L1S2 sub", "Rollback L1S3 sub", "L1S2 sub" };
        final String[] testbErrorSteps = { "L0S2 sub", "L1S3 sub" };
        final String[] testbCancelledSteps = { "L0S3 sub" };
        final String[] testbSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        workflowService.setSuspendClassMethodTestOnly(null);
        workflowService.setSuspendOnErrorTestOnly(true);
        injectedFailures.clear();
        addInjectedFailure(1, 3); // level 1, step 3
        String taskId = UUID.randomUUID().toString();
        Workflow workflow = generate3StepWF(0, 2, taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Top level workflow state: " + state);
        assertTrue(state == WorkflowState.SUSPENDED_ERROR);
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);
        if (state == WorkflowState.SUSPENDED_ERROR) {
            String rollbackTaskId = UUID.randomUUID().toString();
            taskStatusMap.remove(taskId);
            injectedFailures.clear();
            workflowService.rollbackWorkflow(workflow.getWorkflowURI(), rollbackTaskId);
            state = waitOnWorkflowComplete(taskId);
            printLog("Top level workflow state after rollback: " + state);
            stepMap = readWorkflowFromDb(taskId);
            validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        }
        printLog(testname + " completed");
    }

    @Test
    /**
     * This test will perform a simple workflow that fails on the last step, and is asked to rollback.
     * The rollback step will fail, causing the other rollback steps to be cancelled.
     */
    public void test14_one_wf_three_steps_error_on_step_3_rollback() {
        // Expected results for this test case
        final String[] testaSuccessSteps = { "L0S1 sub", "L0S2 sub" };
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = {};
        final String[] testaSuspendedSteps = { "L0S3 sub" };

        final String[] testbSuccessSteps = { "L0S1 sub", "L0S2 sub" };
        final String[] testbErrorSteps = { "L0S3 sub", "Rollback L0S3 sub" };
        final String[] testbCancelledSteps = { "Rollback L0S1 sub", "Rollback L0S2 sub" };
        final String[] testbSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        workflowService.setSuspendClassMethodTestOnly(null);
        workflowService.setSuspendOnErrorTestOnly(true);
        injectedFailures.clear();
        addInjectedFailure(0, 3); // level 0, step 3
        String taskId = UUID.randomUUID().toString();
        Workflow workflow = generate3StepWF(0, 1, taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Top level workflow state: " + state);
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        assertTrue(state == WorkflowState.SUSPENDED_ERROR);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);
        if (state == WorkflowState.SUSPENDED_ERROR) {
            String rollbackTaskId = UUID.randomUUID().toString();
            taskStatusMap.remove(taskId);
            workflowService.rollbackWorkflow(workflow.getWorkflowURI(), rollbackTaskId);
            state = waitOnWorkflowComplete(taskId);
            printLog("Top level workflow state after rollback: " + state);
            stepMap = readWorkflowFromDb(taskId);
            validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        }
        printLog(testname + " completed");
    }

    @Test
    /**
     * This test makes sure if you have multiple steps with the same signature, they all get suspended.
     */
    public void test15_one_wf_four_step_two_methods_suspended_two_resumes() {
        // Expected results for this test case
        final String[] testaSuccessSteps = { "L0S1 sub" };
        final String[] testaErrorSteps = {};
        final String[] testaCancelledSteps = { "L0S4 sub", "L0S3 sub" };
        final String[] testaSuspendedSteps = { "L0S2 sub", };

        final String[] testbSuccessSteps = { "L0S1 sub", "L0S2 sub" };
        final String[] testbErrorSteps = {};
        final String[] testbCancelledSteps = { "L0S4 sub" };
        final String[] testbSuspendedSteps = { "L0S3 sub" };

        final String[] testcSuccessSteps = { "L0S1 sub", "L0S2 sub", "L0S3 sub", "L0S4 sub" };
        final String[] testcErrorSteps = {};
        final String[] testcCancelledSteps = {};
        final String[] testcSuspendedSteps = {};

        final String testname = new Object() {}.getClass().getEnclosingMethod().getName();
        printLog(testname + " started");
        injectedFailures.clear();
        sleepMillis = SLEEP_MILLIS;

        // We're not allowed to (and probably shouldn't) change system properties in the unit tester.
        // So we can override the class/method directly in the Workflow Service.
        workflowService.setSuspendClassMethodTestOnly(this.getClass().getSimpleName() + ".sub");
        workflowService.setSuspendOnErrorTestOnly(true);

        String taskId = UUID.randomUUID().toString();

        // Generate a three step workflow.
        Workflow workflow = generate4StepWF(0, 1, taskId);
        com.emc.storageos.db.client.model.Workflow dbWF = dbClient.queryObject(com.emc.storageos.db.client.model.Workflow.class,
                workflow.getWorkflowURI());
        Operation op = dbClient.createTaskOpStatus(com.emc.storageos.db.client.model.Workflow.class, workflow.getWorkflowURI(),
                taskId, ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME);
        dbWF.getOpStatus().put(taskId, op);
        Task wfTask = toTask(dbWF, taskId, op);

        // Gather the steps from the DB and wait for the workflow to hit a known "stopped" state
        Map<String, WorkflowStep> stepMap = readWorkflowFromDb(taskId);
        WorkflowState state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after 1st suspend: " + state);
        assertTrue(state == WorkflowState.SUSPENDED_NO_ERROR);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testaSuccessSteps, testaErrorSteps, testaCancelledSteps, testaSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("suspended_no_error"));

        taskStatusMap.put(taskId, WorkflowState.CREATED);
        workflowService.resumeWorkflow(workflow.getWorkflowURI(), UUID.randomUUID().toString());
        state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after1st resume: " + state);
        assertTrue(state == WorkflowState.SUSPENDED_NO_ERROR);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testbSuccessSteps, testbErrorSteps, testbCancelledSteps, testbSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("suspended_no_error"));

        taskStatusMap.put(taskId, WorkflowState.CREATED);
        workflowService.resumeWorkflow(workflow.getWorkflowURI(), UUID.randomUUID().toString());
        state = waitOnWorkflowComplete(taskId);
        printLog("Workflow state after resume: " + state);
        assertTrue(state == WorkflowState.SUCCESS);
        stepMap = readWorkflowFromDb(taskId);
        validateStepStates(stepMap, testcSuccessSteps, testcErrorSteps, testcCancelledSteps, testcSuspendedSteps);
        wfTask = dbClient.queryObject(Task.class, wfTask.getId());
        assertTrue(wfTask.getStatus().equals("ready"));
        sleepMillis = 0;
        printLog(testname + " completed");
    }

    private Task toTask(DataObject resource, String taskId, Operation operation) {
        // If the Operation has been serialized in this request, then it should have the corresponding task embedded in
        // it
        Task task = operation.getTask(resource.getId());
        if (task != null) {
            return task;
        } else {
            // It wasn't recently serialized, so fallback to looking for the task in the DB
            task = TaskUtils.findTaskForRequestId(dbClient, resource.getId(), taskId);
            if (task != null) {
                return task;
            }
            else {
                throw new IllegalStateException(String.format(
                        "Task not found for resource %s, op %s in either the operation or the database", resource.getId(), taskId));
            }
        }
    }

    Workflow.Method nopMethod(int level, int step) {
        return new Workflow.Method("nop", level, step);
    }

    Workflow.Method deeplastnopMethod(int level, int step) {
        return new Workflow.Method("deeplastnop", level, step);
    }

    Workflow.Method deepfirstnopMethod(int level, int step) {
        return new Workflow.Method("deepfirstnop", level, step);
    }

    public void nop(int level, int step, String stepId) {
        WorkflowStepCompleter.stepExecuting(stepId);
        if (sleepMillis > 0) {
            try {
                Thread.sleep(sleepMillis);
            } catch (Exception ex) {
                // no action
            }
        }
        if (hasInjectedFailure(level, step)) {
            log.info("Injecting failure in step: " + genMsg(level, step, "nop"));
            ServiceCoded coded = WorkflowException.errors.unforeseen();
            WorkflowStepCompleter.stepFailed(stepId, coded);
        } else {
            WorkflowStepCompleter.stepSucceded(stepId);
        }
    }

    public void deepfirstnop(int level, int step, String stepId) {
        WorkflowStepCompleter.stepExecuting(stepId);
        if (sleepMillis > 0) {
            try {
                Thread.sleep(sleepMillis);
            } catch (Exception ex) {
                // no action
            }
        }
        if (hasInjectedFailure(level, step)) {
            log.info("Injecting failure in step: " + genMsg(level, step, "deepfirstnop"));
            ServiceCoded coded = WorkflowException.errors.unforeseen();
            WorkflowStepCompleter.stepFailed(stepId, coded);
        } else {
            WorkflowStepCompleter.stepSucceded(stepId);
        }
    }

    public void deeplastnop(int level, int step, String stepId) {
        WorkflowStepCompleter.stepExecuting(stepId);
        if (sleepMillis > 0) {
            try {
                Thread.sleep(sleepMillis);
            } catch (Exception ex) {
                // no action
            }
        }
        if (hasInjectedFailure(level, step)) {
            log.info("Injecting failure in step: " + genMsg(level, step, "deeplastnop"));
            ServiceCoded coded = WorkflowException.errors.unforeseen();
            WorkflowStepCompleter.stepFailed(stepId, coded);
        } else {
            WorkflowStepCompleter.stepSucceded(stepId);
        }
    }

    Workflow.Method subMethod(int level, int maxLevels, int step) {
        return new Workflow.Method("sub", level, maxLevels, step);
    }

    /**
     * Workflow step to optionally create a sub-workflow.
     * 
     * @param level
     *            - current workflow level
     * @param maxLevels
     *            - maximum number of workflow levels
     * @param error
     *            - generate error if requested.
     * @param stepId
     */
    public void sub(int level, int maxLevels, int stepIndex, String stepId) {
        WorkflowStepCompleter.stepExecuting(stepId);
        if (sleepMillis > 0) {
            try {
                Thread.sleep(sleepMillis);
            } catch (Exception ex) {
                // no action
            }
        }
        if (hasInjectedFailure(level, stepIndex)) {
            log.info("Injecting failure in step: " + genMsg(level, stepIndex, "sub"));
            ServiceCoded coded = WorkflowException.errors.unforeseen();
            WorkflowStepCompleter.stepFailed(stepId, coded);
            return;
        }
        if (++level >= maxLevels) {
            WorkflowStepCompleter.stepSucceded(stepId);
        } else {
            String workflowMapping = "generate3StepWF:" + stepId + ":" + level;
            if (workflowsKickedOff.contains(workflowMapping)) {
                printLog("Idempotent check: already created/executed workflow from this step, not creating another one: " + workflowMapping);
            } else {
                // Generate a sub-workflow. The completer will complete this step.
                printLog("Generating a new 3 step WF");
                generate3StepWF(level, maxLevels, stepId);
                workflowsKickedOff.add(workflowMapping);
            }
        }
    }

    /**
     * Generates a 3 step workflow. May generate a sub workflow for the middle step.
     * First step is nop, second step is a sub-workflow or nop, and third step is nop.
     * 
     * @param level
     *            -- current level ... maxLevels
     * @param maxLevels
     *            -- max level
     * @param orchTaskId
     *            -- the orchestration task id for the workflow
     * @return
     */
    Set<String> workflowsKickedOff = new HashSet<String>();

    public Workflow generate3StepWF(int level, int maxLevels, String orchTaskId) {
        String[] args = new String[1];
        args[0] = orchTaskId;
        taskStatusMap.put(orchTaskId, WorkflowState.CREATED);
        Workflow workflow = workflowService.getNewWorkflow(this, "generate3StepWF", false, orchTaskId);
        WorkflowTaskCompleter completer = new WorkflowTaskCompleter(workflow.getWorkflowURI(), orchTaskId);
        // first step
        String lastStep = null;
        if (level + 1 == maxLevels) {
            lastStep = workflow.createStep("first deep", genMsg(level, 1, "sub"), null, nullURI,
                    this.getClass().getName(), false, this.getClass(), deepfirstnopMethod(level, 1), deepfirstnopMethod(level, 1), null);
        } else {
            lastStep = workflow.createStep("first", genMsg(level, 1, "sub"), null, nullURI,
                    this.getClass().getName(), false, this.getClass(), nopMethod(level, 1), nopMethod(level, 1), null);
        }
        // second step
        lastStep = workflow.createStep("second", genMsg(level, 2, "sub"), lastStep, nullURI,
                this.getClass().getName(), false, this.getClass(), subMethod(level, maxLevels, 2), nopMethod(level, 2), null);
        // third step
        if (level + 1 == maxLevels) {
            lastStep = workflow.createStep("third deep", genMsg(level, 3, "sub"), lastStep, nullURI,
                    this.getClass().getName(), false, this.getClass(), deeplastnopMethod(level, 3), deeplastnopMethod(level, 3), null);
        } else {
            lastStep = workflow.createStep("third", genMsg(level, 3, "sub"), lastStep, nullURI,
                    this.getClass().getName(), false, this.getClass(), nopMethod(level, 3), nopMethod(level, 3), null);
        }
        // Execute and go
        workflow.executePlan(completer, String.format("Workflow level %d successful", level), new WorkflowCallback(), args, null, null);
        return workflow;
    }

    /**
     * Generates a 3 step workflow. May generate a sub workflow for the middle step and third step.
     * First step is nop, second step is a sub-workflow or nop, and third step is sub-workflow or nop
     * 
     * @param level
     *            -- current level ... maxLevels
     * @param maxLevels
     *            -- max level
     * @param orchTaskId
     *            -- the orchestration task id for the workflow
     * @return
     */
    public Workflow generate4StepWF(int level, int maxLevels, String orchTaskId) {
        String[] args = new String[1];
        args[0] = orchTaskId;
        taskStatusMap.put(orchTaskId, WorkflowState.CREATED);
        Workflow workflow = workflowService.getNewWorkflow(this, "generate3StepWFForTest10", false, orchTaskId);
        WorkflowTaskCompleter completer = new WorkflowTaskCompleter(workflow.getWorkflowURI(), orchTaskId);
        // first step
        String lastStep = workflow.createStep("first", genMsg(level, 1, "sub"), null, nullURI,
                this.getClass().getName(), false, this.getClass(), nopMethod(level, 1), nopMethod(level, 1), null);
        // second step
        lastStep = workflow.createStep("second", genMsg(level, 2, "sub"), lastStep, nullURI,
                this.getClass().getName(), false, this.getClass(), subMethod(level, maxLevels, 2), nopMethod(level, 2), null);
        // third step
        lastStep = workflow.createStep("third", genMsg(level, 3, "sub"), lastStep, nullURI,
                this.getClass().getName(), false, this.getClass(), subMethod(level, maxLevels, 3), nopMethod(level, 3), null);
        // fourth step
        lastStep = workflow.createStep("fourth", genMsg(level, 4, "sub"), lastStep, nullURI,
                this.getClass().getName(), false, this.getClass(), nopMethod(level, 4), nopMethod(level, 4), null);
        // Execute and go
        workflow.executePlan(completer, String.format("Workflow level %d successful", level), new WorkflowCallback(), args, null, null);
        return workflow;
    }

    private String genMsg(int level, int step, String msg) {
        return String.format("L%dS%d %s", level, step, msg);
    }

    /**
     * Reads a workflow from the database. Called recursively for sub-workflows.
     * 
     * @param orchTaskId
     *            -- the Orchestration task id.
     * @return A map from step description to WorkflowStep entry
     */
    private Map<String, WorkflowStep> readWorkflowFromDb(String orchTaskId) {
        Map<String, WorkflowStep> msgToStep = new HashMap<String, WorkflowStep>();
        Joiner j = new Joiner(dbClient);
        List<WorkflowStep> steps =
                j.join(com.emc.storageos.db.client.model.Workflow.class, "wf")
                        .match("orchTaskId", orchTaskId)
                        .join("wf", WorkflowStep.class, "step", "workflow")
                        .go().list("step");
        for (WorkflowStep step : steps) {
            msgToStep.put(step.getDescription(), step);
            System.out.println(String.format("Step %s: status: %s message: %s", step.getDescription(), step.getState(), step.getMessage()));
            // Check for sub-workflow.
            Map<String, WorkflowStep> subWorkflowMap = readWorkflowFromDb(step.getStepId());
            msgToStep.putAll(subWorkflowMap);
        }
        return msgToStep;
    }

    /**
     * Given the step descrption to WorkflowStep map computed by readWorkflowsFromDb,
     * check that the steps (as given by the descriptions) are in the correct states.
     * 
     * @param descriptionToStepMap
     * @param successSteps
     * @param errorSteps
     * @param cancelledSteps
     */
    void validateStepStates(Map<String, WorkflowStep> descriptionToStepMap,
            String[] successSteps, String[] errorSteps, String[] cancelledSteps, String[] suspendedSteps) {
        // check success steps
        for (String successStep : successSteps) {
            WorkflowStep step = descriptionToStepMap.get(successStep);
            assertNotNull("Step not found: " + successStep, step);
            assertEquals(String.format("Step %s expected SUCCESS but in state %s", step.getDescription(), step.getState()),
                    StepState.SUCCESS.name(), step.getState());
        }
        // check error steps
        for (String errorStep : errorSteps) {
            WorkflowStep step = descriptionToStepMap.get(errorStep);
            assertNotNull("Step not found: " + errorStep, step);
            assertEquals(String.format("Step %s expected ERROR but in state %s", step.getDescription(), step.getState()),
                    StepState.ERROR.name(), step.getState());
        }
        // check cancelled steps
        for (String cancelledStep : cancelledSteps) {
            WorkflowStep step = descriptionToStepMap.get(cancelledStep);
            assertNotNull("Step not found: " + cancelledStep, step);
            assertEquals(String.format("Step %s expected CANCELLED but in state %s", step.getDescription(), step.getState()),
                    StepState.CANCELLED.name(), step.getState());
        }
        // check suspended steps
        for (String suspendedStep : suspendedSteps) {
            WorkflowStep step = descriptionToStepMap.get(suspendedStep);
            assertNotNull("Step not found: " + suspendedStep, step);
            boolean isSuspended = (step.getState().equalsIgnoreCase(StepState.SUSPENDED_ERROR.name()) || step.getState().equalsIgnoreCase(
                    StepState.SUSPENDED_NO_ERROR.name()));
            assertTrue(String.format("Step %s expected SUSPENDED but in state %s", step.getDescription(), step.getState()),
                    isSuspended);
        }
    }

    private static class WorkflowCallback implements Workflow.WorkflowCallbackHandler, Serializable {
        @SuppressWarnings("unchecked")
        @Override
        public void workflowComplete(Workflow workflow, Object[] args)
                throws WorkflowException {
            String taskId = (String) args[0];
            printLog("Adding state " + workflow.getWorkflowState() + " to task ID: " + taskId);
            WorkflowTest.getTaskStatusMap().put(taskId, workflow.getWorkflowState());
        }
    }

    private WorkflowState waitOnWorkflowComplete(String taskId) {
        while (taskStatusMap.get(taskId) == null
                || taskStatusMap.get(taskId) == WorkflowState.CREATED
                || taskStatusMap.get(taskId) == WorkflowState.RUNNING
                || taskStatusMap.get(taskId) == WorkflowState.ROLLING_BACK) {
            try {
                // printLog("Checking state " + taskStatusMap.get(taskId) + " for task ID: " + taskId);
                Thread.sleep(1000);
            } catch (Exception e) {
                log.info("Sleep interrupted");
                ;
            }
        }
        log.info(String.format("Workflow task %s reported state %s", taskId, taskStatusMap.get(taskId)));
        return taskStatusMap.get(taskId);
    }

    public static Map<String, WorkflowState> getTaskStatusMap() {
        return taskStatusMap;
    }

    public static void setTaskStatusMap(Map<String, WorkflowState> taskStatusMap) {
        WorkflowTest.taskStatusMap = taskStatusMap;
    }

    private static void printLog(String s) {
        System.out.println(s);
        log.info(s);
        ;
    }

}

/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.scheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.api.context.Context;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.openmrs.test.BaseContextSensitiveTest;
import org.openmrs.util.OpenmrsClassLoader;

/**
 * TODO test all methods in SchedulerService
 */
public class SchedulerServiceTest extends BaseContextSensitiveTest {
	
	// so that we can guarantee tests running accurately instead of tests interfering with the next
	public final Integer TASK_TEST_METHOD_LOCK = new Integer(1);
	
	// used to check for concurrent task execution. Only initialized by code protected by TASK_TEST_METHOD_LOCK.
	public static CountDownLatch latch;
	
	public static AtomicBoolean awaitFailed = new AtomicBoolean(false);
	
	public static AtomicBoolean consecutiveInitResult = new AtomicBoolean(false);
	
	// time to wait for concurrent tasks to execute, should only wait this long if there's a test failure
	public static final long CONCURRENT_TASK_WAIT_MS = 30000;
	
	@Before
	public void setUp() throws Exception {
		Context.flushSession();
		
		Collection<TaskDefinition> tasks = Context.getSchedulerService().getRegisteredTasks();
		for (TaskDefinition task : tasks) {
			Context.getSchedulerService().shutdownTask(task);
			Context.getSchedulerService().deleteTask(task.getId());
		}
		
		Context.flushSession();
		getConnection().commit(); // tasks reappear at the start of the next test otherwise
	}
	
	@Test
	public void shouldResolveValidTaskClass() throws Exception {
		String className = "org.openmrs.scheduler.tasks.TestTask";
		Class<?> c = OpenmrsClassLoader.getInstance().loadClass(className);
		Object o = c.newInstance();
		if (o instanceof Task)
			assertTrue("Class " + className + " is a valid Task", true);
		else
			fail("Class " + className + " is not a valid Task");
	}
	
	@Test(expected = ClassNotFoundException.class)
	public void shouldNotResolveInvalidClass() throws Exception {
		String className = "org.openmrs.scheduler.tasks.InvalidTask";
		Class<?> c = OpenmrsClassLoader.getInstance().loadClass(className);
		Object o = c.newInstance();
		if (o instanceof Task)
			fail("Class " + className + " is not supposed to be a valid Task");
		else
			assertTrue("Class " + className + " is not a valid Task", true);
	}
	
	private TaskDefinition makeRepeatingTaskThatStartsImmediately(String taskClassName) {
		TaskDefinition taskDef = new TaskDefinition();
		taskDef.setTaskClass(taskClassName);
		taskDef.setStartOnStartup(false);
		taskDef.setStartTime(null);
		taskDef.setName("name");
		taskDef.setRepeatInterval(CONCURRENT_TASK_WAIT_MS * 10); // latch should timeout before task ever repeats
		// save task definition to generate a unique ID, otherwise the scheduler thinks they're duplicates and tries to shut one down
		Context.getSchedulerService().saveTaskDefinition(taskDef);
		return taskDef;
	}
	
	/**
	 * Demonstrates concurrent running for tasks
	 */
	@Test
	public void shouldAllowTwoTasksToRunConcurrently() throws Exception {
		TaskDefinition t1 = makeRepeatingTaskThatStartsImmediately(LatchExecuteTask.class.getName());
		TaskDefinition t2 = makeRepeatingTaskThatStartsImmediately(LatchExecuteTask.class.getName());
		
		checkTasksRunConcurrently(t1, t2);
	}
	
	/**
	 * Demonstrates concurrent initializing for tasks
	 */
	@Test
	public void shouldAllowTwoTasksInitMethodsToRunConcurrently() throws Exception {
		TaskDefinition t3 = makeRepeatingTaskThatStartsImmediately(LatchInitializeTask.class.getName());
		TaskDefinition t4 = makeRepeatingTaskThatStartsImmediately(LatchInitializeTask.class.getName());
		
		checkTasksRunConcurrently(t3, t4);
	}
	
	private void checkTasksRunConcurrently(TaskDefinition t1, TaskDefinition t2) throws SchedulerException,
	        InterruptedException {
		
		SchedulerService schedulerService = Context.getSchedulerService();
		
		// synchronized on a class level object in case a test runner is running test methods concurrently
		synchronized (TASK_TEST_METHOD_LOCK) {
			latch = new CountDownLatch(2);
			awaitFailed.set(false);
			
			schedulerService.scheduleTask(t1);
			schedulerService.scheduleTask(t2);
			
			// wait for the tasks to call countDown()
			assertTrue("methods ran consecutively or not at all", latch
			        .await(CONCURRENT_TASK_WAIT_MS, TimeUnit.MILLISECONDS));
			// the main await() didn't fail so both tasks ran and called countDown(), 
			// but if the first await() failed and the latch still reached 0 then the tasks must have been running consecutively 
			assertTrue("methods ran consecutively", !awaitFailed.get());
		}
		schedulerService.shutdownTask(t1);
		schedulerService.shutdownTask(t2);
	}
	
	public abstract static class LatchTask extends AbstractTask {
		
		protected void waitForLatch() {
			try {
				latch.countDown();
				// wait here until the other task thread(s) also countDown the latch
				// if they do then they must be executing concurrently with this task
				if (!latch.await(CONCURRENT_TASK_WAIT_MS, TimeUnit.MILLISECONDS)) {
					// this wait timed out, record it as otherwise the next
					// task(s) could execute consecutively rather than concurrently 
					awaitFailed.set(true);
				}
			}
			catch (InterruptedException ignored) {}
		}
	}
	
	/**
	 * task that waits in its initialize method until all other tasks on the same latch have called
	 * initialize()
	 */
	public static class LatchInitializeTask extends LatchTask {
		
		public void initialize(TaskDefinition config) {
			super.initialize(config);
			waitForLatch();
		}
		
		public void execute() {
		}
	}
	
	/**
	 * task that waits in its execute method until all other tasks on the same latch have called
	 * execute()
	 */
	public static class LatchExecuteTask extends LatchTask {
		
		public void initialize(TaskDefinition config) {
			super.initialize(config);
		}
		
		public void execute() {
			waitForLatch();
		}
	}
	
	/**
	 * task that checks for its execute method running at the same time as its initialize method
	 */
	public static class InitSequenceTestTask extends AbstractTask {
		
		public void initialize(TaskDefinition config) {
			
			super.initialize(config);
			
			// wait for any other thread to run the execute method
			try {
				Thread.sleep(700);
			}
			catch (InterruptedException ignored) {}
			
			// set to false if execute() method was running concurrently and has cleared the latch
			consecutiveInitResult.set(latch.getCount() != 0);
		}
		
		@Override
		public void execute() {
			// clear the latch to signal the main thread
			latch.countDown();
		}
	}
	
	/**
	 * Demonstrates that initialization of a task is accomplished before its execution without
	 * interleaving, which is a non-trivial behavior in the presence of a threaded initialization
	 * method (as implemented in TaskThreadedInitializationWrapper)
	 */
	@Test
	public void shouldNotAllowTaskExecuteToRunBeforeInitializationIsComplete() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		
		TaskDefinition t5 = new TaskDefinition();
		t5.setStartOnStartup(false);
		t5.setStartTime(null); // immediate start
		t5.setTaskClass(InitSequenceTestTask.class.getName());
		t5.setName("name");
		t5.setRepeatInterval(CONCURRENT_TASK_WAIT_MS * 4);
		
		synchronized (TASK_TEST_METHOD_LOCK) {
			// wait for the task to complete
			latch = new CountDownLatch(1);
			consecutiveInitResult.set(false);
			schedulerService.saveTaskDefinition(t5);
			schedulerService.scheduleTask(t5);
			assertTrue("Init and execute methods should run consecutively", latch.await(CONCURRENT_TASK_WAIT_MS,
			    TimeUnit.MILLISECONDS)
			        && consecutiveInitResult.get());
		}
		schedulerService.shutdownTask(t5);
	}
	
	@Test
	public void saveTask_shouldSaveTaskToTheDatabase() throws Exception {
		SchedulerService service = Context.getSchedulerService();
		
		TaskDefinition def = new TaskDefinition();
		final String TASK_NAME = "This is my test! 123459876";
		def.setName(TASK_NAME);
		def.setStartOnStartup(false);
		def.setRepeatInterval(10000000L);
		def.setTaskClass(LatchExecuteTask.class.getName());
		
		synchronized (TASK_TEST_METHOD_LOCK) {
			int size = service.getRegisteredTasks().size();
			service.saveTask(def);
			Assert.assertEquals(size + 1, service.getRegisteredTasks().size());
		}
		
		def = service.getTaskByName(TASK_NAME);
		Assert.assertEquals(Context.getAuthenticatedUser().getUserId(), def.getCreator().getUserId());
	}
	
	/**
	 * Sample task that does not extend AbstractTask
	 */
	public static class BareTask implements Task {
		
		public void execute() {
			latch.countDown();
		}
		
		public TaskDefinition getTaskDefinition() {
			return null;
		}
		
		public void initialize(TaskDefinition definition) {
		}
		
		public boolean isExecuting() {
			return false;
		}
		
		public void shutdown() {
		}
	}
	
	/**
	 * Task which does not return TaskDefinition in getTaskDefinition should run without throwing
	 * exceptions.
	 * 
	 * @throws Exception
	 */
	@Test
	public void shouldNotThrowExceptionWhenTaskDefinitionIsNull() throws Exception {
		SchedulerService schedulerService = Context.getSchedulerService();
		
		TaskDefinition td = new TaskDefinition();
		td.setName("Task");
		td.setStartOnStartup(false);
		td.setTaskClass(BareTask.class.getName());
		td.setStartTime(null);
		td.setName("name");
		td.setRepeatInterval(5000l);
		
		synchronized (TASK_TEST_METHOD_LOCK) {
			latch = new CountDownLatch(1);
			schedulerService.saveTaskDefinition(td);
			schedulerService.scheduleTask(td);
			assertTrue(latch.await(CONCURRENT_TASK_WAIT_MS, TimeUnit.MILLISECONDS));
		}
	}
	
	/**
	 * Just stores the execution time.
	 */
	public static class StoreExecutionTimeTask extends AbstractTask {
		
		public void execute() {
			actualExecutionTime = System.currentTimeMillis();
			// signal the test method that the task has executed
			latch.countDown();
		}
	}
	
	public static Long actualExecutionTime;
	
	/**
	 * Check saved last execution time.
	 */
	@Test
	public void shouldSaveLastExecutionTime() throws Exception {
		final String NAME = "StoreExecutionTime Task";
		SchedulerService service = Context.getSchedulerService();
		
		TaskDefinition td = new TaskDefinition();
		td.setName(NAME);
		td.setStartOnStartup(false);
		td.setTaskClass(StoreExecutionTimeTask.class.getName());
		td.setStartTime(null);
		td.setRepeatInterval(new Long(0));//0 indicates single execution
		synchronized (TASK_TEST_METHOD_LOCK) {
			latch = new CountDownLatch(1);
			service.saveTaskDefinition(td);
			service.scheduleTask(td);
			
			// wait for the task to execute
			assertTrue("task didn't execute", latch.await(CONCURRENT_TASK_WAIT_MS, TimeUnit.MILLISECONDS));
			
			// wait for the SchedulerService to update the execution time
			for (int x = 0; x < 100; x++) {
				// refetch the task
				td = service.getTaskByName(NAME);
				if (td.getLastExecutionTime() != null) {
					break;
				}
				Thread.sleep(200);
			}
			assertNotNull(
			    "actualExecutionTime is null, so either the SessionTask.execute method hasn't finished or didn't get run",
			    actualExecutionTime);
			assertNotNull("lastExecutionTime is null, so the SchedulerService didn't save it", td.getLastExecutionTime());
			assertEquals("Last execution time in seconds is wrong", actualExecutionTime.longValue() / 1000, td
			        .getLastExecutionTime().getTime() / 1000, 1);
		}
	}
}

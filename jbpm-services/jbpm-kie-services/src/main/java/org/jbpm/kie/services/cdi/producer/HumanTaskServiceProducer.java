package org.jbpm.kie.services.cdi.producer;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.persistence.EntityManagerFactory;

import org.jbpm.services.task.HumanTaskConfigurator;
import org.jbpm.services.task.HumanTaskServiceFactory;
import org.jbpm.services.task.impl.command.CommandBasedTaskService;
import org.jbpm.services.task.lifecycle.listeners.TaskLifeCycleEventListener;
import org.kie.api.task.UserGroupCallback;
import org.kie.internal.task.api.InternalTaskService;
import org.kie.internal.task.api.UserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI producer for <code>TaskService</code> instances. By default it runs in new mode, 
 * meaning new <code>TaskService</code> instance for every injection point.
 * This behavior can be altered by setting <code>org.jbpm.cdi.taskservice.mode</code> system 
 * property to one of the values.
 * <ul>
 * 	<li>none - disables producer to not return TaskService instances</li>
 * 	<li>singleton - produces only one instance of TaskService that will be shared</li>
 * 	<li>new - produces new instance for every injection point</li>
 * </ul>
 * This bean accept following injections:
 * <ul>
 * 	<li>UserGroupCallback</li>
 * 	<li>UserInfo</li>
 * 	<li>TaskLifeCycleEventListener</li>
 * </ul>
 * all of these are optional injections and if not available defaults will be used. Underneath it uses
 * <code>HumanTaskConfigurator</code> for <code>TaskService</code> instances creations.
 * 
 * @see HumanTaskConfigurator
 */
@ApplicationScoped
public class HumanTaskServiceProducer {
	
	private static final Logger logger = LoggerFactory.getLogger(HumanTaskServiceProducer.class);
	final String mode = System.getProperty("org.jbpm.cdi.taskservice.mode", "new");
	
	@Inject
	private Instance<UserGroupCallback> userGroupCallback;
	
	@Inject
	private Instance<UserInfo> userInfo;
	
	@Inject
	@Any
	private Instance<TaskLifeCycleEventListener> taskListeners;

	// internal member to ensure only single instance of task service is produced
	private InternalTaskService taskService;
	
	@Produces
	public CommandBasedTaskService produceTaskService(EntityManagerFactory emf) {
		if (mode.equalsIgnoreCase("none")) {
			return null;
		}
		if (taskService == null) {
			HumanTaskConfigurator configurator = HumanTaskServiceFactory.newTaskServiceConfigurator()
					.entityManagerFactory(emf)
					.userGroupCallback(safeGet(userGroupCallback))
					.userInfo(safeGet(userInfo));
					
			try {
				for (TaskLifeCycleEventListener listener : taskListeners) {
					configurator.listener(listener);
					logger.debug("Registering listener {}", listener);
				}
			} catch (Exception e) {
				logger.warn("Cannot add listeners to task service due to {}", e.getMessage());
			}
			if (mode.equalsIgnoreCase("singleton")) {
				this.taskService = (CommandBasedTaskService) configurator.getTaskService();
			} else {
				return (CommandBasedTaskService) configurator.getTaskService();
			}
		}
		
		return (CommandBasedTaskService)taskService;
	}
	
	protected <T> T safeGet(Instance<T> instance) {
		try {
			T object = instance.get();
			logger.debug("About to set object {} on task service", object);
			return object;
		} catch (Throwable e) {
			logger.warn("Cannot get value of of instance {} due to {}", instance, e.getMessage());
		}
		
		return null;
	}

}

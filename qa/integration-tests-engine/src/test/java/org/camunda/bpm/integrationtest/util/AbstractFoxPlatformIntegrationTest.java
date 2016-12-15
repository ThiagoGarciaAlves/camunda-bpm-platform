/**
 * Copyright (C) 2011, 2012 camunda services GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.integrationtest.util;

import org.camunda.bpm.BpmPlatform;
import org.camunda.bpm.ProcessEngineService;
import org.camunda.bpm.engine.*;
import org.camunda.bpm.engine.impl.ProcessEngineImpl;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.integrationtest.deployment.callbacks.PurgeDatabaseServlet;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;


public abstract class AbstractFoxPlatformIntegrationTest {

  protected Logger logger = Logger.getLogger(AbstractFoxPlatformIntegrationTest.class.getName());

  protected ProcessEngineService processEngineService;
//  protected ProcessArchiveService processArchiveService;
  protected ProcessEngine processEngine;
  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected FormService formService;
  protected HistoryService historyService;
  protected IdentityService identityService;
  protected ManagementService managementService;
  protected RepositoryService repositoryService;
  protected RuntimeService runtimeService;
  protected TaskService taskService;
  protected CaseService caseService;
  protected DecisionService decisionService;

  private static String warName = "test.war";

  public static WebArchive initWebArchiveDeployment(final String name, String processesXmlPath) {
    final JavaArchive purgeJar = ShrinkWrap.create(JavaArchive.class, "purge.jar");
    purgeJar.addClass(PurgeDatabaseServlet.class);
    WebArchive archive = ShrinkWrap.create(WebArchive.class, name)
              .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml")
              .addAsLibraries(DeploymentHelper.getEngineCdi())
              .addAsResource(processesXmlPath, "META-INF/processes.xml")
              .addClass(AbstractFoxPlatformIntegrationTest.class)
              .addClass(TestConstants.class)
              .addAsLibraries(purgeJar)
              .setWebXML(new StringAsset("<web-app version='2.5' xmlns='http://java.sun.com/xml/ns/javaee' " +
                "xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' " +
                "xsi:schemaLocation='http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd'>" +
                "<servlet>" +
                "<servlet-name>purgeServlet</servlet-name>" +
                "<servlet-class>" + PurgeDatabaseServlet.class.getName() + "</servlet-class>" +
                "<load-on-startup>1</load-on-startup>" +
                "</servlet>" +
                "<servlet-mapping>" +
                "<servlet-name>purgeServlet</servlet-name>" +
                "<url-pattern>/purge</url-pattern>" +
                "</servlet-mapping>" +
                "</web-app>"));

    TestContainer.addContainerSpecificResources(archive);

    warName = archive.getName();

    return archive;
  }

  public static WebArchive initWebArchiveDeployment(String name) {
    return initWebArchiveDeployment(name, "META-INF/processes.xml");
  }

  public static WebArchive initWebArchiveDeployment() {
    return initWebArchiveDeployment(warName);
  }


  @Before
  public void setupBeforeTest() {
    processEngineService = BpmPlatform.getProcessEngineService();
    processEngine = processEngineService.getDefaultProcessEngine();
    processEngineConfiguration = ((ProcessEngineImpl)processEngine).getProcessEngineConfiguration();
    processEngineConfiguration.getJobExecutor().shutdown(); // make sure the job executor is down
    formService = processEngine.getFormService();
    historyService = processEngine.getHistoryService();
    identityService = processEngine.getIdentityService();
    managementService = processEngine.getManagementService();
    repositoryService = processEngine.getRepositoryService();
    runtimeService = processEngine.getRuntimeService();
    taskService = processEngine.getTaskService();
    caseService = processEngine.getCaseService();
    decisionService = processEngine.getDecisionService();
  }

  @After
  public void cleanUp()  {
    logger.log(Level.INFO, "After test - cleanup");
    HttpURLConnection httpURLConnection = null;
    try {

      URL url = new URL("http", "localhost", 38080, "/" + warName.replace(".war", "") + "/purge");
      URLConnection urlConnection = url.openConnection();
      httpURLConnection = (HttpURLConnection) urlConnection;
      httpURLConnection.setRequestMethod("POST");
      httpURLConnection.setDoOutput(true);
      httpURLConnection.connect();

      logger.log(Level.INFO, url.toString());
      int responseCode = httpURLConnection.getResponseCode();
      logger.log(Level.INFO, "Response code: " + responseCode);

      if (responseCode != 201) {
        String responseMessage = httpURLConnection.getResponseMessage();
        Assert.fail(responseMessage);
      }
    } catch (IOException ioe) {
      logger.log(Level.SEVERE, ioe.getMessage(), ioe);
    } finally {
      if (httpURLConnection != null) {
        httpURLConnection.disconnect();
      }
    }
  }

  public void waitForJobExecutorToProcessAllJobs() {
    waitForJobExecutorToProcessAllJobs(12000);
  }

  public void waitForJobExecutorToProcessAllJobs(long maxMillisToWait) {

    JobExecutor jobExecutor = processEngineConfiguration.getJobExecutor();
    waitForJobExecutorToProcessAllJobs(jobExecutor, maxMillisToWait);
  }

  public void waitForJobExecutorToProcessAllJobs(JobExecutor jobExecutor, long maxMillisToWait) {

    int checkInterval = 1000;

    jobExecutor.start();

    try {
      Timer timer = new Timer();
      InterruptTask task = new InterruptTask(Thread.currentThread());
      timer.schedule(task, maxMillisToWait);
      boolean areJobsAvailable = true;
      try {
        while (areJobsAvailable && !task.isTimeLimitExceeded()) {
          Thread.sleep(checkInterval);
          areJobsAvailable = areJobsAvailable();
        }
      } catch (InterruptedException e) {
      } finally {
        timer.cancel();
      }
      if (areJobsAvailable) {
        throw new RuntimeException("time limit of " + maxMillisToWait + " was exceeded (still " + numberOfJobsAvailable() + " jobs available)");
      }

    } finally {
      jobExecutor.shutdown();
    }
  }

  public boolean areJobsAvailable() {
    List<Job> list = managementService.createJobQuery().list();
    for (Job job : list) {
      if (isJobAvailable(job)) {
        return true;
      }
    }
    return false;
  }

  public boolean isJobAvailable(Job job) {
    return job.getRetries() > 0 && (job.getDuedate() == null || ClockUtil.getCurrentTime().after(job.getDuedate()));
  }

  public int numberOfJobsAvailable() {
    int numberOfJobs = 0;
    List<Job> jobs = managementService.createJobQuery().list();
    for (Job job : jobs) {
      if (isJobAvailable(job)) {
        numberOfJobs++;
      }
    }
    return numberOfJobs;
  }

  private static class InterruptTask extends TimerTask {

    protected boolean timeLimitExceeded = false;
    protected Thread thread;

    public InterruptTask(Thread thread) {
      this.thread = thread;
    }
    public boolean isTimeLimitExceeded() {
      return timeLimitExceeded;
    }
    public void run() {
      timeLimitExceeded = true;
      thread.interrupt();
    }
  }

}

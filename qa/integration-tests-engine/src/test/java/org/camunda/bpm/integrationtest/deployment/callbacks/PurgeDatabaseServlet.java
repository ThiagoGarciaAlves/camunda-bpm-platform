package org.camunda.bpm.integrationtest.deployment.callbacks;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.cdi.impl.util.ProgrammaticBeanLookup;
import org.camunda.bpm.engine.impl.ManagementServiceImpl;
import org.camunda.bpm.engine.impl.management.PurgeReport;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Christopher Zell <christopher.zell@camunda.com>
 */
public class PurgeDatabaseServlet extends HttpServlet {

  protected Logger logger = Logger.getLogger(PurgeDatabaseServlet.class.getName());

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

    logger.log(Level.INFO, "=== PurgeDatabaseServlet ===");
    ProcessEngine engine = ProgrammaticBeanLookup.lookup(ProcessEngine.class);
    ManagementServiceImpl managementService = (ManagementServiceImpl) engine.getManagementService();
    PurgeReport report = managementService.purge();

    if (report.isEmpty()) {
      logger.log(Level.INFO, "Clean DB and cache.");
      resp.setStatus(201);
    } else {
      logger.log(Level.INFO, "DB or cache was not clean.");
      resp.setStatus(400);
      PrintWriter writer = resp.getWriter();
      writer.append(report.toString());
    }
  }
}

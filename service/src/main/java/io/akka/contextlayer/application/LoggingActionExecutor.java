package io.akka.contextlayer.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default executor until connectors exist: records the call in the service log and succeeds. */
public class LoggingActionExecutor implements ActionExecutor {

  private static final Logger log = LoggerFactory.getLogger(LoggingActionExecutor.class);

  @Override
  public Result perform(String orgId, String actionType, String payload) {
    log.info("action {} for organization {}", actionType, orgId);
    return Result.ok("logged " + actionType);
  }
}

package io.akka.contextlayer;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.http.HttpClientProvider;
import akka.optimize.auth.Authz;
import akka.optimize.auth.AuthzConfig;
import com.typesafe.config.Config;
import io.akka.contextlayer.application.ActionExecutor;
import io.akka.contextlayer.application.CallRateLimiter;
import io.akka.contextlayer.application.ConfigSecretProvider;
import io.akka.contextlayer.application.EgressPolicy;
import io.akka.contextlayer.application.EntityStore;
import io.akka.contextlayer.application.HttpActionExecutor;
import io.akka.contextlayer.application.HttpNotifier;
import io.akka.contextlayer.application.Notifier;
import io.akka.contextlayer.application.HttpSourceCaller;
import io.akka.contextlayer.application.LoggingActionExecutor;
import io.akka.contextlayer.application.Neo4jEntityStore;
import io.akka.contextlayer.application.RoutingNotifier;
import io.akka.contextlayer.application.SmtpNotifier;
import io.akka.contextlayer.application.SecretProvider;
import io.akka.contextlayer.application.SourceCaller;
import io.akka.contextlayer.auth.ProjectResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Setup
public class Bootstrap implements ServiceSetup {

  private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

  private final Config config;
  private final Neo4jEntityStore entityStore;
  private final Map<Class<?>, Object> dependencies;

  public Bootstrap(Config config, HttpClientProvider httpClientProvider) {
    this.config = config;
    var secrets = new ConfigSecretProvider(config);
    var hosts = new ArrayList<>(config.getStringList("contextlayer.egress.allowed-hosts"));
    for (var host : config.getString("contextlayer.egress.extra-hosts").split(",")) {
      if (!host.isBlank()) hosts.add(host.strip());
    }
    var egress = new EgressPolicy(hosts);
    var limits = new HttpSourceCaller.Limits(
        config.getInt("contextlayer.limits.max-pages"),
        config.getInt("contextlayer.limits.max-records"),
        config.getInt("contextlayer.limits.max-response-bytes"),
        Duration.ofSeconds(config.getInt("contextlayer.limits.timeout-seconds")),
        config.getLong("contextlayer.limits.max-rate-wait-millis"));
    var sourceCaller = new HttpSourceCaller(secrets, egress, new CallRateLimiter(), limits, System::currentTimeMillis);
    // The driver connects on first use, so a database that is down does not stop the service starting.
    var driver = GraphDatabase.driver(
        config.getString("contextlayer.neo4j.uri"),
        AuthTokens.basic(config.getString("contextlayer.neo4j.user"), config.getString("contextlayer.neo4j.password")));
    this.entityStore = new Neo4jEntityStore(driver);

    this.dependencies = Map.of(
        ActionExecutor.class, new HttpActionExecutor(sourceCaller, limits.timeout(), new LoggingActionExecutor()),
        SecretProvider.class, secrets,
        EgressPolicy.class, egress,
        Notifier.class, new RoutingNotifier(
            new HttpNotifier(secrets, egress, limits.timeout(), List.of(Duration.ofSeconds(1), Duration.ofSeconds(4))),
            new SmtpNotifier(mail(config), secrets, egress, limits.timeout())),
        SourceCaller.class, sourceCaller,
        EntityStore.class, entityStore,
        // One for the service: the platform rule caches who it admitted.
        Authz.class, AuthzConfig.resolve(config, httpClientProvider),
        ProjectResolver.class, ProjectResolver.fixed(config.getString("contextlayer.project-id")));
  }

  private static SmtpNotifier.Settings mail(Config config) {
    var email = config.getConfig("contextlayer.notifications.email");
    return new SmtpNotifier.Settings(email.getString("host"), email.getInt("port"),
        SmtpNotifier.Security.valueOf(email.getString("security").toUpperCase(java.util.Locale.ROOT)), email.getString("user"),
        email.getString("password-ref"), email.getString("from"));
  }

  @Override
  public void onStartup() {
    try {
      entityStore.ensureSchema();
    } catch (RuntimeException e) {
      log.warn("Could not prepare the entity store; questions will fail until it is reachable: {}", e.getMessage());
    }
  }

  @Override
  public DependencyProvider createDependencyProvider() {
    return new DependencyProvider() {
      @Override
      public <T> T getDependency(Class<T> clazz) {
        var dependency = dependencies.get(clazz);
        if (dependency == null) throw new IllegalArgumentException("No dependency registered for " + clazz);
        return clazz.cast(dependency);
      }
    };
  }
}

package io.akka.contextlayer;

import akka.javasdk.DependencyProvider;
import io.akka.contextlayer.application.ActionExecutor;
import io.akka.contextlayer.application.EgressPolicy;
import io.akka.contextlayer.application.EntityStore;
import io.akka.contextlayer.application.Notifier;
import io.akka.contextlayer.application.SecretProvider;
import io.akka.contextlayer.application.SourceCaller;
import io.akka.contextlayer.domain.IdentityCluster;
import io.akka.contextlayer.domain.StructuredQuestion;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds the dependency provider for integration tests, with stand-ins for what a test does not use. */
public final class TestDependencies {

  private TestDependencies() {}

  /** The upstream stream the service consumes is replaced by one a test can publish to. */
  public static akka.javasdk.testkit.TestKit.Settings settings() {
    return akka.javasdk.testkit.TestKit.Settings.DEFAULT.withStreamIncomingMessages("claims-system", "entity_changes");
  }

  public static DependencyProvider provider(ActionExecutor executor, SourceCaller sourceCaller, EntityStore store) {
    return provider(executor, sourceCaller, store, NO_NOTIFIER);
  }

  /** Delivers nothing, and says so. */
  public static final Notifier NO_NOTIFIER = (route, message, key) -> Notifier.Result.failed("no notifier in this test");

  public static DependencyProvider provider(
      ActionExecutor executor, SourceCaller sourceCaller, EntityStore store, Notifier notifier) {
    Map<Class<?>, Object> dependencies = Map.of(
        ActionExecutor.class, executor,
        SecretProvider.class, (SecretProvider) ref -> Optional.empty(),
        SourceCaller.class, sourceCaller,
        EntityStore.class, store,
        EgressPolicy.class, new EgressPolicy(List.of("localhost")),
        Notifier.class, notifier,
        akka.optimize.auth.Authz.class, new TokenAuthz(),
        io.akka.contextlayer.auth.ProjectResolver.class, new TokenProject());
    return new DependencyProvider() {
      @Override
      public <T> T getDependency(Class<T> clazz) {
        var dependency = dependencies.get(clazz);
        if (dependency == null) throw new IllegalArgumentException("No test dependency for " + clazz);
        return clazz.cast(dependency);
      }
    };
  }

  /** For tests that never read sources. */
  public static final SourceCaller NO_SOURCES = (orgId, pack, entity, filters) ->
      SourceCaller.Result.failed("no sources in this test");

  /**
   * For tests that never read or question entities. Identity changes reach the store through a
   * consumer, so those two calls are accepted and ignored; every other use fails the test.
   */
  public static final EntityStore NO_STORE = new EntityStore() {
    @Override
    public UpsertResult upsert(String o, String s, String t, String i, List<Map<String, Object>> r, long at) {
      throw new UnsupportedOperationException("the entity store is not used in this test");
    }

    @Override
    public void linkIdentity(String o, String s, String t, String id, String rule, String key) {}

    @Override
    public void unlinkIdentity(String o, String s, String t, String id, String rule, String key) {}

    @Override
    public java.util.Set<String> entityIds(String o, String s, String t) {
      throw new UnsupportedOperationException("the entity store is not used in this test");
    }

    @Override
    public Map<String, java.util.Set<IdentityKey>> identityMemberships(String o, String s, String t) {
      throw new UnsupportedOperationException("the entity store is not used in this test");
    }

    @Override
    public void relate(String o, String s, String t, String relation, String target, Map<String, List<String>> targets) {
      throw new UnsupportedOperationException("the entity store is not used in this test");
    }

    @Override
    public Applied applyChange(String o, String s, String t, String id, Map<String, Object> r, long v, long at) {
      throw new UnsupportedOperationException("the entity store is not used in this test");
    }

    @Override
    public Applied removeChange(String o, String s, String t, String id, long v) {
      throw new UnsupportedOperationException("the entity store is not used in this test");
    }

    @Override
    public java.util.Set<IdentityKey> identityKeysOf(String o, String s, String t, String id) {
      throw new UnsupportedOperationException("the entity store is not used in this test");
    }

    @Override
    public int deleteEntities(String o, String s, String t, java.util.Collection<String> ids) {
      throw new UnsupportedOperationException("the entity store is not used in this test");
    }

    @Override
    public boolean coveredByConfirmedLinks(String o, String rule, String key) {
      return false;
    }

    @Override
    public void setIdentityStatus(String o, String rule, String key, IdentityCluster.Status status) {}

    @Override
    public QueryResult run(String o, StructuredQuestion q) {
      throw new UnsupportedOperationException("the entity store is not used in this test");
    }

    @Override
    public List<TypeSchema> schema(String o) {
      return List.of();
    }

    @Override
    public Optional<TypeSummary> summary(String o, String s, String t) {
      throw new UnsupportedOperationException("the entity store is not used in this test");
    }

    @Override
    public void deleteOrganization(String o) {
      throw new UnsupportedOperationException("the entity store is not used in this test");
    }
  };

}
